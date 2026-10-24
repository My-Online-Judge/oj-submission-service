package vn.thanhtuanle.run;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Run cooldown (Run code design §6): the submit cooldown's mechanism under its own key and its own code. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunRateLimiterTest {

    private static final UUID USER = UUID.fromString("d0000000-0000-0000-0000-000000000001");

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    SimpleMeterRegistry registry;
    RunRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        registry = new SimpleMeterRegistry();
        limiter = new RunRateLimiter(redis, registry, new RunMetrics(registry), 3);
    }

    @Test
    void firstRun_armsTheRunCooldown_andPasses() {
        when(valueOps.setIfAbsent(eq("oj:rl:run:" + USER), eq("1"), eq(Duration.ofSeconds(3)))).thenReturn(true);

        assertThatCode(() -> limiter.acquire(USER)).doesNotThrowAnyException();
    }

    @Test
    void withinCooldown_throwsRunRateLimitedWithRemainingSeconds() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.getExpire("oj:rl:run:" + USER, TimeUnit.SECONDS)).thenReturn(2L);

        RateLimitedException ex = catchThrowableOfType(() -> limiter.acquire(USER), RateLimitedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RUN_RATE_LIMITED);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(2L);
        assertThat(registry.counter("oj.run.refused", "reason", "cooldown").count()).isEqualTo(1.0);
    }

    @Test
    void redisDown_failsOpen_andCountsTheFallback() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> limiter.acquire(USER)).doesNotThrowAnyException();
        assertThat(registry.counter("oj.redis.fallback", "lookup", "run_rate_limit").count()).isEqualTo(1.0);
    }

    @Test
    void release_liftsTheRunCooldownOnly() {
        limiter.release(USER);

        verify(redis).delete("oj:rl:run:" + USER);
    }
}
