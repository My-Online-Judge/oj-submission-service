package vn.thanhtuanle.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import vn.thanhtuanle.messaging.KafkaTopics;
import vn.thanhtuanle.oj.common.event.OjTopics;

/**
 * Declares the submission topics explicitly so their partition count is deterministic instead of
 * left to broker auto-creation — which yields a single partition and serializes all judging
 * through one worker no matter how many are running.
 *
 * <p>Spring Boot's auto-configured {@code KafkaAdmin} creates these topics on startup and, because
 * the declared partition count exceeds the current one, raises the partition count of the existing
 * (previously auto-created, 1-partition) topics as well.
 *
 * <p>Producers key records by {@code submissionId}, so per-submission ordering is preserved within
 * a partition regardless of how many partitions the topic has.
 */
@Configuration
public class KafkaTopicConfig {

    // Default 6 partitions; override with KAFKA_PARTITIONS. RF=1 (single-broker dev / self-host).
    @Value("${KAFKA_PARTITIONS:6}")
    private int partitions;

    @Bean
    public NewTopic submissionRequestedTopic() {
        return TopicBuilder.name(KafkaTopics.SUBMISSION_REQUESTED)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic submissionJudgedTopic() {
        return TopicBuilder.name(KafkaTopics.SUBMISSION_JUDGED)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    // Facts about submissions for other services (problem-service counts verdicts from it), keyed by problem id.
    @Bean
    public NewTopic submissionEventsTopic() {
        return TopicBuilder.name(OjTopics.SUBMISSION_EVENTS)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    // judge-worker's judging starts, keyed by submissionId.
    @Bean
    public NewTopic submissionStartedTopic() {
        return TopicBuilder.name(KafkaTopics.SUBMISSION_STARTED)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic submissionJudgedDlqTopic() {
        return TopicBuilder.name(KafkaTopics.SUBMISSION_JUDGED_DLQ)
                .partitions(partitions)
                .replicas(1)
                .build();
    }
}
