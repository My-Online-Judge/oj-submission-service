package vn.thanhtuanle.messaging;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a publish only AFTER the caller's transaction commits, or immediately when no transaction synchronization is
 * active (direct calls, unit tests). Publishing mid-transaction opens a lost-event window: a subscriber's fresh re-read
 * ({@code SubmissionService.streamVerdict}) can still see the row's old state while the live publish has already
 * passed its emitter by. Post-commit, a subscriber either registered before the publish (receives it live) or re-reads
 * after the commit (sees the new row). Every publish that follows a state write goes through here:
 * {@link VerdictPubSub#publishAfterCommit} and {@link JudgingPubSub#publishStartedAfterCommit}.
 */
final class AfterCommit {
    private AfterCommit() {}

    static void run(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
