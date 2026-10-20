package vn.thanhtuanle.submission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

/**
 * Refuses work the judge queue cannot take. Anything that queues judgements reserves its slots in the cached queue
 * depth first: a submit or a rejudge of one submission all of them or none ({@link #admit}), a problem rejudge as many
 * as are free ({@link #admitUpTo}). A refusal is 503 QUEUE_FULL with {@code Retry-After: 30}. Work that fails after
 * reserving gives its slots back with {@link #release}; the next queue-depth refresh replaces every reservation with
 * the real count anyway. Each refusal counts in {@code oj.admission.refused}.
 */
@Component
public class SubmissionAdmission {

    public static final long RETRY_AFTER_SECONDS = 30;

    private final OjMetrics metrics;
    private final Counter refused;
    private final long maxQueueDepth;

    public SubmissionAdmission(OjMetrics metrics, MeterRegistry registry,
            @Value("${judge.max-queue-depth:500}") long maxQueueDepth) {
        this.metrics = metrics;
        // Registered here, at 0; like every oj_* counter it may still be absent from a scrape until it increments.
        this.refused = Counter.builder("oj.admission.refused")
                .description("Calls refused with 503 QUEUE_FULL because the judge queue was full")
                .register(registry);
        this.maxQueueDepth = maxQueueDepth;
    }

    /** Reserves {@code count} queue slots, or throws QUEUE_FULL having reserved nothing. Zero always passes. */
    public void admit(int count) {
        if (count <= 0) {
            return;
        }
        if (!metrics.tryReserveQueueSlots(count, maxQueueDepth)) {
            refused.increment();
            throw new RateLimitedException(ErrorCode.QUEUE_FULL, RETRY_AFTER_SECONDS);
        }
    }

    /**
     * Reserves as many of {@code wanted} slots as are free, up to all of them, and returns how many; throws QUEUE_FULL
     * having reserved nothing when none is free. Zero always passes.
     */
    public int admitUpTo(int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        int reserved = metrics.reserveQueueSlotsUpTo(wanted, maxQueueDepth);
        if (reserved == 0) {
            refused.increment();
            throw new RateLimitedException(ErrorCode.QUEUE_FULL, RETRY_AFTER_SECONDS);
        }
        return reserved;
    }

    /** Gives back {@code count} slots that {@link #admit} or {@link #admitUpTo} reserved for work then not queued. */
    public void release(int count) {
        if (count > 0) {
            metrics.releaseQueueSlots(count);
        }
    }
}
