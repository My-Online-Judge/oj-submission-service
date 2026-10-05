package vn.thanhtuanle.messaging;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.event.EventEnvelope;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JudgeResultConsumerTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock VerdictPubSub verdictPubSub;
    @Mock OjMetrics ojMetrics;
    @Mock OutboxWriter outboxWriter;
    @InjectMocks JudgeResultConsumer consumer;

    private Submission pending(UUID id) {
        Submission s = Submission.builder().problemId(UUID.randomUUID())
                .status(SubmissionResult.PENDING.getValue())
                .createdAt(LocalDateTime.now())
                .build();
        s.setId(id);
        return s;
    }

    @Test
    void appliesVerdict_whenPending() {
        UUID id = UUID.randomUUID();
        Submission s = pending(id);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));

        SubmissionJudgedEvent e = SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(SubmissionResult.ACCEPTED.getValue())
                .result(0).cpuTime(12).realTime(15).memory(3072L).build();

        consumer.onJudged(e);

        assertThat(s.getStatus()).isEqualTo(SubmissionResult.ACCEPTED.getValue());
        assertThat(s.getCpuTime()).isEqualTo(12);
        assertThat(s.getTime()).isEqualTo(15);
        assertThat(s.getMemory()).isEqualTo(3072L);
        verify(submissionRepository).save(s);
        // Routed through the after-commit entry point; with no active transaction it
        // degenerates to an immediate publish (see VerdictPubSubTest).
        verify(verdictPubSub).publishAfterCommit(id.toString());
        // Announced to problem-service in the verdict's own transaction, keyed by problem.
        verify(outboxWriter).append(eq("oj.submission.events"), eq(s.getProblemId().toString()),
                argThat(envelope -> envelope instanceof EventEnvelope<?> sent
                        && sent.payload().equals(new SubmissionVerdictRecorded(id, s.getProblemId(), 0))
                        && sent.eventType().equals("SubmissionVerdictRecorded")));
    }

    @Test
    void ignoresResult_whenStatusIsNull() {
        UUID id = UUID.randomUUID();
        Submission s = pending(id);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));

        SubmissionJudgedEvent e = SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(null).build();

        consumer.onJudged(e);

        assertThat(s.getStatus()).isEqualTo(SubmissionResult.PENDING.getValue());
        verify(submissionRepository, never()).save(any());
        verify(verdictPubSub, never()).publishAfterCommit(any());
    }

    @Test
    void ignoresResult_whenAlreadyFinished() {
        UUID id = UUID.randomUUID();
        Submission s = Submission.builder().problemId(UUID.randomUUID()).status(SubmissionResult.ACCEPTED.getValue()).build();
        s.setId(id);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));

        SubmissionJudgedEvent e = SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(SubmissionResult.WRONG_ANSWER.getValue()).build();

        consumer.onJudged(e);

        assertThat(s.getStatus()).isEqualTo(SubmissionResult.ACCEPTED.getValue());
        verify(submissionRepository, never()).save(any());
        verify(verdictPubSub, never()).publishAfterCommit(any());
        // A duplicate verdict is not a second fact: exactly one event per submission.
        verify(outboxWriter, never()).append(any(), any(), any());
    }
}
