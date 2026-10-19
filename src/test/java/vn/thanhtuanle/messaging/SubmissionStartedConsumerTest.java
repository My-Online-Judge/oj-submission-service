package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.thanhtuanle.messaging.event.SubmissionStartedEvent;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A worker's {@code submission.started} moves a PENDING or JUDGING row to JUDGING and records when judging began, in
 * the service's own (naive) time, the zone {@code created_at} and {@code LocalDateTime.now()} use.
 */
class SubmissionStartedConsumerTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final LocalDateTime AN_HOUR_AGO = LocalDateTime.now().minusHours(1).withNano(0);

    @Autowired SubmissionStartedConsumer consumer;
    @Autowired JdbcTemplate jdbc;

    private UUID insertSubmission(int status, LocalDateTime judgingStartedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", created_at, updated_at, language_id, problem_id, "
                        + "problem_slug, user_id, judging_started_at) VALUES (?, ?, 0, ?, ?, ?, ?, 'started', ?, ?)",
                id, status, AN_HOUR_AGO, AN_HOUR_AGO, C_LANGUAGE_ID, UUID.randomUUID(), UUID.randomUUID(),
                judgingStartedAt);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, judging_started_at, judging_started_at::text AS stored "
                + "FROM t_submissions WHERE id = ?", id);
    }

    private static SubmissionStartedEvent started(UUID id, Instant at) {
        return new SubmissionStartedEvent(id.toString(), "w1", at);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    @Test
    void aPendingSubmissionBecomesJudgingWithTheWorkersStartTime() {
        UUID id = insertSubmission(6, null);
        Instant startedAt = Instant.parse("2026-10-19T05:00:00.123Z");

        consumer.onStarted(started(id, startedAt));

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT judging_started_at FROM t_submissions WHERE id = ?",
                LocalDateTime.class, id)).isEqualTo(local(startedAt));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM t_submissions WHERE id = ?",
                LocalDateTime.class, id)).isAfter(AN_HOUR_AGO);
    }

    @Test
    void aSecondStartRefreshesTheStartTime() {
        Instant first = Instant.parse("2026-10-19T05:00:00.123Z");
        Instant second = Instant.parse("2026-10-19T05:02:30.456Z");
        UUID id = insertSubmission(7, local(first));

        consumer.onStarted(started(id, second));

        assertThat(row(id).get("status")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT judging_started_at FROM t_submissions WHERE id = ?",
                LocalDateTime.class, id)).isEqualTo(local(second));
    }

    @Test
    void aStartForAFinishedSubmissionChangesNothing() {
        UUID id = insertSubmission(0, null);

        consumer.onStarted(started(id, Instant.parse("2026-10-19T05:00:00.123Z")));

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(0);
        assertThat(row.get("judging_started_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM t_submissions WHERE id = ?",
                LocalDateTime.class, id)).isEqualTo(AN_HOUR_AGO);
    }

    @Test
    void anUnknownIdOrAMissingFieldIsSkippedWithoutThrowing() {
        UUID id = insertSubmission(6, null);
        Instant at = Instant.parse("2026-10-19T05:00:00.123Z");

        assertThatCode(() -> consumer.onStarted(null)).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onStarted(started(UUID.randomUUID(), at))).doesNotThrowAnyException();
        assertThatCode(() -> consumer.onStarted(new SubmissionStartedEvent("not-a-uuid", "w1", at)))
                .doesNotThrowAnyException();
        assertThatCode(() -> consumer.onStarted(new SubmissionStartedEvent(null, "w1", at)))
                .doesNotThrowAnyException();
        assertThatCode(() -> consumer.onStarted(new SubmissionStartedEvent(id.toString(), "w1", null)))
                .doesNotThrowAnyException();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).as("a start without a time leaves the row alone").isEqualTo(6);
        assertThat(row.get("judging_started_at")).isNull();
    }

    @Test
    void theStartTimeIsStoredInTheServiceZone() {
        TimeZone before = TimeZone.getDefault();
        try {
            // What SubmissionServiceApplication.main pins; the worker's instant is UTC.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
            UUID id = insertSubmission(6, null);

            consumer.onStarted(started(id, Instant.parse("2026-10-19T05:00:00.123Z")));

            // Read as text: the stored wall-clock value itself, with no JDBC time-zone handling in the way.
            assertThat(row(id).get("stored")).isEqualTo("2026-10-19 12:00:00.123");
            assertThat(jdbc.queryForObject("SELECT judging_started_at FROM t_submissions WHERE id = ?",
                    LocalDateTime.class, id)).isEqualTo(LocalDateTime.parse("2026-10-19T12:00:00.123"));
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
