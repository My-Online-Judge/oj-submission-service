package vn.thanhtuanle.submission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.messaging.VerdictEvents;
import vn.thanhtuanle.messaging.VerdictPubSub;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.event.OjTopics;
import vn.thanhtuanle.submission.SubmissionTimeouts.TimedOut;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * Safety net for lost work: a submission whose verdict will never come is failed with SYSTEM_ERROR. Only two kinds of
 * row qualify ({@link SubmissionTimeouts}):
 * <ul>
 *   <li>JUDGING whose worker started more than {@code judge.judging-timeout} (3 min) ago: the worker, its sandbox or
 *       the verdict died ({@value SubmissionTimeouts#JUDGING_TIMED_OUT});</li>
 *   <li>PENDING whose judge request left the outbox more than {@code judge.pending-timeout} (30 min) ago, or without
 *       a request row was created that long ago: the request was lost ({@value SubmissionTimeouts#REQUEST_LOST}).
 *       A request still waiting in the outbox, or a queue that is merely long, is not lost work.</li>
 * </ul>
 * Each rule runs in its own transaction; every failed row gets one {@code SubmissionVerdictRecorded} in the outbox and
 * one SSE push after the commit. A late verdict for a failed row is dropped by the verdict's conditional write
 * ({@link SubmissionRepository#applyVerdict}, PENDING/JUDGING only), so the timeout stands.
 */
@Component
@Slf4j
public class SubmissionReconcileJob {

    private final SubmissionTimeouts timeouts;
    private final OutboxWriter outboxWriter;
    private final VerdictPubSub verdictPubSub;
    private final TransactionOperations transaction;
    private final Duration judgingTimeout;
    private final Duration pendingTimeout;

    public SubmissionReconcileJob(SubmissionTimeouts timeouts, OutboxWriter outboxWriter, VerdictPubSub verdictPubSub,
                                  TransactionOperations transaction,
                                  @Value("${judge.judging-timeout:3m}") Duration judgingTimeout,
                                  @Value("${judge.pending-timeout:30m}") Duration pendingTimeout) {
        this.timeouts = timeouts;
        this.outboxWriter = outboxWriter;
        this.verdictPubSub = verdictPubSub;
        this.transaction = transaction;
        this.judgingTimeout = judgingTimeout;
        this.pendingTimeout = pendingTimeout;
    }

    @Scheduled(fixedDelayString = "${judge.reconcile-interval-ms:60000}",
            initialDelayString = "${judge.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        // Naive, in the JVM's zone like created_at and judging_started_at (pinned in SubmissionServiceApplication).
        LocalDateTime now = LocalDateTime.now();
        failAndAnnounce(SubmissionTimeouts.JUDGING_TIMED_OUT,
                () -> timeouts.failJudgingStartedBefore(now.minus(judgingTimeout), now));
        failAndAnnounce(SubmissionTimeouts.REQUEST_LOST,
                () -> timeouts.failPendingCreatedBefore(now.minus(pendingTimeout), now));
    }

    private void failAndAnnounce(String reason, Supplier<List<TimedOut>> failRule) {
        List<TimedOut> timedOut = transaction.execute(tx -> {
            List<TimedOut> failed = failRule.get();
            for (TimedOut submission : failed) {
                // Same transaction as the status change: announced if and only if it was recorded.
                outboxWriter.append(OjTopics.SUBMISSION_EVENTS, submission.problemId().toString(),
                        VerdictEvents.recorded(submission, SubmissionResult.SYSTEM_ERROR.getValue()).toEnvelope());
                // Deferred until the commit: an earlier push could reach a subscriber that still reads the old row.
                verdictPubSub.publishAfterCommit(submission.id().toString());
            }
            return failed;
        });
        if (timedOut != null) {
            timedOut.forEach(submission -> log.warn("Reconciled submission {} -> SYSTEM_ERROR ({})",
                    submission.id(), reason));
        }
    }
}
