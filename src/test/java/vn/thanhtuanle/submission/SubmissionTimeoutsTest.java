package vn.thanhtuanle.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.submission.SubmissionTimeouts.TimedOut;
import vn.thanhtuanle.support.PostgresTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two reconcile rules on real Postgres: a JUDGING row times out 3 minutes after its worker started, a PENDING row
 * 30 minutes after its judge request left the outbox (or, without a request row, after its creation). Each rule is one
 * conditional UPDATE … RETURNING, so concurrent passes never fail (and announce) the same row twice.
 *
 * <p>The clock is fixed in 2001: rows other Postgres tests leave behind carry today's times, so they never cross these
 * thresholds, and every rule call here returns exactly this test's rows.
 */
class SubmissionTimeoutsTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final LocalDateTime NOW = LocalDateTime.of(2001, 1, 1, 12, 0);
    private static final LocalDateTime JUDGING_THRESHOLD = NOW.minusMinutes(3);
    private static final LocalDateTime PENDING_THRESHOLD = NOW.minusMinutes(30);
    /** The progress window: a worker that started anything in the last 5 minutes means the queue is being drained. */
    private static final LocalDateTime PROGRESS_SINCE = NOW.minusMinutes(5);
    private static final int PENDING = SubmissionResult.PENDING.getValue();
    private static final int JUDGING = SubmissionResult.JUDGING.getValue();

    @Autowired SubmissionTimeouts timeouts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void removeRows() {
        for (UUID id : inserted) {
            jdbc.update("DELETE FROM t_outbox WHERE message_key = ?", id.toString());
            jdbc.update("DELETE FROM t_submissions WHERE id = ?", id);
        }
    }

    private UUID submission(int status, LocalDateTime createdAt, LocalDateTime judgingStartedAt, int rejudgeCount) {
        return submission(status, createdAt, judgingStartedAt, rejudgeCount, UUID.randomUUID(), null);
    }

    private UUID submission(int status, LocalDateTime createdAt, LocalDateTime judgingStartedAt, int rejudgeCount,
                            UUID userId, UUID contestId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", created_at, updated_at, language_id, problem_id, "
                        + "problem_slug, user_id, judging_started_at, rejudge_count, contest_id) "
                        + "VALUES (?, ?, 0, ?, ?, ?, ?, 'reconcile', ?, ?, ?, ?)",
                id, status, createdAt, createdAt, C_LANGUAGE_ID, UUID.randomUUID(), userId,
                judgingStartedAt, rejudgeCount, contestId);
        inserted.add(id);
        return id;
    }

    private UUID judging(LocalDateTime startedAt) {
        return submission(JUDGING, startedAt.minusMinutes(1), startedAt, 0);
    }

    private UUID pending(LocalDateTime createdAt) {
        return submission(PENDING, createdAt, null, 0);
    }

    /** The submission's judge request in the outbox; {@code publishedAt} null while it waits for Kafka. */
    private void request(UUID submissionId, LocalDateTime publishedAt) {
        jdbc.update("INSERT INTO t_outbox (id, topic, message_key, payload, created_at, published_at) "
                        + "VALUES (?, 'submission.requested', ?, ?, ?, ?)",
                UUID.randomUUID(), submissionId.toString(), new byte[] {1}, NOW.minusHours(3), publishedAt);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, error_message, updated_at FROM t_submissions WHERE id = ?", id);
    }

    /** What a rule returns for the row, read back from the table. */
    private TimedOut timedOut(UUID id, boolean rejudge) {
        return jdbc.queryForObject("SELECT problem_id, user_id, contest_id, created_at FROM t_submissions WHERE id = ?",
                (rs, rowNum) -> new TimedOut(id, rs.getObject("problem_id", UUID.class), rejudge,
                        rs.getObject("user_id", UUID.class), rs.getObject("contest_id", UUID.class),
                        rs.getObject("created_at", LocalDateTime.class)), id);
    }

    private UUID problemId(UUID id) {
        return jdbc.queryForObject("SELECT problem_id FROM t_submissions WHERE id = ?", UUID.class, id);
    }

    private static Set<UUID> ids(List<TimedOut> timedOut) {
        return timedOut.stream().map(TimedOut::id).collect(Collectors.toSet());
    }

    @Test
    void judgingStartedFourMinutesAgoIsFailed() {
        UUID id = judging(NOW.minusMinutes(4));

        List<TimedOut> timedOut = timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW);

        assertThat(timedOut).containsExactly(timedOut(id, false));
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(SubmissionResult.SYSTEM_ERROR.getValue());
        assertThat(row.get("error_message")).isEqualTo("Judging timed out");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM t_submissions WHERE id = ?", LocalDateTime.class, id))
                .isEqualTo(NOW);
    }

    @Test
    void aJudgingRowWithoutAStartTimesOutFromItsLastUpdate() {
        UUID stale = submission(JUDGING, NOW.minusMinutes(4), null, 0);
        UUID recent = submission(JUDGING, NOW.minusMinutes(1), null, 0);

        assertThat(ids(timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW))).containsExactly(stale);
        assertThat(row(stale).get("status")).isEqualTo(SubmissionResult.SYSTEM_ERROR.getValue());
        assertThat(row(recent).get("status")).isEqualTo(JUDGING);
    }

    @Test
    void judgingStartedOneMinuteAgoIsKept() {
        // Created long ago: only the start time counts for a JUDGING row.
        UUID id = submission(JUDGING, NOW.minusHours(1), NOW.minusMinutes(1), 0);

        assertThat(timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW)).isEmpty();
        assertThat(row(id).get("status")).isEqualTo(JUDGING);
        assertThat(row(id).get("error_message")).isNull();
    }

    @Test
    void theJudgingRuleNeverTouchesPendingOrFinished() {
        // A PENDING row may still carry the start of an earlier judging (a rejudge re-queued it).
        UUID requeued = submission(PENDING, NOW.minusHours(1), NOW.minusMinutes(10), 1);
        UUID accepted = submission(SubmissionResult.ACCEPTED.getValue(), NOW.minusHours(1), NOW.minusMinutes(10), 0);

        assertThat(timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW)).isEmpty();
        assertThat(row(requeued).get("status")).isEqualTo(PENDING);
        assertThat(row(accepted).get("status")).isEqualTo(SubmissionResult.ACCEPTED.getValue());
        assertThat(row(accepted).get("error_message")).isNull();
    }

    @Test
    void thePendingRuleNeverTouchesJudgingOrFinished() {
        UUID judging = submission(JUDGING, NOW.minusHours(1), NOW.minusMinutes(1), 0);
        UUID accepted = submission(SubmissionResult.ACCEPTED.getValue(), NOW.minusHours(1), null, 0);

        assertThat(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)).isEmpty();
        assertThat(row(judging).get("status")).isEqualTo(JUDGING);
        assertThat(row(accepted).get("status")).isEqualTo(SubmissionResult.ACCEPTED.getValue());
    }

    @Test
    void pendingWhoseRequestLeftTheOutbox31MinutesAgoIsFailed() {
        UUID id = pending(NOW.minusMinutes(32));
        request(id, NOW.minusMinutes(31));

        List<TimedOut> timedOut = timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE);

        assertThat(timedOut).containsExactly(timedOut(id, false));
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo(SubmissionResult.SYSTEM_ERROR.getValue());
        assertThat(row.get("error_message")).isEqualTo("Judging request lost");
        assertThat(jdbc.queryForObject("SELECT updated_at FROM t_submissions WHERE id = ?", LocalDateTime.class, id))
                .isEqualTo(NOW);
    }

    @Test
    void pendingWhoseRequestIsStillInTheOutboxIsKept() {
        // Kafka has been down for an hour: the request never left, so the verdict is still to come.
        UUID id = pending(NOW.minusHours(1));
        request(id, null);

        assertThat(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)).isEmpty();
        assertThat(row(id).get("status")).isEqualTo(PENDING);
    }

    @Test
    void pendingWhoseRequestWasSentOneMinuteAgoIsKept() {
        // A rejudge: created two hours ago, its first request long sent, the rejudge's request sent a minute ago.
        UUID id = submission(PENDING, NOW.minusHours(2), NOW.minusHours(2), 1);
        request(id, NOW.minusHours(2));
        request(id, NOW.minusMinutes(1));

        assertThat(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)).isEmpty();
        assertThat(row(id).get("status")).isEqualTo(PENDING);
    }

    @Test
    void pendingWithoutAnOutboxRowCountsFromCreation() {
        // Sent before the outbox existed, or its row was cleaned up.
        UUID old = pending(NOW.minusMinutes(31));
        UUID young = pending(NOW.minusMinutes(29));

        List<TimedOut> timedOut = timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE);

        assertThat(ids(timedOut)).containsExactly(old);
        assertThat(row(old).get("error_message")).isEqualTo("Judging request lost");
        assertThat(row(young).get("status")).isEqualTo(PENDING);
    }

    @Test
    void aLongPendingIsKeptWhileAWorkerKeepsStartingSubmissions() {
        // A queue longer than 30 minutes is not lost work while the workers are working through it.
        UUID waiting = pending(NOW.minusMinutes(40));
        request(waiting, NOW.minusMinutes(35));
        submission(SubmissionResult.ACCEPTED.getValue(), NOW.minusMinutes(50), NOW.minusMinutes(1), 0);

        assertThat(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)).isEmpty();
        assertThat(row(waiting).get("status")).isEqualTo(PENDING);
    }

    @Test
    void aLongPendingIsFailedWhenOnlyNewerSubmissionsAreBeingStarted() {
        // Work submitted after it is being started while it is not: its request was skipped, not queued.
        UUID skipped = pending(NOW.minusMinutes(40));
        request(skipped, NOW.minusMinutes(35));
        submission(SubmissionResult.ACCEPTED.getValue(), NOW.minusMinutes(10), NOW.minusMinutes(1), 0);

        assertThat(ids(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)))
                .containsExactly(skipped);
    }

    @Test
    void aLongPendingWhoseRequestRowIsDeadIsFailed() {
        // The relay gave up on its request: it will never leave the outbox, so "still waiting there" is not true.
        UUID id = pending(NOW.minusHours(1));
        request(id, null);
        jdbc.update("UPDATE t_outbox SET dead_at = ? WHERE message_key = ?", NOW.minusMinutes(5), id.toString());

        assertThat(ids(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)))
                .containsExactly(id);
    }

    @Test
    void aLongPendingIsFailedWhenNoWorkerStartedAnythingInTheWindow() {
        UUID waiting = pending(NOW.minusMinutes(40));
        request(waiting, NOW.minusMinutes(35));
        submission(SubmissionResult.ACCEPTED.getValue(), NOW.minusMinutes(50), NOW.minusMinutes(10), 0);

        assertThat(ids(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)))
                .containsExactly(waiting);
    }

    @Test
    void aStartDatedAfterTheCallIsNotProgress() {
        // Rows other tests leave carry today's times, long after this test's 2001 clock: they must not count.
        UUID waiting = pending(NOW.minusMinutes(40));
        request(waiting, NOW.minusMinutes(35));
        submission(SubmissionResult.ACCEPTED.getValue(), NOW.minusMinutes(50), NOW.plusDays(1), 0);

        assertThat(ids(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)))
                .containsExactly(waiting);
    }

    @Test
    void aRejudgedRowIsReturnedWithRejudgeTrue() {
        // started 6 min ago: past the 3 min judging threshold, outside the 5 min window the pending rule reads as progress
        UUID judging = submission(JUDGING, NOW.minusHours(2), NOW.minusMinutes(6), 2);
        UUID pending = submission(PENDING, NOW.minusHours(2), null, 1);
        request(pending, NOW.minusMinutes(31));

        assertThat(timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW))
                .containsExactly(timedOut(judging, true));
        assertThat(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE))
                .containsExactly(timedOut(pending, true));
    }

    @Test
    void bothRulesReturnTheSubmitterTheContestAndTheSubmitTime() {
        UUID userId = UUID.randomUUID();
        UUID contestId = UUID.randomUUID();
        UUID judging = submission(JUDGING, NOW.minusMinutes(10), NOW.minusMinutes(6), 0, userId, contestId);
        UUID pending = submission(PENDING, NOW.minusMinutes(40), null, 0, userId, contestId);

        assertThat(timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW)).containsExactly(
                new TimedOut(judging, problemId(judging), false, userId, contestId, NOW.minusMinutes(10)));
        assertThat(timeouts.failPendingCreatedBefore(PENDING_THRESHOLD, NOW, PROGRESS_SINCE)).containsExactly(
                new TimedOut(pending, problemId(pending), false, userId, contestId, NOW.minusMinutes(40)));
    }

    @Test
    void twoConcurrentPassesReturnEachIdOnce() throws Exception {
        Set<UUID> stuck = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            stuck.add(judging(NOW.minusMinutes(10 + i)));
        }
        TransactionTemplate firstTx = new TransactionTemplate(transactionManager);
        TransactionTemplate secondTx = new TransactionTemplate(transactionManager);
        CountDownLatch firstUpdated = new CountDownLatch(1);
        AtomicBoolean secondWaitedOnTheFirst = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The first pass keeps its transaction open until the second is blocked on the rows it updated.
            Future<List<TimedOut>> first = pool.submit(() -> firstTx.execute(tx -> {
                List<TimedOut> timedOut = timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW);
                firstUpdated.countDown();
                secondWaitedOnTheFirst.set(awaitALockWait());
                return timedOut;
            }));
            assertThat(firstUpdated.await(30, SECONDS)).isTrue();
            Future<List<TimedOut>> second = pool.submit(() -> secondTx.execute(
                    tx -> timeouts.failJudgingStartedBefore(JUDGING_THRESHOLD, NOW)));

            Set<UUID> firstIds = ids(first.get(30, SECONDS));
            Set<UUID> secondIds = ids(second.get(30, SECONDS));

            assertThat(secondWaitedOnTheFirst).as("the second pass ran while the first held its rows").isTrue();
            Set<UUID> union = new HashSet<>(firstIds);
            union.addAll(secondIds);
            assertThat(union).isEqualTo(stuck);
            Set<UUID> intersection = new HashSet<>(firstIds);
            intersection.retainAll(secondIds);
            assertThat(intersection).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Polls (on the caller's connection) until some backend of this database waits on a row lock; at most 10 s. */
    private boolean awaitALockWait() {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
            if (waiting != null && waiting > 0) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
