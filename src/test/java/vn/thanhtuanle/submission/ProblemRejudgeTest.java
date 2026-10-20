package vn.thanhtuanle.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.submission.dto.RejudgeQueued;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemNotFoundException;
import vn.thanhtuanle.support.PostgresTest;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rejudging a problem on real Postgres: the newest 500 finished submissions (optionally of one verdict), or fewer when
 * the judge queue has fewer free slots, are reset in one transaction, each with one judge request in the outbox. Only
 * rows actually reset are counted and queued; the queue slots reserved for the others, or for a batch that then
 * fails, are given back.
 */
class ProblemRejudgeTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final LocalDateTime BASE = LocalDateTime.now().minusHours(2).withNano(0);
    private static final int AC = 0;
    private static final int WA = -1;
    private static final int TLE = 1;
    private static final int PENDING = 6;
    private static final int JUDGING = 7;

    @Autowired RejudgeService rejudgeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ProblemCatalog problemCatalog;
    @MockitoBean SubmissionAdmission admission;

    private final String slug = "rejudge-problem-" + UUID.randomUUID();
    private final UUID problemId = UUID.randomUUID();

    @BeforeEach
    void theQueueHasRoom() {
        when(admission.admitUpTo(anyInt())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void removeRows() {
        // Left at PENDING, these rows would count in every other context's queue depth.
        jdbc.update("DELETE FROM t_outbox WHERE message_key IN "
                + "(SELECT id::text FROM t_submissions WHERE problem_slug = ?)", slug);
        jdbc.update("DELETE FROM t_submissions WHERE problem_slug = ?", slug);
    }

    /** One submission per status, a second apart, the first the oldest. */
    private List<UUID> insert(int... statuses) {
        List<UUID> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            LocalDateTime createdAt = BASE.plusSeconds(i);
            rows.add(new Object[] {id, statuses[i], statuses[i], createdAt, createdAt, C_LANGUAGE_ID, problemId, slug,
                    UUID.randomUUID()});
        }
        jdbc.batchUpdate("INSERT INTO t_submissions (id, status, result, cpu_time, \"time\", memory, source_code, "
                + "created_at, updated_at, language_id, problem_id, problem_slug, user_id) "
                + "VALUES (?, ?, ?, 12, 15, 3072, 'int main(){}', ?, ?, ?, ?, ?, ?)", rows);
        return ids;
    }

    private static int[] repeat(int status, int times) {
        int[] statuses = new int[times];
        Arrays.fill(statuses, status);
        return statuses;
    }

    private static int[] concat(int[]... parts) {
        return Arrays.stream(parts).flatMapToInt(Arrays::stream).toArray();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, result, \"time\", memory, rejudge_count, updated_at "
                + "FROM t_submissions WHERE id = ?", id);
    }

    private int requests(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM t_outbox WHERE topic = 'submission.requested' "
                + "AND message_key = ?", Integer.class, id.toString());
    }

    private int requestsForTheProblem() {
        return jdbc.queryForObject("SELECT count(*) FROM t_outbox o JOIN t_submissions s ON o.message_key = s.id::text "
                + "WHERE o.topic = 'submission.requested' AND s.problem_slug = ?", Integer.class, slug);
    }

    private int resetRows() {
        return jdbc.queryForObject("SELECT count(*) FROM t_submissions WHERE problem_slug = ? AND rejudge_count > 0",
                Integer.class, slug);
    }

    private JudgeSpec currentSpec() {
        return new JudgeSpec(problemId, slug, 2000, 128L, "newhash123");
    }

    private void assertUntouched(UUID id, int status) {
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("rejudge_count")).isEqualTo(0);
        assertThat(((Timestamp) row.get("updated_at")).toLocalDateTime()).isBefore(BASE.plusHours(1));
        assertThat(requests(id)).isZero();
    }

    private void assertRequeued(UUID id) {
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(PENDING);
        assertThat(row.get("result")).isNull();
        assertThat(row.get("time")).isEqualTo(0);
        assertThat(row.get("rejudge_count")).isEqualTo(1);
        assertThat(requests(id)).isEqualTo(1);
    }

    @Test
    void rejudgesTheNewest500FinishedSubmissionsOnly() throws Exception {
        // 502 finished, then the two newest still pending.
        List<UUID> ids = insert(concat(repeat(AC, 251), repeat(WA, 251), repeat(PENDING, 2)));
        List<Boolean> inTransaction = new ArrayList<>();
        when(problemCatalog.judgeSpec(slug, true)).thenAnswer(call -> {
            inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return currentSpec();
        });

        RejudgeQueued result = rejudgeService.rejudgeProblem(slug, null);

        assertThat(result.queued()).isEqualTo(500);
        assertUntouched(ids.get(0), AC);
        assertUntouched(ids.get(1), AC);
        assertUntouched(ids.get(502), PENDING);
        assertUntouched(ids.get(503), PENDING);
        assertRequeued(ids.get(2));
        assertRequeued(ids.get(501));
        assertThat(resetRows()).isEqualTo(500);
        assertThat(requestsForTheProblem()).isEqualTo(500);
        String payload = jdbc.queryForObject("SELECT convert_from(payload, 'UTF8') FROM t_outbox "
                + "WHERE message_key = ?", String.class, ids.get(300).toString());
        assertThat(objectMapper.readTree(payload).get("test_case_id").asText()).isEqualTo(slug + "__newhash123");
        assertThat(inTransaction).as("the problems call holds no transaction").containsExactly(false);
        verify(problemCatalog).judgeSpec(slug, true);
        verify(admission).admitUpTo(500);
        verify(admission, never()).release(anyInt());
    }

    @Test
    void theStatusFilterPicksOneResultCode() {
        List<UUID> ids = insert(AC, WA, TLE, WA, PENDING, AC);
        when(problemCatalog.judgeSpec(slug, true)).thenReturn(currentSpec());

        RejudgeQueued result = rejudgeService.rejudgeProblem(slug, WA);

        assertThat(result.queued()).isEqualTo(2);
        assertRequeued(ids.get(1));
        assertRequeued(ids.get(3));
        assertUntouched(ids.get(0), AC);
        assertUntouched(ids.get(2), TLE);
        assertUntouched(ids.get(4), PENDING);
        assertUntouched(ids.get(5), AC);
        verify(admission).admitUpTo(2);
    }

    @Test
    void requestsCarryTheRejudgeCount() throws Exception {
        List<UUID> ids = insert(AC, WA);
        jdbc.update("UPDATE t_submissions SET rejudge_count = 2 WHERE id = ?", ids.get(1));
        when(problemCatalog.judgeSpec(slug, true)).thenReturn(currentSpec());

        rejudgeService.rejudgeProblem(slug, null);

        assertThat(row(ids.get(0)).get("rejudge_count")).isEqualTo(1);
        assertThat(row(ids.get(1)).get("rejudge_count")).isEqualTo(3);
        assertThat(requestedGeneration(ids.get(0))).isEqualTo(1);
        assertThat(requestedGeneration(ids.get(1))).isEqualTo(3);
    }

    private int requestedGeneration(UUID id) throws Exception {
        return request(id).get("rejudgeCount").asInt();
    }

    private JsonNode request(UUID id) throws Exception {
        String payload = jdbc.queryForObject("SELECT convert_from(payload, 'UTF8') FROM t_outbox "
                + "WHERE topic = 'submission.requested' AND message_key = ?", String.class, id.toString());
        return objectMapper.readTree(payload);
    }

    /** See {@link RejudgeServiceTest#aRejudgeOfACheckerProblemCarriesTheChecker}: here, every request of the batch. */
    @Test
    void aRejudgeOfACheckerProblemCarriesTheChecker() throws Exception {
        List<UUID> ids = insert(AC, WA);
        jdbc.update("UPDATE t_submissions SET rejudge_count = 2 WHERE id = ?", ids.get(1));
        when(problemCatalog.judgeSpec(slug, true)).thenReturn(new JudgeSpec(problemId, slug, 2000, 128L,
                "newhash123", null, RejudgeServiceTest.CHECKER, "ab12"));

        RejudgeQueued result = rejudgeService.rejudgeProblem(slug, null);

        assertThat(result.queued()).isEqualTo(2);
        for (UUID id : ids) {
            RejudgeServiceTest.assertCarriesTheChecker(request(id));
        }
        assertThat(requestedGeneration(ids.get(0))).isEqualTo(1);
        assertThat(requestedGeneration(ids.get(1))).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {PENDING, JUDGING, 99})
    void aNonFinalStatusFilterIs400(int status) {
        UUID id = insert(AC).get(0);

        assertThatThrownBy(() -> rejudgeService.rejudgeProblem(slug, status))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status must be a final verdict code");

        assertUntouched(id, AC);
        verifyNoInteractions(admission, problemCatalog);
    }

    @Test
    void aBusyQueueRejudgesOnlyTheNewestThatFit() {
        List<UUID> ids = insert(repeat(AC, 50));
        when(admission.admitUpTo(50)).thenReturn(10);
        when(problemCatalog.judgeSpec(slug, true)).thenReturn(currentSpec());

        RejudgeQueued result = rejudgeService.rejudgeProblem(slug, null);

        assertThat(result.queued()).isEqualTo(10);
        for (UUID newest : ids.subList(40, 50)) {
            assertRequeued(newest);
        }
        for (UUID older : ids.subList(0, 40)) {
            assertUntouched(older, AC);
        }
        assertThat(resetRows()).isEqualTo(10);
        assertThat(requestsForTheProblem()).isEqualTo(10);
        verify(admission, never()).release(anyInt());
    }

    @Test
    void aFullQueueStillRefuses() {
        insert(repeat(AC, 200));
        when(admission.admitUpTo(200))
                .thenThrow(new RateLimitedException(ErrorCode.QUEUE_FULL, SubmissionAdmission.RETRY_AFTER_SECONDS));

        assertThatThrownBy(() -> rejudgeService.rejudgeProblem(slug, null))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUEUE_FULL));

        assertThat(resetRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_submissions WHERE problem_slug = ? AND status = 0",
                Integer.class, slug)).isEqualTo(200);
        assertThat(requestsForTheProblem()).isZero();
        verifyNoInteractions(problemCatalog);
        verify(admission, never()).release(anyInt());  // nothing was reserved
    }

    @Test
    void aProblemWithoutFinishedSubmissionsQueuesZeroWithoutCallingProblems() {
        List<UUID> ids = insert(PENDING, JUDGING);

        RejudgeQueued result = rejudgeService.rejudgeProblem(slug, null);

        assertThat(result.queued()).isZero();
        assertUntouched(ids.get(0), PENDING);
        assertUntouched(ids.get(1), JUDGING);
        verifyNoInteractions(problemCatalog, admission);
    }

    @Test
    void aDeletedProblemIs404() {
        List<UUID> ids = insert(AC, WA, TLE);
        when(problemCatalog.judgeSpec(slug, true)).thenThrow(new ProblemNotFoundException());

        assertThatThrownBy(() -> rejudgeService.rejudgeProblem(slug, null))
                .isInstanceOf(ProblemNotFoundException.class);

        assertUntouched(ids.get(0), AC);
        assertUntouched(ids.get(1), WA);
        assertUntouched(ids.get(2), TLE);
        verify(admission).admitUpTo(3);
        verify(admission).release(3);
    }

    @Test
    void aRowRequeuedMeanwhileIsNeitherCountedNorQueuedTwice() {
        List<UUID> ids = insert(AC, WA, TLE);
        // A single rejudge of the middle row lands between the candidate query and this batch's reset.
        when(problemCatalog.judgeSpec(slug, true)).thenAnswer(call -> {
            jdbc.update("UPDATE t_submissions SET status = 7 WHERE id = ?", ids.get(1));
            return currentSpec();
        });

        RejudgeQueued result = rejudgeService.rejudgeProblem(slug, null);

        assertThat(result.queued()).isEqualTo(2);
        assertRequeued(ids.get(0));
        assertRequeued(ids.get(2));
        assertThat(row(ids.get(1)).get("rejudge_count")).isEqualTo(0);
        assertThat(requests(ids.get(1))).isZero();
        verify(admission).admitUpTo(3);
        verify(admission).release(1);
    }
}
