package vn.thanhtuanle.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;
import vn.thanhtuanle.messaging.KafkaTopics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A reply on run.judged this service cannot read is skipped at once and counted — never retried for five minutes,
 * never dead-lettered onto submission.judged.dlq as if it were a verdict (Run code design §7).
 */
class RunReplyErrorHandlerTest {

    @Test
    void anUnreadableReplyIsCountedAndSkippedNotRethrown() {
        Counter unreadable = new SimpleMeterRegistry().counter("oj.run.unreadable_replies");
        ConsumerRecordRecoverer recoverer = RunKafkaConfig.runReplyRecoverer(unreadable);
        ConsumerRecord<String, String> reply = new ConsumerRecord<>(KafkaTopics.RUN_JUDGED, 0, 42L, "k", "not json");

        assertThatCode(() -> recoverer.accept(reply,
                new DeserializationException("bad", "not json".getBytes(), false, new RuntimeException("x"))))
                .doesNotThrowAnyException();

        assertThat(unreadable.count()).isEqualTo(1.0);
    }
}
