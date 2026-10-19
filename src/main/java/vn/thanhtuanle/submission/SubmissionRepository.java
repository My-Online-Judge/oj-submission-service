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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.dto.JudgeResultDto;

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

    /**
     * A worker began judging: a PENDING or JUDGING submission becomes JUDGING with that start time (a second start, a
     * worker's retry or a redelivery, replaces it). A finished or unknown submission is left alone: 0 rows.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Submission s SET s.status = 7, s.judgingStartedAt = :startedAt, s.updatedAt = :now "
            + "WHERE s.id = :id AND s.status IN (6, 7)")
    int applyStarted(@Param("id") UUID id, @Param("startedAt") LocalDateTime startedAt,
                     @Param("now") LocalDateTime now);

    /**
     * Records a verdict over a PENDING or JUDGING submission only. A finished submission (a duplicate or late verdict,
     * or one reconcile already failed) and an unknown id are left alone: 0 rows. The persistence context is flushed
     * before and cleared after, so a following read sees the written row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Submission s
            SET s.status = :status, s.result = :result, s.cpuTime = :cpuTime, s.time = :time, s.memory = :memory,
                s.errorMessage = :errorMessage, s.details = :details, s.updatedAt = :now
            WHERE s.id = :id AND s.status IN (6, 7)
            """)
    int applyVerdict(@Param("id") UUID id, @Param("status") int status, @Param("result") Integer result,
                     @Param("cpuTime") Integer cpuTime, @Param("time") int time, @Param("memory") Long memory,
                     @Param("errorMessage") String errorMessage, @Param("details") List<JudgeResultDto> details,
                     @Param("now") LocalDateTime now);

    /** The submission with its language loaded, for mapping outside a transaction (VerdictPush). */
    @EntityGraph(attributePaths = "language")
    Optional<Submission> findWithLanguageById(UUID id);
}
