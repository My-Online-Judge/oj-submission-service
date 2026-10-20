package vn.thanhtuanle.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
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
 * <p>Failures are told apart. A record no retry can ever apply (not readable, a tombstone, a verdict for a
 * {@code submissionId} that is missing or not a UUID, one the database rejects as a constraint violation) is given up
 * on at once, so one poison record cannot stall its partition. Anything else (the database restarting, a lock) is
 * retried with a growing pause, {@link #verdictBackOff()}: a brief outage must not lose a verdict. A verdict given up
 * on is republished to {@link KafkaTopics#SUBMISSION_JUDGED_DLQ}, on its partition, for inspection and replay, and
 * then increments {@code oj_verdict_dead_lettered_total} (alert VerdictDeadLettered); the container seeks past it.
 * A {@code submission.started} record given up on is dropped with a WARN: a start is advisory, the verdict or the
 * reconcile job still finishes the submission.
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

    /** Drops a {@code submission.started} record; dead-letters any other record and counts it once published. */
    static ConsumerRecordRecoverer verdictRecoverer(ConsumerRecordRecoverer deadLetters, Counter deadLettered) {
        return (record, ex) -> {
            if (KafkaTopics.SUBMISSION_STARTED.equals(record.topic())) {
                log.warn("Dropping submission.started record {}@{} (key {}): {}", record.partition(), record.offset(),
                        record.key(), NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
                return;
            }
            // Counted once published: a failed publish throws, and the record is delivered and recovered again.
            deadLetters.accept(record, ex);
            deadLettered.increment();
        };
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meters) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.SUBMISSION_JUDGED_DLQ, record.partition()));
        Counter deadLettered = Counter.builder("oj.verdict.dead.lettered")
                .description("Verdicts the consumer gave up on (now on " + KafkaTopics.SUBMISSION_JUDGED_DLQ + ")")
                .register(meters);
        DefaultErrorHandler handler = new DefaultErrorHandler(verdictRecoverer(deadLetters, deadLettered),
                verdictBackOff());
        // Permanent: the same record fails the same way every time, so retrying it would only hold every verdict
        // behind it on the one listener thread for 5 minutes. NullPointerException is a backstop for a field a record
        // lacks. Transient database failures (connection, lock, timeout) stay retryable.
        handler.addNotRetryableExceptions(DeserializationException.class, MessageConversionException.class,
                InvalidVerdictException.class, DataIntegrityViolationException.class, NullPointerException.class);
        return handler;
    }
}
