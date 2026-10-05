package vn.thanhtuanle.messaging;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JudgeResultConsumerMetricsTest {

    private final SubmissionRepository repo = mock(SubmissionRepository.class);
    private final VerdictPubSub pubSub = mock(VerdictPubSub.class);
    private final OjMetrics metrics = mock(OjMetrics.class);
    private final JudgeResultConsumer consumer = new JudgeResultConsumer(repo, pubSub, metrics, mock(OutboxWriter.class));

    private SubmissionJudgedEvent event(UUID id, Integer status) {
        SubmissionJudgedEvent e = new SubmissionJudgedEvent();
        e.setSubmissionId(id.toString());
        e.setStatus(status);
        return e;
    }

    @Test
    void records_onWinningVerdict() {
        UUID id = UUID.randomUUID();
        Submission sub = new Submission();
        sub.setProblemId(UUID.randomUUID());
        sub.setStatus(SubmissionResult.PENDING.getValue());
        sub.setCreatedAt(LocalDateTime.now().minusSeconds(1));
        when(repo.findById(id)).thenReturn(Optional.of(sub));
        consumer.onJudged(event(id, SubmissionResult.ACCEPTED.getValue()));
        verify(metrics).recordVerdict(eq(SubmissionResult.ACCEPTED.getValue()), any());
    }

    @Test
    void doesNotRecord_onDuplicateTerminal() {
        UUID id = UUID.randomUUID();
        Submission sub = new Submission();
        sub.setProblemId(UUID.randomUUID());
        sub.setStatus(SubmissionResult.ACCEPTED.getValue());
        when(repo.findById(id)).thenReturn(Optional.of(sub));
        consumer.onJudged(event(id, SubmissionResult.WRONG_ANSWER.getValue()));
        verify(metrics, never()).recordVerdict(anyInt(), any());
    }

    @Test
    void doesNotRecord_onNullStatus() {
        UUID id = UUID.randomUUID();
        Submission sub = new Submission();
        sub.setProblemId(UUID.randomUUID());
        sub.setStatus(SubmissionResult.PENDING.getValue());
        when(repo.findById(id)).thenReturn(Optional.of(sub));
        consumer.onJudged(event(id, null));
        verify(metrics, never()).recordVerdict(anyInt(), any());
    }

    @Test
    void doesNotRecord_onUnknownSubmission() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        consumer.onJudged(event(id, SubmissionResult.ACCEPTED.getValue()));
        verify(metrics, never()).recordVerdict(anyInt(), any());
    }
}
