package vn.thanhtuanle.submission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.messaging.VerdictPubSub;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.event.EventEnvelope;
import vn.thanhtuanle.oj.common.event.OjTopics;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.submission.SubmissionTimeouts.TimedOut;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionReconcileJobTest {

    @Mock SubmissionTimeouts timeouts;
    @Mock OutboxWriter outboxWriter;
    @Mock VerdictPubSub verdictPubSub;

    private final CountingTransactions transactions = new CountingTransactions();
    private SubmissionReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new SubmissionReconcileJob(timeouts, outboxWriter, verdictPubSub, transactions,
                Duration.ofMinutes(3), Duration.ofMinutes(30));
    }

    @Test
    void eachTimedOutRowGetsOneEventAndOnePush() {
        TimedOut judging1 = new TimedOut(UUID.randomUUID(), UUID.randomUUID(), false);
        TimedOut judging2 = new TimedOut(UUID.randomUUID(), UUID.randomUUID(), true);
        TimedOut pending = new TimedOut(UUID.randomUUID(), UUID.randomUUID(), false);
        when(timeouts.failJudgingStartedBefore(any(), any())).thenReturn(List.of(judging1, judging2));
        when(timeouts.failPendingCreatedBefore(any(), any())).thenReturn(List.of(pending));
        List<Boolean> appendedInATransaction = new ArrayList<>();
        doAnswer(call -> appendedInATransaction.add(transactions.open))
                .when(outboxWriter).append(anyString(), anyString(), any());

        job.reconcile();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> envelopes = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter, times(3)).append(eq(OjTopics.SUBMISSION_EVENTS), keys.capture(), envelopes.capture());
        assertThat(keys.getAllValues()).containsExactly(
                judging1.problemId().toString(), judging2.problemId().toString(), pending.problemId().toString());
        assertThat(envelopes.getAllValues()).<Object>map(envelope -> ((EventEnvelope<?>) envelope).payload())
                .containsExactly(
                new SubmissionVerdictRecorded(judging1.id(), judging1.problemId(), 5, false),
                new SubmissionVerdictRecorded(judging2.id(), judging2.problemId(), 5, true),
                new SubmissionVerdictRecorded(pending.id(), pending.problemId(), 5, false));
        verify(verdictPubSub).publishAfterCommit(judging1.id().toString());
        verify(verdictPubSub).publishAfterCommit(judging2.id().toString());
        verify(verdictPubSub).publishAfterCommit(pending.id().toString());
        verify(verdictPubSub, times(3)).publishAfterCommit(anyString());
        // Each rule in its own transaction, its events written inside it.
        assertThat(transactions.count).isEqualTo(2);
        assertThat(appendedInATransaction).containsExactly(true, true, true);
    }

    @Test
    void thresholdsAreThreeAndThirtyMinutesAgo() {
        when(timeouts.failJudgingStartedBefore(any(), any())).thenReturn(List.of());
        when(timeouts.failPendingCreatedBefore(any(), any())).thenReturn(List.of());

        job.reconcile();

        LocalDateTime now = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> judgingThreshold = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> judgingNow = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timeouts).failJudgingStartedBefore(judgingThreshold.capture(), judgingNow.capture());
        ArgumentCaptor<LocalDateTime> pendingThreshold = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> pendingNow = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timeouts).failPendingCreatedBefore(pendingThreshold.capture(), pendingNow.capture());
        assertThat(judgingThreshold.getValue()).isCloseTo(now.minusMinutes(3), within(5, SECONDS));
        assertThat(pendingThreshold.getValue()).isCloseTo(now.minusMinutes(30), within(5, SECONDS));
        assertThat(judgingNow.getValue()).isCloseTo(now, within(5, SECONDS));
        assertThat(pendingNow.getValue()).isCloseTo(now, within(5, SECONDS));
    }

    @Test
    void nothingTimedOutWritesNothing() {
        when(timeouts.failJudgingStartedBefore(any(), any())).thenReturn(List.of());
        when(timeouts.failPendingCreatedBefore(any(), any())).thenReturn(List.of());

        job.reconcile();

        verifyNoInteractions(outboxWriter, verdictPubSub);
    }

    /** Runs each callback at once, counting the transactions and telling whether one is open. */
    private static final class CountingTransactions implements TransactionOperations {
        int count;
        boolean open;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            count++;
            open = true;
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                open = false;
            }
        }
    }
}
