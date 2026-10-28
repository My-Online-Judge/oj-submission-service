package vn.thanhtuanle.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.thanhtuanle.messaging.KafkaTopics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The reconcile job's two rules, each one conditional {@code UPDATE … RETURNING} (Postgres only) that fails the
 * submissions whose work was lost and returns them. A row is returned by the pass that changed it and by no other: a
 * concurrent pass blocks on the row, re-checks the status after the first commits, and skips it.
 *
 * <p>Runs on {@link NamedParameterJdbcTemplate}, so it joins the caller's JPA transaction. {@code threshold} and
 * {@code now} are naive times in the JVM's zone, like {@code created_at}, {@code judging_started_at} and
 * {@code updated_at}; the database's own {@code now()} would be in another zone.
 */
@Repository
@RequiredArgsConstructor
public class SubmissionTimeouts {

    public static final String JUDGING_TIMED_OUT = "Judging timed out";
    public static final String REQUEST_LOST = "Judging request lost";

    /**
     * A submission one of the rules failed; {@code rejudge} when it had been rejudged. It carries what its verdict
     * event announces: the submitter, the contest (null for practice) and when it was submitted.
     */
    public record TimedOut(UUID id, UUID problemId, boolean rejudge, UUID userId, UUID contestId,
                           LocalDateTime createdAt) {}

    private static final RowMapper<TimedOut> TIMED_OUT = (rs, rowNum) -> new TimedOut(
            rs.getObject("id", UUID.class), rs.getObject("problem_id", UUID.class), rs.getBoolean("rejudge"),
            rs.getObject("user_id", UUID.class), rs.getObject("contest_id", UUID.class),
            rs.getObject("created_at", LocalDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Fails each JUDGING submission whose worker started before {@code threshold}. A JUDGING row without a start
     * time (none is written today) counts from its last update, so it cannot hang forever.
     */
    public List<TimedOut> failJudgingStartedBefore(LocalDateTime threshold, LocalDateTime now) {
        return jdbc.query("""
                UPDATE t_submissions SET status = 5, error_message = :message, updated_at = :now
                WHERE status = 7 AND COALESCE(judging_started_at, updated_at) < :threshold
                RETURNING id, problem_id, rejudge_count > 0 AS rejudge, user_id, contest_id, created_at
                """, params(JUDGING_TIMED_OUT, threshold, now), TIMED_OUT);
    }

    /**
     * Fails each PENDING submission whose judge request left the outbox before {@code threshold}. A request still in
     * the outbox (Kafka down) has not reached a worker, and one sent after the threshold (a rejudge of an old
     * submission, say) has had too little time. A submission without a request row (sent before the outbox existed,
     * or its row cleaned up) counts from its creation.
     *
     * <p>Nothing fails while the queue is being worked through: a worker that announced a start between
     * {@code progressSince} and {@code now} is draining it, and a long queue (the admission cap is hundreds, a worker
     * judges one at a time) is waiting, not lost. Only when no worker has started anything in that window is a
     * PENDING row past the threshold taken as a lost request. Starts dated after {@code now} are not progress.
     */
    public List<TimedOut> failPendingCreatedBefore(LocalDateTime threshold, LocalDateTime now,
                                                   LocalDateTime progressSince) {
        return jdbc.query("""
                UPDATE t_submissions s SET status = 5, error_message = :message, updated_at = :now
                WHERE s.status = 6 AND s.created_at < :threshold
                  AND NOT EXISTS (SELECT 1 FROM t_outbox o
                                  WHERE o.topic = :requestTopic AND o.message_key = s.id::text
                                    AND (o.published_at IS NULL OR o.published_at >= :threshold))
                  AND NOT EXISTS (SELECT 1 FROM t_submissions p
                                  WHERE p.judging_started_at >= :progressSince AND p.judging_started_at <= :now)
                RETURNING s.id, s.problem_id, s.rejudge_count > 0 AS rejudge, s.user_id, s.contest_id, s.created_at
                """, params(REQUEST_LOST, threshold, now).addValue("requestTopic", KafkaTopics.SUBMISSION_REQUESTED)
                        .addValue("progressSince", progressSince),
                TIMED_OUT);
    }

    private static MapSqlParameterSource params(String message, LocalDateTime threshold, LocalDateTime now) {
        return new MapSqlParameterSource()
                .addValue("message", message)
                .addValue("threshold", threshold)
                .addValue("now", now);
    }
}
