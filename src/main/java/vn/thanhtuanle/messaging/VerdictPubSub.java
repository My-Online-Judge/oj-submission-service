package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.submission.VerdictPush;

/**
 * Fans out "this submission has a verdict" across submission-service instances so an SSE subscriber is notified no
 * matter which instance consumed the Kafka verdict.
 *
 * <p>The DB write stays single (JudgeResultConsumer, shared group, idempotent). After it commits, the submission id is
 * PUBLISHed to the {@code oj.verdicts} Redis channel; every instance SUBSCRIBEs and hands the id to its
 * {@link VerdictPush} — a no-op unless that instance holds the submission's emitter, which then reads the verdict back
 * and builds its view on its own pool. The message carries only the id, so neither the consumer's transaction nor this
 * listener's thread ever waits for problem-service (the sample test cases in the view come from it).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VerdictPubSub implements MessageListener {

    public static final String CHANNEL = "oj.verdicts";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VerdictPush verdictPush;

    /**
     * Broadcast a verdict to every instance so whichever one holds the SSE emitter delivers it.
     *
     * <p>Package-private on purpose: every publish that follows a state write MUST go through
     * {@link #publishAfterCommit} instead, and this visibility makes that a compile error for
     * any caller outside this package rather than a convention someone can forget (as
     * {@code SubmissionReconcileJob} once did). Direct callers within this package (this class,
     * and this package's own tests) are the deferral mechanism itself and the no-transaction
     * immediate-publish path — never a second, competing writer.
     */
    void publish(String submissionId) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(new VerdictMessage(submissionId)));
        } catch (Exception e) {
            log.error("Failed to publish verdict for submission {}", submissionId, e);
        }
    }

    /**
     * Publish a verdict only AFTER the caller's transaction commits — or immediately when no
     * transaction synchronization is active (direct calls, unit tests). Publishing mid-transaction
     * opens a lost-verdict window: a subscriber's fresh status re-read
     * ({@code SubmissionService.streamVerdict}) can still see the row as uncommitted-PENDING while
     * the live publish has already passed its emitter by. Post-commit, a subscriber either
     * registered before the publish (receives it live) or re-reads after the commit (sees the
     * terminal row, replays). Every publish that follows a state write MUST go through this method,
     * never through {@link #publish} directly — and since {@link #publish} is package-private,
     * that rule is enforced by the compiler for every caller outside this package, not left to
     * convention or review.
     */
    public void publishAfterCommit(String submissionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(submissionId);
                }
            });
        } else {
            publish(submissionId);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            verdictPush.pushIfWatched(objectMapper.readValue(message.getBody(), VerdictMessage.class).submissionId());
        } catch (Exception e) {
            log.error("Failed to handle verdict pub/sub message", e);
        }
    }

    /** What travels on {@link #CHANNEL}: the id of a submission whose verdict was just committed. */
    public record VerdictMessage(String submissionId) {}
}
