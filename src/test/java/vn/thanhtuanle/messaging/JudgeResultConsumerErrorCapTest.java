package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JudgeResultConsumerErrorCapTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock VerdictPubSub verdictPubSub;
    @Mock OjMetrics ojMetrics;
    @Mock OutboxWriter outboxWriter;
    @InjectMocks JudgeResultConsumer consumer;

    @Test
    void errorMessageLongerThanCapIsTruncated() {
        UUID id = UUID.randomUUID();

        consumer.onJudged(SubmissionJudgedEvent.builder()
                .submissionId(id.toString())
                .status(SubmissionResult.COMPILE_ERROR.getValue())
                .errorMessage("x".repeat(70000))
                .build());

        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(submissionRepository).applyVerdict(eq(id), eq(SubmissionResult.COMPILE_ERROR.getValue()), any(), any(),
                anyInt(), any(), errorMessage.capture(), any(), any());
        assertThat(errorMessage.getValue()).hasSize(JudgeResultConsumer.MAX_ERROR_MESSAGE_CHARS)
                .hasSize(65536);
    }
}
