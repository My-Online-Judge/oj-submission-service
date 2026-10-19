package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.support.PostgresTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict is one conditional UPDATE on real Postgres: it lands only over PENDING/JUDGING, so the first verdict wins
 * and is announced exactly once, a verdict after reconcile gave up is dropped, and the details are stored as JSON.
 * Each {@code onJudged} call goes through the transactional proxy and commits, as a Kafka delivery would.
 */
class JudgeResultConsumerPostgresTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final LocalDateTime AN_HOUR_AGO = LocalDateTime.now().minusHours(1).withNano(0);

    @Autowired JudgeResultConsumer consumer;
    @Autowired SubmissionRepository submissionRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID insertSubmission(int status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", created_at, updated_at, language_id, problem_id, "
                        + "problem_slug, user_id) VALUES (?, ?, 0, ?, ?, ?, ?, 'verdict', ?)",
                id, status, AN_HOUR_AGO, AN_HOUR_AGO, C_LANGUAGE_ID, UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, result, cpu_time, \"time\", memory, error_message "
                + "FROM t_submissions WHERE id = ?", id);
    }

    private LocalDateTime updatedAt(UUID id) {
        return jdbc.queryForObject("SELECT updated_at FROM t_submissions WHERE id = ?", LocalDateTime.class, id);
    }

    private int announcements(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM t_outbox WHERE topic = 'oj.submission.events' "
                + "AND convert_from(payload, 'UTF8') LIKE ?", Integer.class, "%" + id + "%");
    }

    private static SubmissionJudgedEvent verdict(UUID id, int status, int time, List<JudgeResultDto> details) {
        return SubmissionJudgedEvent.builder()
                .submissionId(id.toString()).status(status).result(status)
                .cpuTime(time - 3).realTime(time).memory(3072L).details(details).build();
    }

    @Test
    void theFirstVerdictWinsAndASecondIsDropped() {
        UUID id = insertSubmission(SubmissionResult.JUDGING.getValue());

        consumer.onJudged(verdict(id, SubmissionResult.ACCEPTED.getValue(), 15, List.of()));
        consumer.onJudged(verdict(id, SubmissionResult.WRONG_ANSWER.getValue(), 99, List.of()));

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(SubmissionResult.ACCEPTED.getValue());
        assertThat(row.get("time")).isEqualTo(15);
        assertThat(row.get("cpu_time")).isEqualTo(12);
        assertThat(row.get("memory")).isEqualTo(3072L);
        assertThat(updatedAt(id)).isAfter(AN_HOUR_AGO);
        assertThat(announcements(id)).as("one oj.submission.events row for the one verdict").isEqualTo(1);
    }

    @Test
    void aVerdictAfterReconcileFailedTheSubmissionIsDropped() {
        UUID id = insertSubmission(SubmissionResult.JUDGING.getValue());
        jdbc.update("UPDATE t_submissions SET status = 5, error_message = 'Judging timed out' WHERE id = ?", id);

        consumer.onJudged(verdict(id, SubmissionResult.ACCEPTED.getValue(), 15, List.of()));

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(SubmissionResult.SYSTEM_ERROR.getValue());
        assertThat(row.get("error_message")).isEqualTo("Judging timed out");
        assertThat(row.get("time")).isEqualTo(0);
        assertThat(updatedAt(id)).isEqualTo(AN_HOUR_AGO);
        assertThat(announcements(id)).isZero();
    }

    @Test
    void detailsAreStoredAsJson() {
        UUID id = insertSubmission(SubmissionResult.PENDING.getValue());
        List<JudgeResultDto> details = List.of(
                JudgeResultDto.builder().testCase("1").result(0).cpuTime(12).realTime(15).memory(3072L)
                        .signal(0).exitCode(0).error(0).outputMd5("d41d8cd98f00b204e9800998ecf8427e").build(),
                JudgeResultDto.builder().testCase("2").result(-1).cpuTime(9).realTime(11).memory(3000L).build());

        consumer.onJudged(verdict(id, SubmissionResult.WRONG_ANSWER.getValue(), 15, details));

        assertThat(submissionRepository.findById(id).orElseThrow().getDetails()).isEqualTo(details);
        // A JSON array in the jsonb column, not a JSON string holding the serialized list.
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(details) FROM t_submissions WHERE id = ?",
                String.class, id)).isEqualTo("array");
        assertThat(jdbc.queryForObject("SELECT details -> 1 ->> 'test_case' FROM t_submissions WHERE id = ?",
                String.class, id)).isEqualTo("2");
    }
}
