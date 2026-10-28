package vn.thanhtuanle.messaging.outbox;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
 * <p>A pass sends outside a database transaction and records each row's outcome on its own (see {@link #relayBatch}).
 * Two instances are kept apart by a lease on each claimed row, taken with {@code SKIP LOCKED}.
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
    // One thread: dispatched passes of this instance never overlap. Other instances (and the poll) are kept apart by the
    // lease on each claimed row.
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
     * Claims up to one batch of the oldest sendable rows (a short transaction, then a lease), sends them oldest first
     * <em>outside</em> any database transaction, and records each outcome in a statement of its own. A transaction held
     * open across the sends would keep every row lock for as long as Kafka takes (15 s per row).
     * <ul>
     *   <li>Sent: marked published.</li>
     *   <li>Refused for good ({@link OutboxFailures#isPermanent}): counted, kept away for a backoff, and dead after
     *       {@code maxAttempts}; the rows behind it are still sent.</li>
     *   <li>Failed in a way that may heal (Kafka down): this row and every one not yet tried are given back at once,
     *       uncounted, and the pass stops; the next pass retries them in order.</li>
     *   <li>Too late in the lease to start another send (a slow broker): the rows not yet tried are given back and the
     *       pass stops, so no row is still being sent when another instance may claim it.</li>
     * </ul>
     *
     * @return how many rows were sent
     */
    int relayBatch() {
        LocalDateTime claimedAt = LocalDateTime.now();
        LocalDateTime leaseUntil = claimedAt.plusSeconds(props.getLeaseSeconds());
        // No send starts after this: it could still be running when the lease ends and another instance claims the row.
        LocalDateTime lastSendStart = leaseUntil.minusSeconds(props.getSendTimeoutSeconds());
        List<OutboxMessage> claimed = transaction.execute(status -> repository.claim(
                props.getBatchSize(), claimedAt, leaseUntil));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        // RETURNING gives no order, and the oldest row goes first.
        List<OutboxMessage> inOrder = new ArrayList<>(claimed);
        inOrder.sort(Comparator.comparing(OutboxMessage::getCreatedAt));
        int sent = 0;
        for (int i = 0; i < inOrder.size(); i++) {
            OutboxMessage message = inOrder.get(i);
            if (LocalDateTime.now().isAfter(lastSendStart)) {
                List<UUID> untried = inOrder.subList(i, inOrder.size()).stream().map(OutboxMessage::getId).toList();
                transaction.executeWithoutResult(status -> repository.releaseClaims(untried));
                break;
            }
            try {
                sender.send(message);
            } catch (Exception e) {
                if (OutboxFailures.isPermanent(e)) {
                    refuse(message, e);
                    continue;
                }
                log.warn("Outbox message {} to {} not sent yet: {}", message.getId(), message.getTopic(), e.getMessage());
                List<UUID> untried = inOrder.subList(i, inOrder.size()).stream().map(OutboxMessage::getId).toList();
                transaction.executeWithoutResult(status -> repository.releaseClaims(untried));
                break;
            }
            transaction.executeWithoutResult(status -> repository.markPublished(message.getId(), LocalDateTime.now()));
            sent++;
        }
        return sent;
    }

    private void refuse(OutboxMessage message, Exception failure) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryAt = now.plusSeconds((long) props.getRetryBackoffSeconds() * (message.getAttempts() + 1));
        transaction.executeWithoutResult(status ->
                repository.markRefused(message.getId(), props.getMaxAttempts(), now, retryAt));
        boolean dead = message.getAttempts() + 1 >= props.getMaxAttempts();
        log.error("Outbox message {} to {} refused for good ({} of {}){}: {}", message.getId(), message.getTopic(),
                message.getAttempts() + 1, props.getMaxAttempts(), dead ? "; given up on it" : "", failure.toString());
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
