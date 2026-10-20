package vn.thanhtuanle.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

/**
 * Refuses work the judge queue cannot take. Anything that queues judgements (a submit, a rejudge of one submission or
 * of a batch) reserves its slots in the cached queue depth first, all of them or none; a refusal is 503 QUEUE_FULL
 * with {@code Retry-After: 30}. Work that fails after {@link #admit} gives its slots back with {@link #release}; the
 * next queue-depth refresh replaces every reservation with the real count anyway.
 */
@Component
public class SubmissionAdmission {

    public static final long RETRY_AFTER_SECONDS = 30;

    private final OjMetrics metrics;
    private final long maxQueueDepth;

    public SubmissionAdmission(OjMetrics metrics, @Value("${judge.max-queue-depth:500}") long maxQueueDepth) {
        this.metrics = metrics;
        this.maxQueueDepth = maxQueueDepth;
    }

    /** Reserves {@code count} queue slots, or throws QUEUE_FULL having reserved nothing. Zero always passes. */
    public void admit(int count) {
        if (count <= 0) {
            return;
        }
        if (!metrics.tryReserveQueueSlots(count, maxQueueDepth)) {
            throw new RateLimitedException(ErrorCode.QUEUE_FULL, RETRY_AFTER_SECONDS);
        }
    }

    /** Gives back {@code count} slots that {@link #admit} reserved for work that was then not queued. */
    public void release(int count) {
        if (count > 0) {
            metrics.releaseQueueSlots(count);
        }
    }
}
