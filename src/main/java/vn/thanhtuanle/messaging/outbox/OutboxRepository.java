package vn.thanhtuanle.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Claims up to {@code limit} of the oldest rows that are neither sent, dead, nor owned by a live instance, in one
     * statement: the row locks last only as long as this transaction, the lease ({@code leaseUntil}) keeps the claim
     * after it. {@code SKIP LOCKED} makes two instances claiming at the same moment take disjoint rows. The rows come
     * back in no particular order: the relay sorts them by {@code created_at}.
     */
    @Query(value = """
            UPDATE t_outbox SET claimed_until = :leaseUntil
            WHERE id IN (SELECT id FROM t_outbox
                         WHERE published_at IS NULL AND dead_at IS NULL
                           AND (claimed_until IS NULL OR claimed_until < :now)
                         ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED)
            RETURNING *
            """, nativeQuery = true)
    List<OutboxMessage> claim(@Param("limit") int limit, @Param("now") LocalDateTime now,
                              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** The broker acknowledged the row. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxMessage o SET o.publishedAt = :at, o.claimedUntil = null WHERE o.id = :id")
    int markPublished(@Param("id") UUID id, @Param("at") LocalDateTime at);

    /** The send failed in a way that may heal (Kafka down, a timeout): give the rows back at once, uncounted. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OutboxMessage o SET o.claimedUntil = null WHERE o.id IN :ids")
    int releaseClaims(@Param("ids") List<UUID> ids);

    /**
     * The broker refused the row for good: count it, keep it away from the relay until {@code retryAt}, and kill it when
     * this was the {@code maxAttempts}-th refusal.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxMessage o
            SET o.attempts = o.attempts + 1, o.claimedUntil = :retryAt,
                o.deadAt = CASE WHEN o.attempts + 1 >= :maxAttempts THEN :now ELSE o.deadAt END
            WHERE o.id = :id
            """)
    int markRefused(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now,
                    @Param("retryAt") LocalDateTime retryAt);

    /** Rows still to be sent: not sent and not dead. */
    @Query("SELECT count(o) FROM OutboxMessage o WHERE o.publishedAt IS NULL AND o.deadAt IS NULL")
    long countPending();

    /** Rows given up on: they need a person (the alert OutboxDeadRow). */
    @Query("SELECT count(o) FROM OutboxMessage o WHERE o.deadAt IS NOT NULL")
    long countDead();

    @Query("SELECT min(o.createdAt) FROM OutboxMessage o WHERE o.publishedAt IS NULL AND o.deadAt IS NULL")
    LocalDateTime oldestUnpublishedCreatedAt();

    @Modifying
    @Query("DELETE FROM OutboxMessage o WHERE o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") LocalDateTime before);
}
