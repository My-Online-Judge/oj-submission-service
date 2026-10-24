package vn.thanhtuanle.run;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Per-user Run cooldown (Run code design §6): the submit cooldown's mechanism — one atomic SET NX EX, a 429 whose
 * Retry-After is the key's remaining TTL, Redis down fails OPEN — under its own key prefix and its own code, so a run
 * never arms the submit cooldown and a submit never arms this one.
 */
@Component
@Slf4j
public class RunRateLimiter {

    static final String KEY_PREFIX = "oj:rl:run:";

    private final StringRedisTemplate redis;
    private final RunMetrics metrics;
    private final Counter fallbacks;
    private final long cooldownSeconds;

    public RunRateLimiter(StringRedisTemplate redis, MeterRegistry registry, RunMetrics metrics,
                          @Value("${oj.run.cooldown-seconds:3}") long cooldownSeconds) {
        this.redis = redis;
        this.metrics = metrics;
        // The RedisUnavailable rule watches every call let through because Redis did not answer.
        this.fallbacks = Counter.builder("oj.redis.fallback")
                .description("Requests or work that went on without Redis because a Redis call failed (fail-open)")
                .tag("lookup", "run_rate_limit")
                .register(registry);
        this.cooldownSeconds = cooldownSeconds;
    }

    /** Arms the cooldown atomically; throws 429 RUN_RATE_LIMITED with the remaining seconds if still cooling down. */
    public void acquire(UUID userId) {
        String key = KEY_PREFIX + userId;
        Long remaining;
        try {
            Boolean armed = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(cooldownSeconds));
            if (!Boolean.FALSE.equals(armed)) {
                return; // armed now (TRUE) — or a null answer from a flaky proxy: fail open
            }
            remaining = redis.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            fallbacks.increment();
            log.warn("Run limiter unavailable, allowing: {}", e.getMessage());
            return;
        }
        long retryAfter = (remaining == null || remaining < 1) ? 1 : remaining;
        metrics.refused("cooldown");
        throw new RateLimitedException(ErrorCode.RUN_RATE_LIMITED, retryAfter);
    }

    /** Lifts the cooldown that {@link #acquire} armed, for a run that then was not queued. Best effort. */
    public void release(UUID userId) {
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Could not release the run cooldown: {}", e.getMessage());
        }
    }
}
