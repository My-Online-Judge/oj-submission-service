package vn.thanhtuanle.submission;

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
 * Per-user submission cooldown: one atomic SET NX EX per submit. If the key is still
 * there, the user is inside the cooldown and gets a 429 whose Retry-After is the key's
 * remaining TTL. Redis being down fails OPEN — submissions beat enforcement.
 */
@Component
@Slf4j
public class SubmissionRateLimiter {

    static final String KEY_PREFIX = "oj:rl:submit:";

    private final StringRedisTemplate redis;
    private final Counter throttled;
    private final Counter fallbacks;
    private final long cooldownSeconds;

    public SubmissionRateLimiter(StringRedisTemplate redis, MeterRegistry registry,
                                 @Value("${oj.submission.cooldown-seconds:10}") long cooldownSeconds) {
        this.redis = redis;
        // Hold the Counter rather than looking it up by name on every throttle — the idiomatic
        // Micrometer pattern. NOTE: registering it here does NOT guarantee an always-visible zero
        // series. In this stack (Spring Boot 3.4 / Micrometer 1.14 / prometheus-metrics-core) an idle
        // counter that stays at 0 is dropped from the live scrape and only reappears once it
        // increments — so alerts on oj_* counters are written absent-safe in alerts.yml instead of
        // relying on a zero series being present. See the ops memory for the full investigation.
        this.throttled = Counter.builder("oj.submission.rate_limited")
                .description("Submissions rejected by the per-user cooldown")
                .register(registry);
        // Sub-project 6: the RedisUnavailable rule watches every submit let through because Redis did not answer.
        this.fallbacks = Counter.builder("oj.redis.fallback")
                .description("Requests or work that went on without Redis because a Redis call failed (fail-open)")
                .tag("lookup", "rate_limit")
                .register(registry);
        this.cooldownSeconds = cooldownSeconds;
    }

    /** Arms the cooldown atomically; throws 429 with the remaining seconds if still cooling down. */
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
            log.warn("Submission limiter unavailable, allowing: {}", e.getMessage());
            return;
        }
        long retryAfter = (remaining == null || remaining < 1) ? 1 : remaining;
        throttled.increment();
        throw new RateLimitedException(ErrorCode.SUBMISSION_RATE_LIMITED, retryAfter);
    }
}
