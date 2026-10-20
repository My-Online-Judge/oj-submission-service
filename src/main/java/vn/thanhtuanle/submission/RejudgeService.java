package vn.thanhtuanle.submission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.messaging.KafkaTopics;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.submission.dto.RejudgeQueued;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Judges finished submissions again against the problem's current test data and limits. Like a submit, a rejudge
 * reserves its queue slot first and gives it back if anything after fails; the judge spec is fetched outside any
 * transaction, and only the reset and its judge request in the outbox are one transaction. The reset is conditional
 * (never over PENDING or JUDGING), so a submission is never queued twice by concurrent rejudges.
 *
 * <p>A problem rejudge takes at most {@link #MAX_BATCH} of its newest finished submissions, optionally of one verdict.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RejudgeService {

    static final int MAX_BATCH = 500;

    private final SubmissionRepository submissionRepository;
    private final JudgeService judgeService;
    private final ProblemCatalog problemCatalog;
    private final SubmissionMapper submissionMapper;
    private final OutboxWriter outboxWriter;
    private final SubmissionAdmission admission;
    private final TransactionOperations transactions;

    public SubmissionResponseDto rejudge(UUID id) {
        Submission submission = submissionRepository.findWithLanguageById(id)
                .orElseThrow(() -> notFound(id));
        if (!SubmissionResult.isTerminal(submission.getStatus())) {
            throw new AppException(ErrorCode.SUBMISSION_NOT_FINISHED);
        }

        admission.admit(1);
        try {
            JudgeSpec spec = problemCatalog.judgeSpec(submission.getProblemSlug(), true);
            String sourceCode = submission.getSourceCode();
            Language language = submission.getLanguage();
            transactions.executeWithoutResult(status -> {
                if (submissionRepository.resetForRejudge(id, LocalDateTime.now()) == 0) {
                    throw new AppException(ErrorCode.SUBMISSION_NOT_FINISHED);
                }
                // Re-read rather than the loaded count + 1: another rejudge may have run to its verdict since the
                // load. The reset holds the row lock until commit, so this is the generation this reset set.
                queue(id, sourceCode, spec, language, submissionRepository.findRejudgeCountById(id));
            });
        } catch (RuntimeException | Error e) {
            admission.release(1);
            throw e;
        }
        log.info("Submission {} queued for a rejudge", id);

        return submissionRepository.findWithLanguageById(id)
                .map(submissionMapper::toDto)
                .orElseThrow(() -> notFound(id));
    }

    /**
     * Rejudges the newest {@link #MAX_BATCH} finished submissions of the problem, or of those only the ones with
     * verdict {@code status}. Returns how many were queued: a candidate that a concurrent rejudge queued meanwhile is
     * neither counted nor queued again, and its reserved slot is given back.
     */
    public RejudgeQueued rejudgeProblem(String slug, Integer status) {
        if (status != null && !SubmissionResult.isTerminalVerdict(status)) {
            throw new IllegalArgumentException("status must be a final verdict code");
        }
        Pageable newest = PageRequest.of(0, MAX_BATCH);
        List<UUID> candidates = status == null
                ? submissionRepository.findRejudgeCandidates(slug, newest)
                : submissionRepository.findRejudgeCandidatesWithStatus(slug, status, newest);
        if (candidates.isEmpty()) {
            return new RejudgeQueued(0);
        }

        int reserved = candidates.size();
        admission.admit(reserved);
        Integer queued;
        try {
            JudgeSpec spec = problemCatalog.judgeSpec(slug, true);
            queued = transactions.execute(tx -> resetAndQueue(candidates, spec));
        } catch (RuntimeException | Error e) {
            admission.release(reserved);
            throw e;
        }
        int count = queued == null ? 0 : queued;
        if (count < reserved) {
            admission.release(reserved - count);
        }
        log.info("Problem {} rejudge queued {} of {} submission(s)", slug, count, reserved);
        return new RejudgeQueued(count);
    }

    /**
     * Resets each candidate still finished, then queues one judge request for each row actually reset, carrying the
     * generation the reset gave it (the rows are re-read after the resets).
     */
    private int resetAndQueue(List<UUID> candidates, JudgeSpec spec) {
        LocalDateTime now = LocalDateTime.now();
        List<UUID> reset = new ArrayList<>();
        for (UUID id : candidates) {
            if (submissionRepository.resetForRejudge(id, now) == 1) {
                reset.add(id);
            }
        }
        if (reset.isEmpty()) {
            return 0;
        }
        for (Submission submission : submissionRepository.findWithLanguageByIdIn(reset)) {
            queue(submission.getId(), submission.getSourceCode(), spec, submission.getLanguage(),
                    submission.getRejudgeCount());
        }
        return reset.size();
    }

    /** Appends the judge request; the outbox relay sends it once the caller's transaction has committed. */
    private void queue(UUID id, String sourceCode, JudgeSpec spec, Language language, int rejudgeCount) {
        SubmissionRequestedEvent event =
                judgeService.buildRequestedEvent(id.toString(), sourceCode, spec, language, rejudgeCount);
        outboxWriter.append(KafkaTopics.SUBMISSION_REQUESTED, event.getSubmissionId(), event);
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Submission not found with id: " + id);
    }
}
