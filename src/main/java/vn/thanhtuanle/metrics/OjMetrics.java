package vn.thanhtuanle.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class OjMetrics {

    private static final List<Integer> QUEUED_STATUSES =
            List.of(SubmissionResult.PENDING.getValue(), SubmissionResult.JUDGING.getValue());

    private final MeterRegistry registry;
    private final SubmissionRepository submissionRepository;
    private final Timer latencyTimer;
    /**
     * Submissions pending or in judging, as of the last refresh, plus the slots reserved since. Admission reads it on
     * every submit and the gauge on every scrape; only {@link #refreshQueueDepth()} queries the database.
     */
    private final AtomicLong queueDepth = new AtomicLong();

    public OjMetrics(MeterRegistry registry, SubmissionRepository submissionRepository) {
        this.registry = registry;
        this.submissionRepository = submissionRepository;
        // The default range stops at 30 s, so a backlog of a few minutes all fell into +Inf and the bench's quantiles
        // read 30 s. The buckets now reach 10 minutes, with exact boundaries at round latencies from 1 s to 5 min.
        this.latencyTimer = Timer.builder("oj.judge.latency")
                .description("Submit-to-verdict latency")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofMinutes(10))
                .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15),
                        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
                        Duration.ofSeconds(300))
                .register(registry);
        Gauge.builder("oj.queue.depth", queueDepth, AtomicLong::get)
                .description("Submissions pending or in judging")
                .register(registry);
        // oj.verdict{status=...} counters are created lazily by recordVerdict on the first verdict of
        // each status. Pre-creating them at 0 here was tried and dropped: in this stack an idle zero
        // counter is dropped from the live scrape anyway, so the pre-registration bought nothing. The
        // alerts in alerts.yml are written absent-safe (`... or vector(0)`) so a status whose series
        // does not exist yet reads as 0, not no-data. See the ops memory for the investigation.
    }

    /**
     * Record one terminal verdict. A rejudge counts but is no latency sample: its row keeps the original submit's
     * {@code createdAt}. Never throws — must not break the verdict transaction.
     */
    public void recordVerdict(int status, LocalDateTime createdAt, boolean rejudge) {
        try {
            registry.counter("oj.verdict", "status", statusName(status)).increment();
            if (!rejudge) {
                latencyTimer.record(Duration.between(createdAt, LocalDateTime.now()));
            }
        } catch (Exception e) {
            log.warn("Failed to record verdict metrics: {}", e.getMessage());
        }
    }

    /** Recounts the queue; this drops the reservations made since the last refresh. A failure keeps the last value. */
    @Scheduled(fixedDelayString = "${judge.queue-depth-refresh-ms:5000}")
    public void refreshQueueDepth() {
        try {
            queueDepth.set(submissionRepository.countByStatusIn(QUEUED_STATUSES));
        } catch (Exception e) {
            log.warn("Queue depth refresh failed, keeping {}: {}", queueDepth.get(), e.getMessage());
        }
    }

    public long queueDepth() {
        return queueDepth.get();
    }

    /**
     * Takes {@code count} slots if the queue stays within {@code max}; otherwise takes none and returns false. One
     * compare-and-set, so submits that arrive between two refreshes cannot all pass on the same stale depth.
     */
    public boolean tryReserveQueueSlots(int count, long max) {
        while (true) {
            long depth = queueDepth.get();
            if (depth + count > max) {
                return false;
            }
            if (queueDepth.compareAndSet(depth, depth + count)) {
                return true;
            }
        }
    }

    /**
     * Gives back slots reserved for work that was then not queued. Never below zero: a refresh in between has already
     * dropped the reservation, and the depth is at most one window low until the next one.
     */
    public void releaseQueueSlots(int count) {
        queueDepth.updateAndGet(depth -> Math.max(0, depth - count));
    }

    private static String statusName(int status) {
        try {
            return SubmissionResult.fromValue(status).name();
        } catch (IllegalArgumentException e) {
            return "UNKNOWN_" + status;
        }
    }
}
