package vn.thanhtuanle.run;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.enums.SubmissionResult;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The Run lane's meters ({@code oj.run.*}, Run code design §10). Counters are created on first use, so an idle one is
 * absent from a scrape until it increments — as every oj_* counter in this service (alerts are written absent-safe).
 */
@Component
public class RunMetrics {

    private final MeterRegistry registry;
    private final Timer latency;

    public RunMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.latency = Timer.builder("oj.run.latency")
                .description("Run request publish-to-reply latency")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofMinutes(2))
                .register(registry);
    }

    /** A run refused before it was queued: {@code cooldown}, {@code in_progress} or {@code busy}. */
    public void refused(String reason) {
        registry.counter("oj.run.refused", "reason", reason).increment();
    }

    /** The whole-run status of a reply: ACCEPTED for a run that reached its cases, else the verdict name. */
    public void outcome(int status) {
        registry.counter("oj.run.outcome", "status", statusName(status)).increment();
    }

    public void latency(Duration d) {
        latency.record(d);
    }

    public void timedOut() {
        registry.counter("oj.run.timed_out").increment();
    }

    /** A reply for a run nobody waits for any more (timed out, or never ours). */
    public void lateReply() {
        registry.counter("oj.run.late_replies").increment();
    }

    /** A record on run.judged this service could not read as a reply. */
    public void unreadableReply() {
        registry.counter("oj.run.unreadable_replies").increment();
    }

    /** Registers the gauge of runs this instance is waiting for. */
    public void inflight(Supplier<Number> value) {
        Gauge.builder("oj.run.inflight", value)
                .description("Runs this instance is waiting for")
                .register(registry);
    }

    private static String statusName(int status) {
        try {
            return SubmissionResult.fromValue(status).name();
        } catch (IllegalArgumentException e) {
            return "UNKNOWN_" + status;
        }
    }
}
