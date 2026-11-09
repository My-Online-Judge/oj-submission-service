package vn.thanhtuanle.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.messaging.event.SubmissionProgressEvent;

import java.util.UUID;

/**
 * Forwards judge-worker's {@code submission.progress} (one record per judged case while a submission is judged) to the
 * SSE subscribers through {@link JudgingPubSub}. Nothing is written: progress is advisory, so a record this service
 * cannot use is logged and skipped, never thrown, and a lost one only leaves a gap the verdict fills.
 *
 * <p>Uses Boot's listener container factory (and so the {@code tls} profile's Kafka SSL bundle and the shared error
 * handler, which drops a progress record it gives up on). The worker sends plain JSON without type headers, so this
 * listener names its own default type.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubmissionProgressConsumer {

    private final JudgingPubSub judgingPubSub;

    @KafkaListener(topics = KafkaTopics.SUBMISSION_PROGRESS, groupId = KafkaTopics.SUBMISSION_PROGRESS_GROUP,
            properties = "spring.json.value.default.type=vn.thanhtuanle.messaging.event.SubmissionProgressEvent")
    public void onProgress(SubmissionProgressEvent event) {
        if (event == null || event.submissionId() == null || event.testCase() == null || event.result() == null) {
            log.warn("Ignoring a submission.progress without a submissionId, testCase or result: {}", event);
            return;
        }
        try {
            UUID.fromString(event.submissionId());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring a submission.progress with a malformed submissionId: {}", event);
            return;
        }
        judgingPubSub.publishProgress(event);
    }
}
