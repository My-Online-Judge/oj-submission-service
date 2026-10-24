package vn.thanhtuanle.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The container factory of the {@code run.judged} listener (Run code design §7). Built by Boot's configurer on Boot's
 * consumer factory, so the {@code tls} profile's Kafka SSL bundle applies as it does to the other listeners; with its
 * own error handler: a record that cannot be handled is counted and skipped at once. The shared
 * {@link KafkaConsumerConfig} handler would retry it for five minutes and then publish it to
 * {@code submission.judged.dlq}, where the replay script treats every record as a verdict.
 */
@Configuration
@Slf4j
public class RunKafkaConfig {

    /** Logs the record and the cause, counts it, never throws (so the container moves past it). */
    static ConsumerRecordRecoverer runReplyRecoverer(Counter unreadable) {
        return (record, ex) -> {
            log.warn("Skipping run.judged record {}@{} (key {}): {}", record.partition(), record.offset(),
                    record.key(), NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
            unreadable.increment();
        };
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> runJudgedContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory, MeterRegistry meters) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        Counter unreadable = Counter.builder("oj.run.unreadable_replies")
                .description("Records on run.judged this service could not read as a reply")
                .register(meters);
        factory.setCommonErrorHandler(new DefaultErrorHandler(runReplyRecoverer(unreadable), new FixedBackOff(0L, 0L)));
        return factory;
    }
}
