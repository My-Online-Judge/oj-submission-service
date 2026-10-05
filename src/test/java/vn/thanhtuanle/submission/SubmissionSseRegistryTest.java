package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
}
