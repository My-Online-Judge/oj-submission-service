package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.util.ReflectionTestUtils;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubmissionSseRegistryTest {

    @Test
    void subscribeReturnsEmitter_andCompleteRemovesIt() {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        SseEmitter emitter = registry.subscribe("sub-1");
        assertThat(emitter).isNotNull();

        // complete với subscriber tồn tại: không ném lỗi
        assertThatCode(() -> registry.complete("sub-1",
                SubmissionResponseDto.builder().status(0).build())).doesNotThrowAnyException();

        // complete lần 2 (đã remove) là no-op
        assertThatCode(() -> registry.complete("sub-1",
                SubmissionResponseDto.builder().status(0).build())).doesNotThrowAnyException();
    }

    @Test
    void completeUnknownSubmissionIsNoOp() {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        assertThatCode(() -> registry.complete("nope",
                SubmissionResponseDto.builder().status(0).build())).doesNotThrowAnyException();
    }

    @Test
    void holdsOnlyALiveSubscriber() {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        assertThat(registry.holds("sub-1")).isFalse();

        registry.subscribe("sub-1");
        assertThat(registry.holds("sub-1")).isTrue();

        registry.complete("sub-1", SubmissionResponseDto.builder().status(0).build());
        assertThat(registry.holds("sub-1")).isFalse();
    }

    private static SubmissionResponseDto verdict() {
        return SubmissionResponseDto.builder().status(0).build();
    }

    @Test
    void twoStreamsOnOneSubmissionBothReceiveTheVerdict() throws Exception {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        SseEmitter a = mock(SseEmitter.class);
        SseEmitter b = mock(SseEmitter.class);
        registry.register("s1", a);
        registry.register("s1", b);

        registry.complete("s1", verdict());

        verify(a).send(any(SseEmitter.SseEventBuilder.class));
        verify(a).complete();
        verify(b).send(any(SseEmitter.SseEventBuilder.class));
        verify(b).complete();
        assertThat(registry.holds("s1")).isFalse();
        assertThat(registry.size()).isZero();
    }

    @Test
    void theNinthStreamEvictsTheOldestAndCompletesItCleanly() {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        SseEmitter oldest = mock(SseEmitter.class);
        registry.register("s1", oldest);
        for (int i = 0; i < 7; i++) {
            registry.register("s1", mock(SseEmitter.class));
        }
        verify(oldest, never()).complete();
        assertThat(registry.size()).isEqualTo(8);

        registry.register("s1", mock(SseEmitter.class));

        verify(oldest).complete();
        assertThat(registry.size()).isEqualTo(8);
        assertThat(registry.holds("s1")).isTrue();
    }

    @Test
    void anEmitterIsRemovedOnCompletionTimeoutAndError() {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        for (String field : new String[]{"completionCallback", "timeoutCallback", "errorCallback"}) {
            SseEmitter ended = registry.subscribe(field);
            SseEmitter other = registry.subscribe(field);
            assertThat(registry.size()).isEqualTo(2);

            fire(ended, field);

            assertThat(registry.size()).as(field).isEqualTo(1);
            assertThat(registry.holds(field)).isTrue();
            // a repeated callback for the same emitter changes nothing
            fire(ended, field);
            assertThat(registry.size()).isEqualTo(1);

            fire(other, field);
            assertThat(registry.holds(field)).isFalse();
            assertThat(registry.size()).isZero();
        }
    }

    /** Runs the callback Spring's emitter keeps for completion, timeout or error. */
    @SuppressWarnings("unchecked")
    private static void fire(SseEmitter emitter, String field) {
        Object cb = ReflectionTestUtils.getField(emitter, field);
        if (cb instanceof Runnable r) {
            r.run();
        } else {
            ((java.util.function.Consumer<Throwable>) cb).accept(new RuntimeException("boom"));
        }
    }

    @Test
    void theGlobalCapRefusesNewStreamsAndFreesRoomWhenOneEnds() {
        SubmissionSseRegistry registry = new SubmissionSseRegistry(8, 3);
        SseEmitter first = registry.subscribe("a");
        registry.subscribe("b");
        registry.subscribe("c");

        assertThatThrownBy(() -> registry.subscribe("d")).isInstanceOf(RateLimitedException.class);
        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.holds("d")).isFalse();

        registry.onDone("a", first);
        assertThatCode(() -> registry.subscribe("d")).doesNotThrowAnyException();
    }

    @Test
    void aSendThatThrowsAnyExceptionCompletesAndForgetsTheEmitter() throws Exception {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        registry.register("s1", emitter);

        assertThatCode(() -> registry.complete("s1", SubmissionResponseDto.builder().status(0).build()))
                .doesNotThrowAnyException();

        verify(emitter).completeWithError(any(IllegalStateException.class));
        assertThat(registry.holds("s1")).isFalse();
    }
}
