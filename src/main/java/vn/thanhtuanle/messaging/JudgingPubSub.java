package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.messaging.event.SubmissionProgressEvent;
import vn.thanhtuanle.submission.SubmissionSseRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fans out what happens to a submission WHILE it is judged across submission-service instances, so the SSE
 * subscriber sees it whichever instance consumed the Kafka record: the worker's start ({@link #EVENT_STARTED}) and
 * each judged case ({@link #EVENT_PROGRESS}). Both are advisory and in-memory: nothing is read back from the database,
 * the emitter is not completed, and an instance that holds no emitter for the id does nothing. The verdict itself has
 * its own path ({@link VerdictPubSub}, which reads the committed row and completes the stream).
 *
 * <p>No generation check on the progress path, on purpose: progress only flows while a generation is being judged
 * and a stream closes with its verdict, so a stale generation's progress would have to arrive in the short window
 * between a rejudge and the new judging's first event; the ladder then fills again from the new events, and the
 * verdict replaces it all. The start is published only when the consumer applied it, which does check the
 * generation.
 */
@Component
@Slf4j
public class JudgingPubSub implements MessageListener {

    public static final String CHANNEL = "oj.judging";
    /** The SSE event names, as the portal's {@code useSubmissionStream} listens for them. */
    public static final String EVENT_STARTED = "started";
    public static final String EVENT_PROGRESS = "progress";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SubmissionSseRegistry sseRegistry;
    private final Counter fallbacks;

    public JudgingPubSub(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                         SubmissionSseRegistry sseRegistry, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sseRegistry = sseRegistry;
        // Progress or a start that could not be broadcast is lost for good; the verdict still arrives on its own path.
        this.fallbacks = Counter.builder("oj.redis.fallback")
                .description("Requests or work that went on without Redis because a Redis call failed (fail-open)")
                .tag("lookup", "judging_publish")
                .register(registry);
    }

    /** Broadcasts that the worker started judging the submission, once the consumer's transaction has committed. */
    public void publishStartedAfterCommit(String submissionId) {
        AfterCommit.run(() -> publish(submissionId, EVENT_STARTED,
                Map.of("status", SubmissionResult.JUDGING.getValue())));
    }

    /**
     * Broadcasts one judged case. Only the case's name, result, error code, measurements and the total travel: the
     * event is rebuilt here so a worker field this service does not know (or the program's output, should a worker
     * ever send it) never reaches the browser.
     */
    public void publishProgress(SubmissionProgressEvent event) {
        publish(event.submissionId(), EVENT_PROGRESS, progressData(event));
    }

    /** What the browser gets on a {@code progress} event. */
    static Map<String, Object> progressData(SubmissionProgressEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("testCase", event.testCase());
        data.put("result", event.result());
        data.put("error", event.error());
        data.put("cpuTime", event.cpuTime());
        data.put("realTime", event.realTime());
        data.put("memory", event.memory());
        data.put("total", event.total());
        return data;
    }

    private void publish(String submissionId, String event, Map<String, Object> data) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(
                    new JudgingMessage(submissionId, event, objectMapper.valueToTree(data))));
        } catch (Exception e) {
            fallbacks.increment();
            log.warn("Failed to publish {} of submission {}", event, submissionId, e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JudgingMessage m = objectMapper.readValue(message.getBody(), JudgingMessage.class);
            if (m.submissionId() == null || m.event() == null || m.data() == null) {
                log.warn("Ignoring a judging message without an id, an event or data");
                return;
            }
            sseRegistry.send(m.submissionId(), m.event(), m.data());
        } catch (Exception e) {
            log.error("Failed to handle judging pub/sub message", e);
        }
    }

    /** What travels on {@link #CHANNEL}: the submission, the SSE event name and the event's JSON payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JudgingMessage(String submissionId, String event, JsonNode data) {}
}
