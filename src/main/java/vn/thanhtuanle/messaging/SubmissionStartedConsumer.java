package vn.thanhtuanle.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.messaging.event.SubmissionStartedEvent;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Applies judge-worker's {@code submission.started}: the submission is JUDGING from the worker's start time. A start
 * is advisory, so one this service cannot use is logged and skipped, never thrown.
 *
 * <p>Uses Boot's listener container factory (and so the {@code tls} profile's Kafka SSL bundle and the shared error
 * handler). The worker sends plain JSON without type headers, so this listener names its own default type.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubmissionStartedConsumer {

    private final SubmissionRepository submissionRepository;

    @KafkaListener(topics = KafkaTopics.SUBMISSION_STARTED, groupId = KafkaTopics.SUBMISSION_STARTED_GROUP,
            properties = "spring.json.value.default.type=vn.thanhtuanle.messaging.event.SubmissionStartedEvent")
    @Transactional
    public void onStarted(SubmissionStartedEvent event) {
        if (event == null || event.submissionId() == null || event.startedAt() == null) {
            log.warn("Ignoring a submission.started without a submissionId or startedAt: {}", event);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(event.submissionId());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring a submission.started with a malformed submissionId: {}", event);
            return;
        }
        MDC.put("submissionId", event.submissionId());
        try {
            // The worker's instant is UTC; created_at, judging_started_at and LocalDateTime.now() are naive times in
            // the JVM's zone (Asia/Ho_Chi_Minh, pinned in SubmissionServiceApplication.main).
            // The worker's clock is not the timeout clock: a start in this service's future (worker clock ahead) is
            // clamped to now, or it would postpone the timeout. A worker clock BEHIND by minutes still shortens the
            // window; not clamped, as the compose host shares one kernel clock.
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime reported = LocalDateTime.ofInstant(event.startedAt(), ZoneId.systemDefault());
            LocalDateTime startedAt = reported.isAfter(now) ? now : reported;
            // A start without a generation (an old worker, a request queued before generations) is applied as before.
            Integer generation = event.rejudgeCount();
            int updated = generation == null
                    ? submissionRepository.applyStarted(id, startedAt, now)
                    : submissionRepository.applyStartedOfGeneration(id, startedAt, now, generation);
            if (updated == 0) {
                log.debug("Ignoring the start of a finished, unknown or since rejudged submission {}", id);
            }
        } finally {
            MDC.remove("submissionId");
        }
    }
}
