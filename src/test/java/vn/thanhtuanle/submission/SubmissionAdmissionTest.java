package vn.thanhtuanle.submission;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmissionAdmissionTest {

    private final SubmissionRepository repo = mock(SubmissionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OjMetrics metrics = new OjMetrics(registry, repo);
    private final SubmissionAdmission admission = new SubmissionAdmission(metrics, registry, 500);

    private void queued(long count) {
        when(repo.countByStatusIn(anyCollection())).thenReturn(count);
        metrics.refreshQueueDepth();
    }

    @Test
    void belowTheLimitPasses() {
        queued(499);

        assertThatCode(() -> admission.admit(1)).doesNotThrowAnyException();
        assertThat(metrics.queueDepth()).as("the slot is reserved").isEqualTo(500);
    }

    @Test
    void atTheLimitIsQueueFullWithRetryAfter30() {
        queued(500);

        RateLimitedException refused = catchThrowableOfType(() -> admission.admit(1), RateLimitedException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.QUEUE_FULL);
        assertThat(refused.getRetryAfterSeconds()).isEqualTo(30);
        assertThat(metrics.queueDepth()).isEqualTo(500);
    }

    @Test
    void aBatchThatWouldCrossTheLimitIsRefusedWhole() {
        queued(498);

        assertThat(catchThrowableOfType(() -> admission.admit(3), RateLimitedException.class)).isNotNull();
        assertThat(metrics.queueDepth()).as("no part of the batch is reserved").isEqualTo(498);
    }

    @Test
    void aRefusalIsCounted() {
        assertThat(refusals()).as("registered at 0").isZero();
        queued(500);

        assertThat(catchThrowableOfType(() -> admission.admit(1), RateLimitedException.class)).isNotNull();
        assertThat(refusals()).isEqualTo(1);

        queued(10);
        admission.admit(1);
        assertThat(refusals()).as("an admitted call is no refusal").isEqualTo(1);
    }

    private double refusals() {
        return registry.get("oj.admission.refused").counter().count();
    }

    @Test
    void zeroAlwaysPasses() {
        queued(600);  // over the limit already: a reservation of nothing still passes

        assertThatCode(() -> admission.admit(0)).doesNotThrowAnyException();
        assertThat(metrics.queueDepth()).isEqualTo(600);
    }

    @Test
    void releasedSlotsCanBeAdmittedAgain() {
        queued(499);
        admission.admit(1);

        admission.release(1);

        assertThat(metrics.queueDepth()).isEqualTo(499);
        assertThatCode(() -> admission.admit(1)).doesNotThrowAnyException();
    }
}
