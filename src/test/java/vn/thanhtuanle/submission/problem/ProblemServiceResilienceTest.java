package vn.thanhtuanle.submission.problem;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The policy around calls to problem-service (sub-project 7 §3.1 F3). */
class ProblemServiceResilienceTest {

    @Test
    void attemptsAreRetriedFiftyMillisecondsApart() {
        assertThat(ProblemServiceResilience.retryConfig().getIntervalBiFunction().apply(1, null)).isEqualTo(50L);
    }
}
