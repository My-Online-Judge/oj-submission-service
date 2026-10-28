package vn.thanhtuanle.run;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunResponseDto;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The pending runs of one instance (Run code design §6): one per user, at most max-inflight, freed on reply or removal. */
class RunRegistryTest {

    SimpleMeterRegistry meters;
    RunRegistry registry;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = new RunRegistry(new RunProperties(), new RunMetrics(meters));   // max-inflight 20
    }

    private static DeferredResult<ApiResponse<RunResponseDto>> deferred() {
        return new DeferredResult<>();
    }

    private static RunJudgedEvent reply(String runId) {
        JudgeResultDto one = JudgeResultDto.builder().testCase("1").result(0).error(0).cpuTime(1).memory(1L).build();
        return new RunJudgedEvent(runId, 0, 0, 1, 1, 1L, null, List.of(one), null);
    }

    @Test
    void theSameUserCannotHaveTwoPendingRuns() {
        UUID user = UUID.randomUUID();
        registry.register(user, List.of(true), false, deferred());

        RateLimitedException ex = catchThrowableOfType(
                () -> registry.register(user, List.of(true), false, deferred()), RateLimitedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RUN_IN_PROGRESS);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(1L);
        assertThat(meters.counter("oj.run.refused", "reason", "in_progress").count()).isEqualTo(1.0);
        assertThat(registry.inflight()).isEqualTo(1);
    }

    @Test
    void theTwentyFirstRunIsRefusedAsBusy() {
        for (int i = 0; i < 20; i++) {
            registry.register(UUID.randomUUID(), List.of(true), false, deferred());
        }

        RateLimitedException ex = catchThrowableOfType(
                () -> registry.register(UUID.randomUUID(), List.of(true), false, deferred()),
                RateLimitedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RUN_BUSY);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(10L);
        assertThat(meters.counter("oj.run.refused", "reason", "busy").count()).isEqualTo(1.0);
        assertThat(registry.inflight()).isEqualTo(20);
    }

    @Test
    void completingARunFreesItsSlotAndItsUser() {
        UUID user = UUID.randomUUID();
        DeferredResult<ApiResponse<RunResponseDto>> result = deferred();
        PendingRun run = registry.register(user, List.of(true), false, result);

        assertThat(registry.complete(reply(run.runId().toString()))).isTrue();

        assertThat(registry.inflight()).isZero();
        assertThat(result.hasResult()).isTrue();
        @SuppressWarnings("unchecked")
        ApiResponse<RunResponseDto> body = (ApiResponse<RunResponseDto>) result.getResult();
        assertThat(body.getData().cases()).hasSize(1);
        assertThat(meters.counter("oj.run.outcome", "status", "ACCEPTED").count()).isEqualTo(1.0);
        assertThat(meters.timer("oj.run.latency").count()).isEqualTo(1L);
        assertThatCode(() -> registry.register(user, List.of(true), false, deferred())).doesNotThrowAnyException();
    }

    @Test
    void aReplyForAnUnknownRunIsALateReply() {
        assertThat(registry.complete(reply(UUID.randomUUID().toString()))).isFalse();
        assertThat(registry.complete(reply("not-a-uuid"))).isFalse();

        assertThat(meters.counter("oj.run.late_replies").count()).isEqualTo(2.0);
    }

    @Test
    void removingARunFreesItsSlotWithoutAnswering() {
        DeferredResult<ApiResponse<RunResponseDto>> result = deferred();
        PendingRun run = registry.register(UUID.randomUUID(), List.of(true), false, result);

        assertThat(registry.remove(run.runId())).isPresent();
        assertThat(registry.remove(run.runId())).isEmpty();

        assertThat(result.hasResult()).isFalse();
        assertThat(registry.inflight()).isZero();
        assertThat(meters.get("oj.run.inflight").gauge().value()).isZero();
    }
}
