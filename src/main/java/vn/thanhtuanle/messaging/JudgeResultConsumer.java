package vn.thanhtuanle.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.oj.common.event.OjTopics;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JudgeResultConsumer {

    static final int MAX_ERROR_MESSAGE_CHARS = 65536;

    private final SubmissionRepository submissionRepository;
    private final VerdictPubSub verdictPubSub;
    private final OjMetrics ojMetrics;
    private final OutboxWriter outboxWriter;

    @KafkaListener(topics = KafkaTopics.SUBMISSION_JUDGED, groupId = "judge-api-results")
    @Transactional
    public void onJudged(SubmissionJudgedEvent event) {
        MDC.put("submissionId", event.getSubmissionId());
        try {
            UUID id = parseId(event.getSubmissionId());
            int status;
            String errorMessage;
            if (SubmissionResult.isTerminalVerdict(event.getStatus())) {
                status = event.getStatus();
                errorMessage = event.getErrorMessage();
                if (errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE_CHARS) {
                    errorMessage = errorMessage.substring(0, MAX_ERROR_MESSAGE_CHARS);
                }
            } else {
                // A verdict must finish the submission: one it cannot be stored as becomes SYSTEM_ERROR.
                log.warn("Invalid verdict status {} for submission {}, storing SYSTEM_ERROR", event.getStatus(), id);
                status = SubmissionResult.SYSTEM_ERROR.getValue();
                errorMessage = "Invalid verdict status " + event.getStatus();
            }
            // time is NOT NULL: a replayed record or an old worker may send none.
            int time = event.getRealTime() == null ? 0 : event.getRealTime();

            // Only the first verdict over PENDING/JUDGING lands; a duplicate, a late verdict (reconcile already
            // failed the submission) or an unknown id matches no row.
            int updated = submissionRepository.applyVerdict(id, status, event.getResult(), event.getCpuTime(), time,
                    event.getMemory(), errorMessage, event.getDetails(), LocalDateTime.now());
            if (updated == 0) {
                log.info("late or duplicate verdict for submission {}", id);
                return;
            }
            Submission submission = submissionRepository.findById(id).orElseThrow(
                    () -> new IllegalStateException("Submission " + id + " vanished after its verdict was written"));
            boolean rejudge = submission.getRejudgeCount() > 0;
            // Same transaction as the verdict: the fact is announced if and only if it was recorded.
            outboxWriter.append(OjTopics.SUBMISSION_EVENTS, submission.getProblemId().toString(),
                    new SubmissionVerdictRecorded(id, submission.getProblemId(), status, rejudge).toEnvelope());
            // Deferred until this @Transactional method commits (VerdictPubSub.publishAfterCommit):
            // publishing mid-transaction would let a subscriber's fresh re-read still see PENDING
            // while the live publish has already passed its emitter by — a lost verdict.
            verdictPubSub.publishAfterCommit(id.toString());
            ojMetrics.recordVerdict(status, submission.getCreatedAt(), rejudge);
            log.info("Applied verdict {} to submission {}", status, id);
        } finally {
            MDC.remove("submissionId");
        }
    }

    private static UUID parseId(String submissionId) {
        IllegalArgumentException cause = null;
        if (submissionId != null) {
            try {
                return UUID.fromString(submissionId);
            } catch (IllegalArgumentException e) {
                cause = e;
            }
        }
        throw new InvalidVerdictException("Verdict with a submissionId that is not a UUID: " + submissionId, cause);
    }
}
