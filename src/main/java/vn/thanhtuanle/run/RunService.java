package vn.thanhtuanle.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.common.constant.Permissions;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.judge.CheckerLanguages;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.messaging.KafkaTopics;
import vn.thanhtuanle.messaging.event.RunRequestedEvent;
import vn.thanhtuanle.messaging.event.RunTestCase;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.oj.common.security.CurrentUser;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunCaseDto;
import vn.thanhtuanle.run.dto.RunRequestDto;
import vn.thanhtuanle.run.dto.RunResponseDto;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * A run (Run code design §5, §6): the user's code on the given cases, through the run lane, answered when the worker
 * replies. Nothing is stored. Everything that can refuse the request comes before the cooldown is armed; anything that
 * fails after it gives the cooldown and the slot back. The HTTP request is held open as a {@link DeferredResult} that
 * the reply listener ({@code RunRegistry.complete}) or the timeout completes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunService {

    private final JudgeService judgeService;
    private final ProblemCatalog problemCatalog;
    private final LanguageRepository languageRepository;
    private final CheckerLanguages checkerLanguages;
    private final CurrentUser currentUser;
    private final RunRequestValidator validator;
    private final RunRateLimiter rateLimiter;
    private final RunRegistry registry;
    private final RunProperties props;
    private final RunMetrics metrics;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeferredResult<ApiResponse<RunResponseDto>> run(RunRequestDto req) {
        judgeService.validate(req.getSourceCode());
        List<RunCaseDto> cases = validator.validate(req);

        // Resolved first — limits, the checker and the contest included — so neither an unknown problem, one that
        // cannot be judged, a contest that is not running nor an unknown language burns the cooldown below.
        UUID userId = currentUser.id();
        JudgeSpec spec = problemCatalog.judgeSpec(req.getProblemSlug(), mayEditProblems(), req.getContestSlug(), userId);
        Language language = languageRepository.findByIdentifier(req.getLanguageIdentifier())
                .filter(l -> !l.isDisabled())
                .orElseThrow(() -> new ResourceNotFoundException("Language not found"));
        Language checkerLanguage = checkerLanguages.forSpec(spec);
        boolean checker = spec.spjVersion() != null;
        List<Boolean> hasExpected = cases.stream().map(c -> c.getExpectedOutput() != null).toList();

        rateLimiter.acquire(userId);
        UUID runId = null;
        try {
            DeferredResult<ApiResponse<RunResponseDto>> deferred = new DeferredResult<>(props.getTimeout().toMillis());
            PendingRun pending = registry.register(userId, hasExpected, checker, deferred);
            runId = pending.runId();
            MDC.put("submissionId", runId.toString());
            try {
                RunRequestedEvent event = buildEvent(runId, req.getSourceCode(), spec, language, checkerLanguage,
                        checker, cases);
                UUID id = runId;
                deferred.onTimeout(() -> expire(id, deferred));
                kafkaTemplate.send(KafkaTopics.RUN_REQUESTED, runId.toString(), event).whenComplete((sent, ex) -> {
                    if (ex != null) {
                        // Kafka did not take the request: the run cannot happen. Freed here, answered with the cause
                        // (the shared handler's 500 with a traceId); the cooldown is lifted so the user may retry.
                        registry.remove(id).ifPresent(p -> {
                            log.error("Run {} could not be published", id, ex);
                            rateLimiter.release(userId);
                            deferred.setErrorResult(ex);
                        });
                    }
                });
                log.info("Run {} queued for problem {} ({} cases)", runId, spec.problemSlug(), cases.size());
                return deferred;
            } finally {
                MDC.remove("submissionId");
            }
        } catch (RuntimeException | Error e) {
            if (runId != null) {
                registry.remove(runId);
            }
            rateLimiter.release(userId);
            throw e;
        }
    }

    /** The wait is over: the run's slot is freed and the request answered 504, if it is still pending. */
    void expire(UUID runId, DeferredResult<ApiResponse<RunResponseDto>> deferred) {
        registry.remove(runId).ifPresent(p -> {
            log.warn("Run {} got no reply within {}", runId, props.getTimeout());
            metrics.timedOut();
            deferred.setErrorResult(new AppException(ErrorCode.RUN_TIMED_OUT));
        });
    }

    private RunRequestedEvent buildEvent(UUID runId, String source, JudgeSpec spec, Language language,
            Language checkerLanguage, boolean checker, List<RunCaseDto> cases) {
        long expiresAt = clock.millis() + props.getTimeout().toMillis();
        SubmissionRequestedEvent template = judgeService.buildRequestedEvent(runId.toString(), source, spec, language,
                0, checkerLanguage);
        // The sandbox requires an expected output per case unless a checker judges it: an empty one for a case the
        // user gave none for (RunResultMapper turns the resulting WRONG_ANSWER back into "ran").
        List<RunTestCase> testCases = cases.stream()
                .map(c -> new RunTestCase(c.getInput(),
                        checker ? null : (c.getExpectedOutput() == null ? "" : c.getExpectedOutput())))
                .toList();
        RunRequestedEvent event = RunRequestedEvent.from(template, runId.toString(), expiresAt, testCases);
        int bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(event).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Run request could not be serialised", e);
        }
        if (bytes > props.getMaxEventBytes()) {
            throw new IllegalArgumentException("Run request is too large");
        }
        return event;
    }

    /** Holders of problem:update may try out a problem that is still INACTIVE (as for a submit). */
    private static boolean mayEditProblems() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permissions.PROBLEM_UPDATE.equals(a.getAuthority()));
    }
}
