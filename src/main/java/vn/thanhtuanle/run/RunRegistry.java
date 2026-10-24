package vn.thanhtuanle.run;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunResponseDto;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The runs this instance is waiting for (Run code design §6): at most one per user (429 RUN_IN_PROGRESS), at most
 * {@code oj.run.max-inflight} in all (503 RUN_BUSY). A reply completes its run's HTTP response; a reply for a run
 * nobody waits for any more is counted as late and dropped. The checks and the insert are one critical section, so
 * two requests of one user, or two at the cap, cannot both pass.
 */
@Component
@Slf4j
public class RunRegistry {

    static final long IN_PROGRESS_RETRY_AFTER_SECONDS = 1;
    static final long BUSY_RETRY_AFTER_SECONDS = 10;

    private final RunProperties props;
    private final RunMetrics metrics;
    private final Map<UUID, PendingRun> pending = new HashMap<>();
    private final Set<UUID> users = new HashSet<>();

    public RunRegistry(RunProperties props, RunMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
        metrics.inflight(this::inflight);
    }

    /** Takes a slot for the user's run, or throws having taken nothing. */
    public synchronized PendingRun register(UUID userId, List<Boolean> hasExpected, boolean checker,
            DeferredResult<ApiResponse<RunResponseDto>> result) {
        if (users.contains(userId)) {
            metrics.refused("in_progress");
            throw new RateLimitedException(ErrorCode.RUN_IN_PROGRESS, IN_PROGRESS_RETRY_AFTER_SECONDS);
        }
        if (pending.size() >= props.getMaxInflight()) {
            metrics.refused("busy");
            throw new RateLimitedException(ErrorCode.RUN_BUSY, BUSY_RETRY_AFTER_SECONDS);
        }
        PendingRun run = new PendingRun(UUID.randomUUID(), userId, List.copyOf(hasExpected), checker, Instant.now(),
                result);
        pending.put(run.runId(), run);
        users.add(userId);
        return run;
    }

    /** Frees the run's slot without answering it (a timeout, a failed publish). Empty if it was not pending. */
    public synchronized Optional<PendingRun> remove(UUID runId) {
        PendingRun run = pending.remove(runId);
        if (run != null) {
            users.remove(run.userId());
        }
        return Optional.ofNullable(run);
    }

    /** Answers the run the reply names and frees its slot; false (counted as late) if no such run is pending. */
    public boolean complete(RunJudgedEvent event) {
        UUID runId;
        try {
            runId = UUID.fromString(event.runId());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("Reply for a run id that is not a UUID: {}", event.runId());
            metrics.lateReply();
            return false;
        }
        Optional<PendingRun> found = remove(runId);
        if (found.isEmpty()) {
            log.info("Late reply for run {} (timed out, or not this instance's)", runId);
            metrics.lateReply();
            return false;
        }
        PendingRun run = found.get();
        run.result().setResult(ApiResponse.success(RunResultMapper.toResponse(run, event)));
        metrics.latency(Duration.between(run.startedAt(), Instant.now()));
        metrics.outcome(event.status() == null ? 5 : event.status());
        return true;
    }

    public synchronized int inflight() {
        return pending.size();
    }
}
