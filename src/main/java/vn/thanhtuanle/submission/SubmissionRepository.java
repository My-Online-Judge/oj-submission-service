package vn.thanhtuanle.submission;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.thanhtuanle.entity.Submission;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    /**
     * Non-terminal submissions (PENDING/JUDGING) whose judging should have finished: created before the
     * threshold and — when their judge request went through the outbox — sent before it too. A request
     * still waiting in the outbox (Kafka down) has not started judging, and one sent after the threshold
     * has had too little time; flipping either to SYSTEM_ERROR would discard the real verdict that follows.
     * Submissions without an outbox row (sent before 2a, or the row cleaned up) count from creation.
     */
    @Query("""
            SELECT s FROM Submission s
            WHERE s.status IN :statuses AND s.createdAt < :threshold
              AND NOT EXISTS (SELECT 1 FROM OutboxMessage o
                              WHERE o.topic = 'submission.requested'
                                AND o.messageKey = CAST(s.id AS String)
                                AND (o.publishedAt IS NULL OR o.publishedAt >= :threshold))
            """)
    List<Submission> findStuck(@Param("statuses") Collection<Integer> statuses,
                               @Param("threshold") LocalDateTime threshold);

    /** Count submissions currently in one of the given statuses — used by the oj.queue.depth gauge. */
    long countByStatusIn(Collection<Integer> statuses);

    /**
     * Current committed status of a submission, read fresh from the database.
     *
     * This MUST stay a scalar projection — do NOT "simplify" it to findById or any
     * entity-returning query. A JPQL scalar select always hits the database and returns the
     * committed column value directly, bypassing the persistence context. An entity-returning
     * query would NOT be fresh: Hibernate merges each query row with the already-managed
     * instance, so the stale first-level-cache copy wins and the re-read is a no-op.
     * Returns null when the row no longer exists.
     */
    @Query("select s.status from Submission s where s.id = :id")
    Integer findStatusById(@Param("id") UUID id);
    @Query("SELECT s FROM Submission s WHERE s.problemSlug = :slug ORDER BY s.createdAt DESC")
    Page<Submission> findByProblemSlugOrderByCreatedAtDesc(@Param("slug") String slug, Pageable pageable);

    @Query("""
                SELECT s
                FROM Submission s
                WHERE s.userId = :userId
                ORDER BY s.createdAt DESC
            """)
    Page<Submission> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") UUID userId,
            Pageable pageable);


    @Query("""
                SELECT s
                FROM Submission s
                WHERE s.userId = :userId AND s.problemSlug = :problemSlug
                ORDER BY s.createdAt DESC
            """)
    Page<Submission> findByUserIdAndProblemSlugOrderByCreatedAtDesc(
            @Param("userId") UUID userId,
            @Param("problemSlug") String problemSlug,
            Pageable pageable
    );

    /** The submission with its language loaded, for mapping outside a transaction (VerdictPush). */
    @EntityGraph(attributePaths = "language")
    Optional<Submission> findWithLanguageById(UUID id);
}
