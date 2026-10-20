package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.messaging.event.SubmissionStartedEvent;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.support.PostgresTest;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rejudge resets a finished submission to PENDING and counts it in {@code rejudge_count}; its judge request carries
 * that count, and the worker echoes it on {@code submission.started} and {@code submission.judged}. A start or verdict
 * of an earlier generation (a redelivery, a late retry, a dead-letter replay) must not land on the rejudged row. One
 * without a generation (a request from before the guard, an old worker) is applied as before.
 */
class VerdictGenerationGuardPostgresTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final LocalDateTime AN_HOUR_AGO = LocalDateTime.now().minusHours(1).withNano(0);
    private static final int AC = SubmissionResult.ACCEPTED.getValue();
    private static final int WA = SubmissionResult.WRONG_ANSWER.getValue();
    private static final int PENDING = SubmissionResult.PENDING.getValue();
    private static final int JUDGING = SubmissionResult.JUDGING.getValue();

    @Autowired JudgeResultConsumer judgeResults;
    @Autowired SubmissionStartedConsumer starts;
    @Autowired SubmissionRepository repository;
    @Autowired TransactionOperations transactions;
    @Autowired JdbcTemplate jdbc;

    private UUID insertSubmission(int status, int rejudgeCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", rejudge_count, created_at, updated_at, "
                        + "language_id, problem_id, problem_slug, user_id) VALUES (?, ?, 0, ?, ?, ?, ?, ?, 'gen', ?)",
                id, status, rejudgeCount, AN_HOUR_AGO, AN_HOUR_AGO, C_LANGUAGE_ID, UUID.randomUUID(),
                UUID.randomUUID());
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, result, \"time\", judging_started_at, rejudge_count, updated_at "
                + "FROM t_submissions WHERE id = ?", id);
    }

    private int announcements(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM t_outbox WHERE topic = 'oj.submission.events' "
                + "AND convert_from(payload, 'UTF8') LIKE ?", Integer.class, "%" + id + "%");
    }

    private static SubmissionJudgedEvent verdict(UUID id, int status, Integer generation) {
        return SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(status).result(status)
                .cpuTime(12).realTime(15).memory(3072L).details(List.of()).rejudgeCount(generation).build();
    }

    private static SubmissionStartedEvent started(UUID id, Instant at, Integer generation) {
        return new SubmissionStartedEvent(id.toString(), "w1", at, generation);
    }

    private int applyVerdictOfGeneration(UUID id, int status, int generation) {
        return transactions.execute(tx -> repository.applyVerdictOfGeneration(id, status, status, 12, 15, 3072L,
                null, List.of(), LocalDateTime.now(), generation));
    }

    private int applyStartedOfGeneration(UUID id, LocalDateTime startedAt, int generation) {
        return transactions.execute(tx ->
                repository.applyStartedOfGeneration(id, startedAt, LocalDateTime.now(), generation));
    }

    @Test
    void aVerdictOfAnEarlierGenerationIsIgnored() {
        UUID id = insertSubmission(JUDGING, 1);
        Map<String, Object> before = row(id);

        assertThat(applyVerdictOfGeneration(id, AC, 0)).isZero();
        judgeResults.onJudged(verdict(id, AC, 0));

        assertThat(row(id)).isEqualTo(before);
        assertThat(announcements(id)).as("no statistics event for an ignored verdict").isZero();

        judgeResults.onJudged(verdict(id, WA, 1));

        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo(WA);
        assertThat(after.get("time")).isEqualTo(15);
        assertThat(announcements(id)).isEqualTo(1);
    }

    @Test
    void aStartOfAnEarlierGenerationIsIgnored() {
        UUID id = insertSubmission(PENDING, 1);
        Map<String, Object> before = row(id);
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(5).withNano(0);

        assertThat(applyStartedOfGeneration(id, startedAt, 0)).isZero();
        assertThat(row(id)).isEqualTo(before);

        assertThat(applyStartedOfGeneration(id, startedAt, 1)).isEqualTo(1);
        Map<String, Object> after = row(id);
        assertThat(after.get("status")).isEqualTo(JUDGING);
        assertThat(((Timestamp) after.get("judging_started_at")).toLocalDateTime()).isEqualTo(startedAt);
    }

    @Test
    void aVerdictWithoutGenerationIsStillApplied() {
        // Rollout: requests queued before the guard, and an old worker's verdicts, carry no rejudgeCount.
        UUID id = insertSubmission(JUDGING, 1);

        judgeResults.onJudged(verdict(id, AC, null));

        assertThat(row(id).get("status")).isEqualTo(AC);
        assertThat(announcements(id)).isEqualTo(1);
    }

    @Test
    void aStartWithoutGenerationIsStillApplied() {
        UUID id = insertSubmission(PENDING, 1);

        starts.onStarted(started(id, Instant.now().minusSeconds(5), null));

        assertThat(row(id).get("status")).isEqualTo(JUDGING);
    }

    @Test
    void aStaleStartAndVerdictAfterARejudgeResetAreIgnored() {
        // Judged once (generation 0), then rejudged: the row is PENDING at generation 1.
        UUID id = insertSubmission(AC, 0);
        Integer resets = transactions.execute(tx -> repository.resetForRejudge(id, LocalDateTime.now()));
        assertThat(resets).isEqualTo(1);
        Map<String, Object> reset = row(id);
        assertThat(reset.get("rejudge_count")).isEqualTo(1);

        // The first judging's late retry start, and its verdict replayed from submission.judged.dlq.
        starts.onStarted(started(id, Instant.now().minusSeconds(1), 0));
        judgeResults.onJudged(verdict(id, WA, 0));

        assertThat(row(id)).as("still waiting for the rejudge").isEqualTo(reset);
        assertThat(announcements(id)).isZero();

        Instant rejudgeStart = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        starts.onStarted(started(id, rejudgeStart, 1));
        assertThat(row(id).get("status")).isEqualTo(JUDGING);
        assertThat(((Timestamp) row(id).get("judging_started_at")).toLocalDateTime())
                .isEqualTo(LocalDateTime.ofInstant(rejudgeStart, ZoneId.systemDefault()));

        judgeResults.onJudged(verdict(id, AC, 1));
        assertThat(row(id).get("status")).isEqualTo(AC);
        assertThat(announcements(id)).isEqualTo(1);
    }
}
