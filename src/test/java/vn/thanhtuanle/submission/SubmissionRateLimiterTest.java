package vn.thanhtuanle.submission;

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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubmissionRateLimiterTest {

    private static final UUID USER = UUID.fromString("d0000000-0000-0000-0000-000000000001");

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    SubmissionRateLimiter limiter;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        registry = new SimpleMeterRegistry();
        limiter = new SubmissionRateLimiter(redis, registry, 10);
    }

    @Test
    void firstSubmission_armsCooldown_andPasses() {
        when(valueOps.setIfAbsent(eq("oj:rl:submit:" + USER), eq("1"), eq(Duration.ofSeconds(10)))).thenReturn(true);
        assertThatCode(() -> limiter.acquire(USER)).doesNotThrowAnyException();
    }

    @Test
    void withinCooldown_throwsWithRemainingSeconds() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.getExpire("oj:rl:submit:" + USER, TimeUnit.SECONDS)).thenReturn(7L);

        RateLimitedException ex = catchThrowableOfType(() -> limiter.acquire(USER), RateLimitedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(7);
    }

    @Test
    void expiredButPresentKey_clampsRetryAfterToAtLeast1() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(0L);

        RateLimitedException ex = catchThrowableOfType(() -> limiter.acquire(USER), RateLimitedException.class);

        assertThat(ex.getRetryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void redisDown_failsOpen() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new RuntimeException("down"));
        assertThatCode(() -> limiter.acquire(USER)).doesNotThrowAnyException();
    }

    @Test
    void redisDown_isCountedAsARedisFallback() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new RuntimeException("down"));

        limiter.acquire(USER);

        assertThat(registry.counter("oj.redis.fallback", "lookup", "rate_limit").count()).isEqualTo(1);
    }

    @Test
    void releaseDeletesTheCooldownKey() {
        limiter.release(USER);

        verify(redis).delete("oj:rl:submit:" + USER);
    }

    @Test
    void aReleaseWithoutRedisDoesNotThrow() {
        when(redis.delete(anyString())).thenThrow(new RuntimeException("down"));

        assertThatCode(() -> limiter.release(USER)).doesNotThrowAnyException();
    }
}
