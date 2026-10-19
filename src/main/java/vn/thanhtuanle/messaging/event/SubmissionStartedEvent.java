package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * judge-worker's announcement on {@code submission.started} that it begins judging a submission now. {@code startedAt}
 * is a UTC instant (ISO-8601 with milliseconds and {@code Z}); this service stores it in its own zone.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionStartedEvent(String submissionId, String worker, Instant startedAt) {
}
