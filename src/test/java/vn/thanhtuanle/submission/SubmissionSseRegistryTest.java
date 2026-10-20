package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

    @Test
    void aLateCallbackOfAReplacedEmitterKeepsTheNewOne() throws Exception {
        SubmissionSseRegistry registry = new SubmissionSseRegistry();
        SseEmitter first = registry.subscribe("s1");
        registry.subscribe("s1");

        registry.onDone("s1", first);

        assertThat(registry.holds("s1")).isTrue();
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
