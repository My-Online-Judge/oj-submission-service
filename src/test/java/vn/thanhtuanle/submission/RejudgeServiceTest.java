package vn.thanhtuanle.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemNotFoundException;
import vn.thanhtuanle.support.PostgresTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rejudging one finished submission on real Postgres: one conditional reset (status NOT IN (6, 7)) and one judge
 * request in the outbox, built from the problem's current judge spec. A queue slot is reserved first and given back by
 * anything that then fails.
 */
class RejudgeServiceTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final LocalDateTime AN_HOUR_AGO = LocalDateTime.now().minusHours(1).withNano(0);

    @Autowired RejudgeService rejudgeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ProblemCatalog problemCatalog;
    @MockitoBean SubmissionAdmission admission;

    private final String slug = "rejudge-" + UUID.randomUUID();
    private final UUID problemId = UUID.randomUUID();
    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void removeRows() {
        // Left at PENDING, these rows would count in every other context's queue depth.
        for (UUID id : inserted) {
            jdbc.update("DELETE FROM t_outbox WHERE message_key = ?", id.toString());
            jdbc.update("DELETE FROM t_submissions WHERE id = ?", id);
        }
    }

    private UUID insertSubmission(int status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, result, cpu_time, \"time\", memory, error_message, details, "
                        + "judging_started_at, rejudge_count, source_code, created_at, updated_at, language_id, "
                        + "problem_id, problem_slug, user_id) "
                        + "VALUES (?, ?, ?, 12, 15, 3072, 'old error', "
                        + "'[{\"test_case\": \"1\", \"result\": -1}]'::jsonb, ?, 0, 'int main(){}', ?, ?, ?, ?, ?, ?)",
                id, status, status, AN_HOUR_AGO.plusSeconds(5), AN_HOUR_AGO, AN_HOUR_AGO, C_LANGUAGE_ID, problemId,
                slug, UUID.randomUUID());
        inserted.add(id);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, result, cpu_time, \"time\", memory, error_message, details::text "
                + "AS details, judging_started_at, rejudge_count, updated_at FROM t_submissions WHERE id = ?", id);
    }

    private List<String> requests(UUID id) {
        return jdbc.queryForList("SELECT convert_from(payload, 'UTF8') FROM t_outbox "
                + "WHERE topic = 'submission.requested' AND message_key = ?", String.class, id.toString());
    }

    private JudgeSpec currentSpec() {
        return new JudgeSpec(problemId, slug, 2000, 128L, "newhash123");
    }

    @Test
    void aFinishedSubmissionIsResetAndQueuedWithTheCurrentSpec() throws Exception {
        UUID id = insertSubmission(0);
        List<Boolean> inTransaction = new ArrayList<>();
        when(problemCatalog.judgeSpec(slug, true)).thenAnswer(call -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return currentSpec();
        });

        SubmissionResponseDto dto = rejudgeService.rejudge(id);

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(6);
        assertThat(row.get("result")).isNull();
        assertThat(row.get("cpu_time")).isNull();
        assertThat(row.get("error_message")).isNull();
        assertThat(row.get("details")).isNull();
        assertThat(row.get("judging_started_at")).isNull();
        assertThat(row.get("time")).isEqualTo(0);
        assertThat(row.get("memory")).isEqualTo(0L);
        assertThat(row.get("rejudge_count")).isEqualTo(1);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toLocalDateTime()).isAfter(AN_HOUR_AGO);

        List<String> requests = requests(id);
        assertThat(requests).hasSize(1);
        JsonNode request = objectMapper.readTree(requests.get(0));
        assertThat(request.get("test_case_id").asText()).isEqualTo(slug + "__newhash123");
        assertThat(request.get("submissionId").asText()).isEqualTo(id.toString());
        assertThat(request.get("src").asText()).isEqualTo("int main(){}");
        assertThat(request.get("max_cpu_time").asInt()).isEqualTo(2000);

        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getStatus()).isEqualTo(6);
        assertThat(dto.getLanguage().getId()).isEqualTo(C_LANGUAGE_ID);
        assertThat(inTransaction).as("the problems call holds no transaction").containsExactly(false);
        verify(admission).admit(1);
        verify(admission, never()).release(anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 7})
    void aSubmissionStillPendingOrJudgingIs409(int status) {
        UUID id = insertSubmission(status);

        assertThatThrownBy(() -> rejudgeService.rejudge(id))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SUBMISSION_NOT_FINISHED));

        assertThat(requests(id)).isEmpty();
        assertThat(row(id).get("rejudge_count")).isEqualTo(0);
        verifyNoInteractions(admission, problemCatalog);
    }

    @Test
    void aFullQueueRefusesTheRejudge() {
        UUID id = insertSubmission(0);
        Map<String, Object> before = row(id);
        doThrow(new RateLimitedException(ErrorCode.QUEUE_FULL, SubmissionAdmission.RETRY_AFTER_SECONDS))
                .when(admission).admit(1);

        assertThatThrownBy(() -> rejudgeService.rejudge(id))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUEUE_FULL));

        assertThat(row(id)).isEqualTo(before);
        assertThat(requests(id)).isEmpty();
        verifyNoInteractions(problemCatalog);
        verify(admission, never()).release(anyInt());  // nothing was reserved
    }

    @Test
    void anUnknownIdIs404() {
        assertThatThrownBy(() -> rejudgeService.rejudge(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(admission, problemCatalog);
    }

    @Test
    void aRejudgeMayJudgeAnInactiveProblem() {
        UUID id = insertSubmission(0);
        when(problemCatalog.judgeSpec(slug, true)).thenReturn(currentSpec());

        rejudgeService.rejudge(id);

        verify(problemCatalog).judgeSpec(slug, true);
        verify(problemCatalog, never()).judgeSpec(slug, false);
    }

    @Test
    void aDeletedProblemIs404AndGivesTheSlotBack() {
        UUID id = insertSubmission(0);
        Map<String, Object> before = row(id);
        when(problemCatalog.judgeSpec(slug, true)).thenThrow(new ProblemNotFoundException());

        assertThatThrownBy(() -> rejudgeService.rejudge(id)).isInstanceOf(ProblemNotFoundException.class);

        assertThat(row(id)).isEqualTo(before);
        assertThat(requests(id)).isEmpty();
        verify(admission).admit(1);
        verify(admission).release(1);
    }

    @Test
    void aSubmissionRequeuedMeanwhileIs409AndGivesTheSlotBack() {
        UUID id = insertSubmission(0);
        // Another rejudge resets the row between the status check and this one's reset.
        when(problemCatalog.judgeSpec(slug, true)).thenAnswer(call -> {
            jdbc.update("UPDATE t_submissions SET status = 6, rejudge_count = 1 WHERE id = ?", id);
            return currentSpec();
        });

        assertThatThrownBy(() -> rejudgeService.rejudge(id))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SUBMISSION_NOT_FINISHED));

        assertThat(row(id).get("rejudge_count")).as("reset once, by the other rejudge").isEqualTo(1);
        assertThat(requests(id)).isEmpty();
        verify(admission).admit(1);
        verify(admission).release(1);
    }
}
