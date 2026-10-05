package vn.thanhtuanle.submission;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.thanhtuanle.common.constant.Permissions;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.messaging.KafkaTopics;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.security.CurrentUser;
import vn.thanhtuanle.submission.dto.SubmissionRequestDto;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.language.LanguageRepository;


import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final JudgeService judgeService;
    private final ProblemCatalog problemCatalog;
    private final LanguageRepository languageRepository;
    private final SubmissionMapper submissionMapper;
    private final OutboxWriter outboxWriter;
    private final SubmissionSseRegistry sseRegistry;
    private final SubmissionDetailAssembler detailAssembler;
    // Shared transactional EntityManager proxy — used only for refresh() in streamVerdict.
    private final EntityManager entityManager;
    private final SubmissionRateLimiter submissionRateLimiter;
    private final CurrentUser currentUser;

    @Transactional
    public SubmissionResponseDto submit(SubmissionRequestDto req) {
        log.info("Start submission for problem: {}", req.getProblemSlug());
        judgeService.validate(req.getSourceCode());

        // Resolved first — limits and the bundle version included — so neither an unknown problem nor one
        // that cannot be judged burns the cooldown below.
        JudgeSpec spec = problemCatalog.judgeSpec(req.getProblemSlug());
        Language language = languageRepository.findByIdentifier(req.getLanguageIdentifier())
                .filter(l -> !l.isDisabled())
                .orElseThrow(() -> new ResourceNotFoundException("Language not found"));
        UUID userId = currentUser.id();

        // Cooldown gate: sits after problem/language/user resolve so a 404 never burns it,
        // and before the row exists so a throttled submit leaves no trace.
        submissionRateLimiter.acquire(userId);

        Submission submission = createPendingSubmission(req, spec, language, userId);
        submissionRepository.save(submission);
        MDC.put("submissionId", submission.getId().toString());
        try {
            SubmissionRequestedEvent event = judgeService.buildRequestedEvent(
                    submission.getId().toString(), submission.getSourceCode(), spec, language);
            // Sent by the outbox relay once this transaction has committed — never for a rolled-back row.
            outboxWriter.append(KafkaTopics.SUBMISSION_REQUESTED, event.getSubmissionId(), event);
            log.info("Submission {} queued for judging", submission.getId());
            return submissionMapper.toDto(submission);
        } finally {
            MDC.remove("submissionId");
        }
    }

    private Submission createPendingSubmission(SubmissionRequestDto req, JudgeSpec spec, Language language,
            UUID userId) {
        return Submission.builder()
                .sourceCode(req.getSourceCode())
                .problemId(spec.problemId())
                .problemSlug(spec.problemSlug())
                .userId(userId)
                .language(language)
                .time(0)
                .memory(0L)
                .status(SubmissionResult.PENDING.getValue())
                .shareSubmission(req.getShareSubmission())
                .build();
    }

    /** An unknown slug gives an empty page: the slug is matched on the submissions, no problem is looked up. */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionResponseDto> getSubmissionsByProblemSlug(String problemSlug, int page, int size) {
        log.info("Service to get submissions by problem slug: {}", problemSlug);

        Pageable pageable = PageRequest.of(page, size);
        return PageResponse.of(submissionRepository
                .findByProblemSlugOrderByCreatedAtDesc(problemSlug, pageable)
                .map(submissionMapper::toDto));
    }

    @Transactional(readOnly = true)
    public SubmissionResponseDto getSubmissionById(String id) {
        log.info("Service to get submission by id: {}", id);
        Submission submission = submissionRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found with id: " + id));
        assertCanRead(submission, id);
        return submissionMapper.toDto(submission, detailAssembler.assemble(submission));
    }

    @Transactional(readOnly = true)
    public SseEmitter streamVerdict(String id) {
        // Load + authorize BEFORE subscribing: an unknown id must 404 identically to a denied
        // id (no existence oracle), and no emitter may ever be registered for a request that
        // fails either check (registering first would let a denied/unknown request evict a
        // legitimate owner's live emitter — an eviction DoS). This first read ONLY authorizes;
        // it is never the basis for the replay decision.
        UUID submissionId = UUID.fromString(id);
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission not found with id: " + id));
        assertCanRead(submission, id);

        // Now that the caller is authorized, register the emitter. The verdict may have landed
        // in the window between the read above and subscribe(): if the consumer's publish ran
        // before the emitter existed, the replay decision must come from a GENUINELY fresh
        // read. A second findById would not be one — within this persistence context it returns
        // the already-managed (stale) instance without touching the database. The scalar status
        // query below always hits the database (see the warning on findStatusById), so it sees
        // the committed verdict; refresh() then forces a DB re-read into the managed instance
        // so the replay payload matches. Together with writers publishing only AFTER their
        // commit (VerdictPubSub.publishAfterCommit), this closes the race completely: a
        // subscriber either registers before the post-commit publish (receives it live) or
        // re-reads after the commit (sees terminal here and replays).
        SseEmitter emitter = sseRegistry.subscribe(id);
        Integer freshStatus = submissionRepository.findStatusById(submissionId);
        if (freshStatus != null && SubmissionResult.isTerminal(freshStatus)) {
            entityManager.refresh(submission);
            log.info("Submission {} already terminal (status={}) at subscribe, replaying verdict",
                    id, submission.getStatus());
            sseRegistry.complete(id, submissionMapper.toDto(submission, detailAssembler.assemble(submission)));
        }
        // Null (row gone) or non-terminal: no replay — the live post-commit publish or the
        // registry timeout takes it from here.
        return emitter;
    }

    @Transactional(readOnly = true)
    public PageResponse<SubmissionResponseDto> getSubmissionsByUser(String userId, int page, int size) {
        log.info("Service to get submissions by user_id: {}", userId);

        UUID requested = UUID.fromString(userId);
        if (!requested.equals(currentUser.id()) && !hasReadAnyAuthority()) {
            throw new ResourceNotFoundException("Submissions not found for user: " + userId);
        }

        Pageable pageable = PageRequest.of(page, size);

        Page<Submission> submissionPage = submissionRepository.findByUserIdOrderByCreatedAtDesc(UUID.fromString(userId),
                pageable);

        log.info("Found {} submissions for user_id: {}", submissionPage.getTotalElements(), userId);
        return PageResponse.of(submissionPage.map(submissionMapper::toDto));
    }

    @Transactional(readOnly = true)
    public PageResponse<SubmissionResponseDto> getSubmissionsByUserAndProblem(String userId, String problemSlug,
            int page, int size) {
        log.info("Service to get submissions by user_id: {} and problem_slug: {}", userId, problemSlug);

        UUID requested = UUID.fromString(userId);
        if (!requested.equals(currentUser.id()) && !hasReadAnyAuthority()) {
            throw new ResourceNotFoundException("Submissions not found for user: " + userId);
        }

        Pageable pageable = PageRequest.of(page, size);

        Page<Submission> submissionPage = submissionRepository
                .findByUserIdAndProblemSlugOrderByCreatedAtDesc(UUID.fromString(userId), problemSlug, pageable);

        log.info("Found {} submissions for user_id: {} and problem_slug: {}", submissionPage.getTotalElements(), userId,
                problemSlug);
        return PageResponse.of(submissionPage.map(submissionMapper::toDto));
    }

    /**
     * Owner-or-admin gate. Throws {@link ResourceNotFoundException} — deliberately a 404 rather
     * than a 403, so the response does not confirm that an id exists.
     */
    private void assertCanRead(Submission submission, String id) {
        UUID current = currentUser.id();
        boolean isOwner = current.equals(submission.getUserId());
        if (isOwner || hasReadAnyAuthority()) {
            return;
        }
        log.warn("User {} denied access to submission {}", current, id);
        throw new ResourceNotFoundException("Submission not found with id: " + id);
    }

    private boolean hasReadAnyAuthority() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.SUBMISSION_READ_ANY.equals(a.getAuthority()));
    }
}
