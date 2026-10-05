package vn.thanhtuanle.submission;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.event.EventEnvelope;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.messaging.VerdictPubSub;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionReconcileJobTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock VerdictPubSub verdictPubSub;
    @Mock StringRedisTemplate redisTemplate;
    @Mock VerdictPush verdictPush;
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Mock OutboxWriter outboxWriter;
    @InjectMocks SubmissionReconcileJob job;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(job, "stuckTimeoutMin", 5L);
    }

    @Test
    void flipsStuckSubmissionToSystemError_andNotifiesSse() {
        UUID id = UUID.randomUUID();
        Submission stuck = Submission.builder().problemId(UUID.randomUUID()).status(SubmissionResult.PENDING.getValue()).build();
        stuck.setId(id);
        when(submissionRepository.findStuck(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of(stuck));

        job.reconcileStuck();

        assertThat(stuck.getStatus()).isEqualTo(SubmissionResult.SYSTEM_ERROR.getValue());
        assertThat(stuck.getErrorMessage()).contains("timed out");
        verify(submissionRepository).save(stuck);
        verify(verdictPubSub).publishAfterCommit(id.toString());
        verify(outboxWriter).append(eq("oj.submission.events"), eq(stuck.getProblemId().toString()),
                argThat(envelope -> envelope instanceof EventEnvelope<?> sent
                        && sent.payload().equals(new SubmissionVerdictRecorded(id, stuck.getProblemId(),
                                SubmissionResult.SYSTEM_ERROR.getValue()))));
    }

    @Test
    void publishesReconciledVerdict_onlyAfterCommit_forEveryStuckSubmission() {
        // reconcileStuck is @Transactional and flips N stuck submissions in a loop before its
        // single commit: publishing mid-loop would let a subscriber's fresh re-read still see
        // PENDING while the live publish has already passed it by (same lost-verdict window as
        // the consumer). Two stuck submissions here pin that the loop registers ONE deferred
        // publish PER iteration — not just that a single-element case happens to defer.
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Submission stuck1 = Submission.builder().problemId(UUID.randomUUID()).status(SubmissionResult.PENDING.getValue()).build();
        stuck1.setId(id1);
        Submission stuck2 = Submission.builder().problemId(UUID.randomUUID()).status(SubmissionResult.PENDING.getValue()).build();
        stuck2.setId(id2);
        when(submissionRepository.findStuck(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of(stuck1, stuck2));

        // Real VerdictPubSub (not a spy on `publish` — it is package-private now, and this test
        // lives outside vn.thanhtuanle.messaging) wired to a mocked Redis template, so the
        // wire-level effect of each deferred publish is observable via convertAndSend, the same
        // way VerdictPubSubTest asserts it.
        VerdictPubSub realPubSub = new VerdictPubSub(redisTemplate, objectMapper, verdictPush);
        SubmissionReconcileJob txJob = new SubmissionReconcileJob(
                submissionRepository, realPubSub, outboxWriter);
        ReflectionTestUtils.setField(txJob, "stuckTimeoutMin", 5L);

        // Simulate the @Transactional wrapper: synchronization active during reconcileStuck.
        TransactionSynchronizationManager.initSynchronization();
        try {
            txJob.reconcileStuck();

            // Both state writes happened inside the transaction…
            verify(submissionRepository).save(stuck1);
            verify(submissionRepository).save(stuck2);
            // …but nothing may reach the wire before commit, for either submission.
            verify(redisTemplate, never()).convertAndSend(any(), any());
            // One deferred registration per loop iteration — not one for the whole batch.
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(2);

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(2)).convertAndSend(eq(VerdictPubSub.CHANNEL), bodies.capture());
        assertThat(bodies.getAllValues())
                .hasSize(2)
                .anySatisfy(body -> assertThat(body).contains(id1.toString()))
                .anySatisfy(body -> assertThat(body).contains(id2.toString()));
    }

    @Test
    void doesNothing_whenNoStuckSubmissions() {
        when(submissionRepository.findStuck(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        job.reconcileStuck();

        verify(submissionRepository, never()).save(any());
        verify(verdictPubSub, never()).publishAfterCommit(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queriesOnlyPendingAndJudgingStatuses() {
        when(submissionRepository.findStuck(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        job.reconcileStuck();

        ArgumentCaptor<Collection<Integer>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(submissionRepository).findStuck(statuses.capture(), any(LocalDateTime.class));
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                SubmissionResult.PENDING.getValue(), SubmissionResult.JUDGING.getValue());
    }
}
