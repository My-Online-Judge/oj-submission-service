package vn.thanhtuanle.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class OjMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OjMetrics metrics = new OjMetrics(registry, mock(SubmissionRepository.class));

    @Test
    void recordVerdict_recordsLatencyAndTaggedCounter() {
        metrics.recordVerdict(SubmissionResult.ACCEPTED.getValue(), LocalDateTime.now().minusSeconds(2), false);

        assertThat(registry.get("oj.judge.latency").timer().count()).isEqualTo(1);
        assertThat(registry.get("oj.verdict").tag("status", "ACCEPTED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aRejudgedVerdictCountsButIsNotALatencySample() {
        // A rejudged row keeps its original created_at: submit-to-verdict would measure the age of the submission.
        metrics.recordVerdict(SubmissionResult.WRONG_ANSWER.getValue(), LocalDateTime.now().minusDays(3), true);

        assertThat(registry.get("oj.verdict").tag("status", "WRONG_ANSWER").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("oj.judge.latency").timer().count()).isZero();
    }

    @Test
    void recordVerdict_neverThrowsOnBadStatus() {
        // 999 is not a valid SubmissionResult value — must not throw.
        metrics.recordVerdict(999, LocalDateTime.now(), false);
        assertThat(registry.get("oj.judge.latency").timer().count()).isEqualTo(1);
    }

    @Test
    void recordVerdict_neverThrowsOnNullCreatedAt() {
        assertThatCode(() -> metrics.recordVerdict(SubmissionResult.ACCEPTED.getValue(), null, false))
                .doesNotThrowAnyException();
    }
}
