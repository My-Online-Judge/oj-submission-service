package vn.thanhtuanle.messaging;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JudgeResultConsumerMdcTest {

    @Test
    void setsMdcDuringProcessing_andClearsAfter() {
        SubmissionRepository repo = mock(SubmissionRepository.class);
        VerdictPubSub pubSub = mock(VerdictPubSub.class);
        OjMetrics metrics = mock(OjMetrics.class);
        UUID id = UUID.randomUUID();
        Submission sub = new Submission();
        sub.setProblemId(UUID.randomUUID());
        sub.setStatus(SubmissionResult.PENDING.getValue());
        sub.setCreatedAt(LocalDateTime.now().minusSeconds(2));
        when(repo.findById(id)).thenReturn(Optional.of(sub));

        AtomicReference<String> mdcDuring = new AtomicReference<>();
        doAnswer(inv -> { mdcDuring.set(MDC.get("submissionId")); return null; })
                .when(pubSub).publishAfterCommit(any());

        JudgeResultConsumer consumer = new JudgeResultConsumer(repo, pubSub, metrics, mock(OutboxWriter.class));
        SubmissionJudgedEvent event = new SubmissionJudgedEvent();
        event.setSubmissionId(id.toString());
        event.setStatus(SubmissionResult.ACCEPTED.getValue());

        consumer.onJudged(event);

        assertThat(mdcDuring.get()).isEqualTo(id.toString());  // set during processing
        assertThat(MDC.get("submissionId")).isNull();          // cleared afterward
    }
}
