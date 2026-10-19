package vn.thanhtuanle.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

/**
 * The listeners' failures told apart, end to end on Boot's container factory: a database that is briefly away is
 * waited out until the verdict lands; a verdict no retry can ever apply goes to the dead-letter topic at once, on its
 * partition, and is counted; a start this service cannot read is dropped (a start is advisory), never dead-lettered.
 *
 * <p>On Postgres: a verdict commits together with its outbox row, and H2's schema (built from the entities) holds no
 * outbox payload over 255 bytes.
 */
// bootstrapServersProperty: by default the broker sets spring.kafka.bootstrap-servers as a JVM system property, which
// would point every later test context of the run at it; the address stays in this class's context instead.
@EmbeddedKafka(partitions = 1,
        topics = {KafkaTopics.SUBMISSION_JUDGED, KafkaTopics.SUBMISSION_JUDGED_DLQ, KafkaTopics.SUBMISSION_STARTED},
        bootstrapServersProperty = "spring.embedded.kafka.brokers")
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"})
class VerdictConsumerErrorHandlingTest extends PostgresTest {

    private static final String DEAD_LETTERED = "oj.verdict.dead.lettered";
    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");

    @MockitoSpyBean SubmissionRepository submissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired MeterRegistry meters;

    private DefaultKafkaProducerFactory<String, String> producerFactory;
    private KafkaTemplate<String, String> worker;
    private DefaultKafkaConsumerFactory<String, String> dlqReaders;

    @BeforeEach
    void plainStringProducerAndDlqReader() {
        // What kafka-python sends: UTF-8 key and JSON value, no Spring __TypeId__ header.
        producerFactory = new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer());
        worker = new KafkaTemplate<>(producerFactory);
        dlqReaders = new DefaultKafkaConsumerFactory<>(KafkaTestUtils.consumerProps("dlq-reader", "false", broker),
                new StringDeserializer(), new StringDeserializer());
    }

    @AfterEach
    void closeProducer() {
        producerFactory.destroy();
    }

    private UUID insertPending() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", created_at, updated_at, language_id, problem_id, "
                        + "problem_slug, user_id) VALUES (?, 6, 0, now(), now(), ?, ?, 'dlq', ?)",
                id, C_LANGUAGE_ID, UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private int status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM t_submissions WHERE id = ?", Integer.class, id);
    }

    private static String verdictJson(String submissionId) {
        // What judge-worker sends (Python's json.dumps default separators).
        return "{\"submissionId\": \"" + submissionId + "\", \"status\": 0, \"result\": 0, \"cpu_time\": 5, "
                + "\"real_time\": 7, \"memory\": 1024, \"error_message\": null}";
    }

    private double deadLettered() {
        return meters.counter(DEAD_LETTERED).count();
    }

    /**
     * The partition of each record now on the dead-letter topic with this key, read from the beginning up to the
     * current end.
     */
    private List<Integer> deadLetters(String key) {
        try (Consumer<String, String> reader = dlqReaders.createConsumer()) {
            List<TopicPartition> partitions = reader.partitionsFor(KafkaTopics.SUBMISSION_JUDGED_DLQ).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            reader.assign(partitions);
            reader.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = reader.endOffsets(partitions);
            List<Integer> keyed = new ArrayList<>();
            while (partitions.stream().anyMatch(tp -> reader.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, String> r : reader.poll(Duration.ofMillis(100))) {
                    if (key.equals(r.key())) {
                        keyed.add(r.partition());
                    }
                }
            }
            return keyed;
        }
    }

    @Test
    void aDatabaseBlipIsRetriedNotDeadLettered() {
        UUID id = insertPending();
        double before = deadLettered();
        TransientDataAccessResourceException blip = new TransientDataAccessResourceException("db restarting");
        // The spy of a repository proxy delegates to it; its default answer is that delegation (the real method).
        doThrow(blip).doThrow(blip).doThrow(blip)
                .doAnswer(mockingDetails(submissions).getMockCreationSettings().getDefaultAnswer())
                .when(submissions).applyVerdict(any(), anyInt(), any(), any(), anyInt(), any(), any(), any(), any());

        worker.send(KafkaTopics.SUBMISSION_JUDGED, id.toString(), verdictJson(id.toString()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo(SubmissionResult.ACCEPTED.getValue()));
        verify(submissions, atLeast(4))
                .applyVerdict(any(), anyInt(), any(), any(), anyInt(), any(), any(), any(), any());
        assertThat(deadLetters(id.toString())).isEmpty();
        assertThat(deadLettered()).isEqualTo(before);
    }

    @Test
    void anUnreadableRecordIsDeadLetteredAtOnceAndCounted() {
        String key = "unreadable-" + UUID.randomUUID();
        double before = deadLettered();
        // The last partition: the dead letter must keep it (the DLQ has as many partitions as the source).
        int partition = worker.partitionsFor(KafkaTopics.SUBMISSION_JUDGED).size() - 1;

        worker.send(KafkaTopics.SUBMISSION_JUDGED, partition, key, "not json");

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deadLetters(key)).containsExactly(partition));
        assertThat(deadLettered()).isEqualTo(before + 1);
    }

    @Test
    void aNonUuidSubmissionIdIsDeadLetteredAtOnce() {
        String key = "not-a-uuid-" + UUID.randomUUID();
        double before = deadLettered();

        worker.send(KafkaTopics.SUBMISSION_JUDGED, key, verdictJson(key));

        // Were it retried, 1 s doubling for 5 minutes would hold it far longer than this.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(deadLetters(key)).hasSize(1));
        assertThat(deadLettered()).isEqualTo(before + 1);
    }

    @Test
    void anUnreadableStartIsDroppedNotDeadLettered() {
        UUID id = insertPending();
        double before = deadLettered();

        // Same key, so the same partition: once the start behind it is applied, the poison record has been handled.
        worker.send(KafkaTopics.SUBMISSION_STARTED, id.toString(), "not json");
        worker.send(KafkaTopics.SUBMISSION_STARTED, id.toString(),
                "{\"submissionId\": \"" + id + "\", \"worker\": \"w1\", \"startedAt\": \"2026-10-19T05:00:00.123Z\"}");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(status(id)).isEqualTo(SubmissionResult.JUDGING.getValue()));

        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deadLetters(id.toString())).isEmpty());
        assertThat(deadLettered()).isEqualTo(before);
    }
}
