package vn.thanhtuanle.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.ExponentialBackOff;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigTest {

    @Test
    void theBackOffIsOneSecondDoublingToThirtyForFiveMinutes() {
        ExponentialBackOff backOff = KafkaConsumerConfig.verdictBackOff();

        assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(30_000L);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(300_000L);
    }
}
