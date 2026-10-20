package vn.thanhtuanle.messaging.outbox;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves committed outbox rows to Kafka.
 *
 * <p>Normally a row is sent right after its transaction commits ({@link #relaySoon}), on this relay's
 * own thread so the caller never waits for Kafka. The OpenTelemetry agent carries the caller's trace
 * context onto that thread, so the record continues the request's trace. Rows a dispatch could not send
 * — Kafka down, or a crash between the commit and the send — are picked up by {@link #poll}.
 *
 * <p>At most one dispatched pass waits at a time: a problem rejudge calls {@link #relaySoon} once per queued
 * submission, and one pass sends them all.
 */
@Component
@Slf4j
public class OutboxRelay {

    private final OutboxRepository repository;
    private final OutboxSender sender;
    private final TransactionTemplate transaction;
    private final OutboxProperties props;
    // One thread: passes of this instance never overlap. Other instances are kept apart by SKIP LOCKED.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "outbox-relay");
        t.setDaemon(true);
        return t;
    });
    /** A dispatched pass is waiting on the relay thread and has not started yet. */
    private final AtomicBoolean queued = new AtomicBoolean();

    public OutboxRelay(OutboxRepository repository, OutboxSender sender,
            PlatformTransactionManager transactionManager, OutboxProperties props) {
        this.repository = repository;
        this.sender = sender;
        this.transaction = new TransactionTemplate(transactionManager);
        this.props = props;
    }

    /**
     * Called after a transaction that appended rows has committed. Returns at once. Queues a pass unless one is
     * already waiting: that pass has not started, so it will see these rows too.
     */
    public void relaySoon() {
        if (props.isRelayEnabled() && queued.compareAndSet(false, true)) {
            executor.execute(this::dispatchedPass);
        }
    }

    private void dispatchedPass() {
        // Cleared before the pass reads anything: rows committed while it runs get one more pass.
        queued.set(false);
        relayPending();
    }

    /** Safety net for rows the after-commit dispatch could not send. */
    @Scheduled(fixedDelayString = "${oj.outbox.poll-interval-ms:10000}")
    public void poll() {
        if (props.isRelayEnabled()) {
            relayPending();
        }
    }

    void relayPending() {
        try {
            while (relayBatch() == props.getBatchSize()) {
                // a full batch: there may be more
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay pass failed, the next one retries: {}", e.getMessage());
        }
    }

    /**
     * Sends up to one batch of the oldest unpublished rows and marks each sent row published, all in one
     * transaction. Stops at the first failure: that row and the later ones wait for the next pass.
     *
     * @return how many rows were sent
     */
    int relayBatch() {
        Integer sent = transaction.execute(status -> {
            int count = 0;
            for (OutboxMessage message : repository.lockOldestUnpublished(props.getBatchSize())) {
                try {
                    sender.send(message);
                } catch (Exception e) {
                    log.warn("Outbox message {} to {} not sent yet: {}", message.getId(), message.getTopic(), e.getMessage());
                    break;
                }
                message.setPublishedAt(LocalDateTime.now());
                count++;
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    /** Published rows are only kept for a while, for debugging. */
    @Scheduled(cron = "${oj.outbox.cleanup-cron:0 30 4 * * *}")
    public void deletePublished() {
        Integer deleted = transaction.execute(status ->
                repository.deletePublishedBefore(LocalDateTime.now().minusDays(props.getRetentionDays())));
        log.info("Outbox clean-up deleted {} published row(s)", deleted);
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }
}
