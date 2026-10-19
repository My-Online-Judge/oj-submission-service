package vn.thanhtuanle.messaging;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.event.EventEnvelope;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JudgeResultConsumerTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock VerdictPubSub verdictPubSub;
    @Mock OjMetrics ojMetrics;
    @Mock OutboxWriter outboxWriter;
    @InjectMocks JudgeResultConsumer consumer;

    private static final List<JudgeResultDto> DETAILS = List.of(
            JudgeResultDto.builder().testCase("1").result(0).cpuTime(12).realTime(15).memory(3072L).build());

    /** The row as the conditional write left it, read back by the consumer. */
    private Submission written(UUID id, int status, int rejudgeCount) {
        Submission s = Submission.builder().problemId(UUID.randomUUID())
                .status(status)
                .rejudgeCount(rejudgeCount)
                .createdAt(LocalDateTime.now())
                .build();
        s.setId(id);
        return s;
    }

    private void oneRowUpdated(UUID id, Submission reread) {
        when(submissionRepository.applyVerdict(eq(id), anyInt(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(1);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(reread));
    }

    private static SubmissionJudgedEvent accepted(UUID id) {
        return SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(SubmissionResult.ACCEPTED.getValue())
                .result(0).cpuTime(12).realTime(15).memory(3072L).details(DETAILS).build();
    }

    @Test
    void appliesVerdictThroughTheConditionalWrite() {
        UUID id = UUID.randomUUID();
        Submission s = written(id, 0, 0);
        oneRowUpdated(id, s);

        consumer.onJudged(accepted(id));

        verify(submissionRepository).applyVerdict(eq(id), eq(0), eq(0), eq(12), eq(15), eq(3072L), isNull(),
                eq(DETAILS), any(LocalDateTime.class));
        verify(submissionRepository, never()).save(any());
        // Announced to problem-service in the verdict's own transaction, keyed by problem: once, as a first verdict.
        verify(outboxWriter).append(eq("oj.submission.events"), eq(s.getProblemId().toString()),
                argThat(envelope -> envelope instanceof EventEnvelope<?> sent
                        && sent.payload().equals(new SubmissionVerdictRecorded(id, s.getProblemId(), 0, false))
                        && sent.eventType().equals("SubmissionVerdictRecorded")));
        // Routed through the after-commit entry point; with no active transaction it degenerates to an immediate
        // publish (see VerdictPubSubTest).
        verify(verdictPubSub).publishAfterCommit(id.toString());
        verify(ojMetrics).recordVerdict(0, s.getCreatedAt(), false);
    }

    @Test
    void zeroRowsMeansLateOrDuplicate_nothingElseHappens() {
        UUID id = UUID.randomUUID();
        // A finished submission (a duplicate, or a verdict after reconcile gave up) or an unknown id: 0 rows.
        when(submissionRepository.applyVerdict(eq(id), anyInt(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(0);

        consumer.onJudged(accepted(id));

        verify(submissionRepository, never()).findById(any());
        verifyNoInteractions(outboxWriter, verdictPubSub, ojMetrics);
    }

    @Test
    void anUnknownStatusIsStoredAsSystemError() {
        UUID id = UUID.randomUUID();
        oneRowUpdated(id, written(id, 5, 0));

        consumer.onJudged(SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(42).result(0).cpuTime(12).realTime(15).memory(3072L)
                .errorMessage("whatever the worker said").build());

        verify(submissionRepository).applyVerdict(eq(id), eq(5), eq(0), eq(12), eq(15), eq(3072L),
                eq("Invalid verdict status 42"), isNull(), any(LocalDateTime.class));
        verify(outboxWriter).append(eq("oj.submission.events"), any(),
                argThat(envelope -> envelope instanceof EventEnvelope<?> sent
                        && sent.payload() instanceof SubmissionVerdictRecorded recorded && recorded.verdict() == 5));
        verify(ojMetrics).recordVerdict(eq(5), any(), eq(false));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {6, 7})
    void nullOrNonTerminalStatusIsInvalidToo(Integer status) {
        UUID id = UUID.randomUUID();
        oneRowUpdated(id, written(id, 5, 0));

        consumer.onJudged(SubmissionJudgedEvent.builder().submissionId(id.toString()).status(status).build());

        verify(submissionRepository).applyVerdict(eq(id), eq(5), isNull(), isNull(), eq(0), isNull(),
                eq("Invalid verdict status " + status), isNull(), any(LocalDateTime.class));
        verify(verdictPubSub).publishAfterCommit(id.toString());
    }

    @Test
    void aRejudgedSubmissionsVerdictCarriesRejudgeTrue() {
        UUID id = UUID.randomUUID();
        Submission s = written(id, -1, 1);
        oneRowUpdated(id, s);

        consumer.onJudged(SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(SubmissionResult.WRONG_ANSWER.getValue()).result(-1)
                .cpuTime(9).realTime(11).memory(3000L).build());

        verify(outboxWriter).append(eq("oj.submission.events"), eq(s.getProblemId().toString()),
                argThat(envelope -> envelope instanceof EventEnvelope<?> sent
                        && sent.payload().equals(new SubmissionVerdictRecorded(id, s.getProblemId(), -1, true))));
        verify(ojMetrics).recordVerdict(-1, s.getCreatedAt(), true);
    }

    @Test
    void aMalformedSubmissionIdThrowsInvalidVerdictException() {
        SubmissionJudgedEvent event = SubmissionJudgedEvent.builder()
                .submissionId("not-a-uuid").status(SubmissionResult.ACCEPTED.getValue()).build();

        assertThatThrownBy(() -> consumer.onJudged(event))
                .isInstanceOf(InvalidVerdictException.class)
                .hasMessageContaining("not-a-uuid");
        verifyNoInteractions(submissionRepository, outboxWriter, verdictPubSub, ojMetrics);
    }

    @Test
    void nullTimesAndMemoryAreStoredAsZeroOrNull() {
        UUID id = UUID.randomUUID();
        oneRowUpdated(id, written(id, 0, 0));
        // A hand-replayed DLQ record or an old worker: no real_time, memory or details. The time column is NOT NULL.
        SubmissionJudgedEvent event = SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(SubmissionResult.ACCEPTED.getValue()).result(0)
                .realTime(null).memory(null).details(null).build();

        assertThatCode(() -> consumer.onJudged(event)).doesNotThrowAnyException();

        verify(submissionRepository).applyVerdict(eq(id), eq(0), eq(0), isNull(), eq(0), isNull(), isNull(),
                isNull(), any(LocalDateTime.class));
    }
}
