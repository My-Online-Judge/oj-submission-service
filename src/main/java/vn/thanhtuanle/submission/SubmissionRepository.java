package vn.thanhtuanle.submission;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.dto.JudgeResultDto;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, UUID>, JpaSpecificationExecutor<Submission> {

    /** The admin search: a page with the languages loaded in the same query (no N+1). */
    @Override
    @EntityGraph(attributePaths = "language")
    Page<Submission> findAll(Specification<Submission> spec, Pageable pageable);


    /** Count submissions currently in one of the given statuses — OjMetrics' queue-depth refresh (gauge, admission). */
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
     * A worker began judging: a PENDING or JUDGING submission becomes JUDGING with that start time (a later start, a
     * worker's retry, replaces it). A finished or unknown submission is left alone, and so is one that already holds
     * a later start (a redelivered older event must not pull the start back and time the row out early): 0 rows.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Submission s SET s.status = 7, s.judgingStartedAt = :startedAt, s.updatedAt = :now "
            + "WHERE s.id = :id AND s.status IN (6, 7) "
            + "AND (s.judgingStartedAt IS NULL OR s.judgingStartedAt < :startedAt)")
    int applyStarted(@Param("id") UUID id, @Param("startedAt") LocalDateTime startedAt,
                     @Param("now") LocalDateTime now);

    /**
     * {@link #applyStarted} of the generation {@code gen} only: a start of an earlier judging (the row was rejudged
     * since) matches no row. A separate query rather than an optional parameter: Postgres cannot type a null
     * parameter in {@code :gen IS NULL OR ...}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Submission s SET s.status = 7, s.judgingStartedAt = :startedAt, s.updatedAt = :now "
            + "WHERE s.id = :id AND s.status IN (6, 7) "
            + "AND (s.judgingStartedAt IS NULL OR s.judgingStartedAt < :startedAt) "
            + "AND s.rejudgeCount = :gen")
    int applyStartedOfGeneration(@Param("id") UUID id, @Param("startedAt") LocalDateTime startedAt,
                                 @Param("now") LocalDateTime now, @Param("gen") int gen);

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

    /**
     * Names the worker that produced the verdict {@link #applyVerdict} / {@link #applyVerdictOfGeneration} just stored.
     * Called only after one of them updated the row, in the same transaction, so a dropped late verdict never
     * overwrites who judged the first one.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Submission s SET s.judgedBy = :judgedBy WHERE s.id = :id")
    int recordJudgedBy(@Param("id") UUID id, @Param("judgedBy") String judgedBy);

    /**
     * {@link #applyVerdict} of the generation {@code gen} only: a verdict of an earlier judging (a redelivery, or a
     * dead-letter replay after a rejudge) matches no row. Separate from {@link #applyVerdict} for the same reason as
     * {@link #applyStartedOfGeneration}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Submission s
            SET s.status = :status, s.result = :result, s.cpuTime = :cpuTime, s.time = :time, s.memory = :memory,
                s.errorMessage = :errorMessage, s.details = :details, s.updatedAt = :now
            WHERE s.id = :id AND s.status IN (6, 7) AND s.rejudgeCount = :gen
            """)
    int applyVerdictOfGeneration(@Param("id") UUID id, @Param("status") int status, @Param("result") Integer result,
                                 @Param("cpuTime") Integer cpuTime, @Param("time") int time,
                                 @Param("memory") Long memory, @Param("errorMessage") String errorMessage,
                                 @Param("details") List<JudgeResultDto> details, @Param("now") LocalDateTime now,
                                 @Param("gen") int gen);

    /**
     * Puts a finished submission back to PENDING for a rejudge: the previous verdict, its times and its start are
     * cleared and the rejudge is counted. A PENDING or JUDGING submission (one already queued or rejudged meanwhile)
     * and an unknown id are left alone: 0 rows. Flushed before and cleared after, so a following read sees the row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Submission s
            SET s.status = 6, s.result = null, s.cpuTime = null, s.time = 0, s.memory = 0L, s.errorMessage = null,
                s.details = null, s.judgingStartedAt = null, s.rejudgeCount = s.rejudgeCount + 1, s.updatedAt = :now
            WHERE s.id = :id AND s.status NOT IN (6, 7)
            """)
    int resetForRejudge(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * The submission's current {@code rejudge_count} as this transaction sees it (its own uncommitted reset included), read fresh (a scalar projection, like {@link #findStatusById}).
     * Read right after {@link #resetForRejudge} in the same transaction, it is the generation that reset set.
     */
    @Query("select s.rejudgeCount from Submission s where s.id = :id")
    Integer findRejudgeCountById(@Param("id") UUID id);

    /** The submission with its language loaded, for mapping outside a transaction (VerdictPush). */
    @EntityGraph(attributePaths = "language")
    Optional<Submission> findWithLanguageById(UUID id);

    /** The submissions with their languages loaded (a problem rejudge builds one judge request each). */
    @EntityGraph(attributePaths = "language")
    List<Submission> findWithLanguageByIdIn(Collection<UUID> ids);

    /** A problem's finished submissions, newest first: what a problem rejudge takes, a page at most. */
    @Query("""
            SELECT s.id FROM Submission s
            WHERE s.problemSlug = :slug AND s.status NOT IN (6, 7)
            ORDER BY s.createdAt DESC
            """)
    List<UUID> findRejudgeCandidates(@Param("slug") String slug, Pageable page);

    /**
     * {@link #findRejudgeCandidates} of one verdict. A separate query rather than an optional parameter: Postgres
     * cannot type a null parameter in {@code :status IS NULL OR s.status = :status}.
     */
    @Query("""
            SELECT s.id FROM Submission s
            WHERE s.problemSlug = :slug AND s.status NOT IN (6, 7) AND s.status = :status
            ORDER BY s.createdAt DESC
            """)
    List<UUID> findRejudgeCandidatesWithStatus(@Param("slug") String slug, @Param("status") int status,
                                               Pageable page);

    /**
     * One row for a user's finished submissions (status not 6 or 7): count, accepted count (null when there are none)
     * and distinct problems attempted.
     */
    @Query("""
            SELECT COUNT(s), SUM(CASE WHEN s.status = 0 THEN 1L ELSE 0L END), COUNT(DISTINCT s.problemSlug)
            FROM Submission s
            WHERE s.userId = :userId AND s.status NOT IN (6, 7)
            """)
    List<Object[]> aggregateTerminal(@Param("userId") UUID userId);

    /** The distinct slugs a user got accepted on, ascending. */
    @Query("SELECT DISTINCT s.problemSlug FROM Submission s WHERE s.userId = :userId AND s.status = 0 "
            + "ORDER BY s.problemSlug")
    List<String> findSolvedSlugs(@Param("userId") UUID userId);

    /** A user's newest submissions of any status, with the language loaded (no N+1). */
    @EntityGraph(attributePaths = "language")
    @Query("SELECT s FROM Submission s WHERE s.userId = :userId ORDER BY s.createdAt DESC, s.id DESC")
    List<Submission> findRecent(@Param("userId") UUID userId, Pageable page);
}
