package vn.thanhtuanle.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;
import vn.thanhtuanle.messaging.KafkaTopics;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaConsumerConfigTest {

    @Test
    void theBackOffIsOneSecondDoublingToThirtyForFiveMinutes() {
        ExponentialBackOff backOff = KafkaConsumerConfig.verdictBackOff();

        assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(30_000L);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(300_000L);
    }

    @Test
    void aDeadLetterIsCountedOnlyOnceItIsPublished() {
        Counter deadLettered = new SimpleMeterRegistry().counter("oj.verdict.dead.lettered");
        List<ConsumerRecord<?, ?>> published = new ArrayList<>();
        boolean[] brokerDown = {true};
        ConsumerRecordRecoverer deadLetters = (record, ex) -> {
            if (brokerDown[0]) {
                throw new KafkaException("Dead-letter publish failed");
            }
            published.add(record);
        };
        ConsumerRecordRecoverer recoverer = KafkaConsumerConfig.verdictRecoverer(deadLetters, deadLettered);
        ConsumerRecord<String, String> verdict = new ConsumerRecord<>(KafkaTopics.SUBMISSION_JUDGED, 0, 42L, "k", "v");

        // A failed publish throws: the record is delivered and recovered again, and must not be counted twice.
        assertThatThrownBy(() -> recoverer.accept(verdict, new RuntimeException("poison")))
                .isInstanceOf(KafkaException.class);
        assertThat(deadLettered.count()).isZero();

        brokerDown[0] = false;
        recoverer.accept(verdict, new RuntimeException("poison"));

        assertThat(published).containsExactly(verdict);
        assertThat(deadLettered.count()).isEqualTo(1.0);
    }
}
