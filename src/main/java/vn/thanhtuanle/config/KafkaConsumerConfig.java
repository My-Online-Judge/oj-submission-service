package vn.thanhtuanle.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;
import vn.thanhtuanle.messaging.InvalidVerdictException;
import vn.thanhtuanle.messaging.KafkaTopics;

/**
 * The error handler of this service's listeners (Boot's container factory picks it up, so the {@code tls} profile's
 * Kafka SSL bundle applies to the listeners and to the dead-letter publishes, which go through Boot's
 * {@link KafkaTemplate}).
 *
 * <p>Failures are told apart. A record no retry can ever apply (not readable, or a verdict for a {@code submissionId}
 * that is not a UUID) is given up on at once, so one poison record cannot stall its partition. Anything else (the
 * database restarting, a lock) is retried with a growing pause, {@link #verdictBackOff()}: a brief outage must not
 * lose a verdict. A verdict given up on increments {@code oj_verdict_dead_lettered_total} (alert VerdictDeadLettered)
 * and is republished to {@link KafkaTopics#SUBMISSION_JUDGED_DLQ}, on its partition, for inspection and replay; the
 * container then seeks past it. A {@code submission.started} record given up on is dropped with a WARN: a start is
 * advisory, the verdict or the reconcile job still finishes the submission.
 *
 * <p>The pause runs on the consumer thread before the record is fetched again, so the time between two polls is the
 * listener's run plus at most 30 s: well inside the consumer's {@code max.poll.interval.ms} (default 300 s), so the
 * five minutes of retries never get the consumer evicted from its group.
 */
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    /** 1 s doubling to 30 s, for 5 minutes in all. */
    static ExponentialBackOff verdictBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(300_000L);
        return backOff;
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meters) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.SUBMISSION_JUDGED_DLQ, record.partition()));
        Counter deadLettered = Counter.builder("oj.verdict.dead.lettered")
                .description("Verdicts the consumer gave up on (now on " + KafkaTopics.SUBMISSION_JUDGED_DLQ + ")")
                .register(meters);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            if (KafkaTopics.SUBMISSION_STARTED.equals(record.topic())) {
                log.warn("Dropping submission.started record {}@{} (key {}): {}", record.partition(), record.offset(),
                        record.key(), NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
                return;
            }
            deadLettered.increment();
            deadLetters.accept(record, ex);
        }, verdictBackOff());
        handler.addNotRetryableExceptions(DeserializationException.class, MessageConversionException.class,
                InvalidVerdictException.class);
        return handler;
    }
}
