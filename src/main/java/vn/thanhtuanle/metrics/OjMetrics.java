package vn.thanhtuanle.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class OjMetrics {

    private final MeterRegistry registry;
    private final Timer latencyTimer;

    public OjMetrics(MeterRegistry registry, SubmissionRepository submissionRepository) {
        this.registry = registry;
        this.latencyTimer = Timer.builder("oj.judge.latency")
                .description("Submit-to-verdict latency")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("oj.queue.depth", submissionRepository, r -> queueDepth(r))
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

    private static double queueDepth(SubmissionRepository repo) {
        try {
            return repo.countByStatusIn(List.of(
                    SubmissionResult.PENDING.getValue(), SubmissionResult.JUDGING.getValue()));
        } catch (Exception e) {
            return Double.NaN;  // gauge tolerates a transient query failure
        }
    }

    private static String statusName(int status) {
        try {
            return SubmissionResult.fromValue(status).name();
        } catch (IllegalArgumentException e) {
            return "UNKNOWN_" + status;
        }
    }
}
