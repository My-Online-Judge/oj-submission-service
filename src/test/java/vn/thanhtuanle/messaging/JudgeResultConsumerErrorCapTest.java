package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
        Submission s = Submission.builder().problemId(UUID.randomUUID())
                .status(SubmissionResult.PENDING.getValue())
                .createdAt(LocalDateTime.now()).build();
        s.setId(id);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));

        consumer.onJudged(SubmissionJudgedEvent.builder()
                .submissionId(id.toString())
                .status(SubmissionResult.COMPILE_ERROR.getValue())
                .errorMessage("x".repeat(70000))
                .build());

        assertThat(s.getErrorMessage()).hasSize(JudgeResultConsumer.MAX_ERROR_MESSAGE_CHARS)
                .hasSize(65536);
    }
}
