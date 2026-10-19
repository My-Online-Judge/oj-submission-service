package vn.thanhtuanle.messaging;

import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The listener end to end, on Boot's container factory and its deserializer settings: the bytes judge-worker puts on
 * {@code submission.started} (plain JSON, no type headers, so the listener's own default type applies), and a record
 * that is not JSON at all, which must not hold up the starts behind it on the same partition.
 */
// bootstrapServersProperty: by default the broker sets spring.kafka.bootstrap-servers as a JVM system property, which
// would point every later test context of the run at it; the address stays in this class's context instead.
@EmbeddedKafka(partitions = 1, topics = {KafkaTopics.SUBMISSION_STARTED, KafkaTopics.SUBMISSION_JUDGED_DLQ},
        bootstrapServersProperty = "spring.embedded.kafka.brokers")
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class SubmissionStartedConsumerKafkaTest extends PostgresTest {

    // Seeded by V2.
    private static final UUID C_LANGUAGE_ID = UUID.fromString("8eb51c84-2d03-4f86-92c3-1f31a671ff12");
    private static final Instant STARTED_AT = Instant.parse("2026-10-19T05:00:00.123Z");

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc;

    private DefaultKafkaProducerFactory<String, String> producerFactory;
    private KafkaTemplate<String, String> worker;

    @BeforeEach
    void plainStringProducer() {
        // What kafka-python sends: UTF-8 key and JSON value, no Spring __TypeId__ header.
        producerFactory = new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer());
        worker = new KafkaTemplate<>(producerFactory);
    }

    @AfterEach
    void closeProducer() {
        producerFactory.destroy();
    }

    private UUID insertPending() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", created_at, updated_at, language_id, problem_id, "
                        + "problem_slug, user_id) VALUES (?, 6, 0, now(), now(), ?, ?, 'started', ?)",
                id, C_LANGUAGE_ID, UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private static String workerJson(UUID id) {
        // Python's json.dumps default separators.
        return "{\"submissionId\": \"" + id + "\", \"worker\": \"w1\", \"startedAt\": \"2026-10-19T05:00:00.123Z\"}";
    }

    private void awaitJudging(UUID id) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM t_submissions WHERE id = ?", Integer.class, id))
                    .isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT judging_started_at FROM t_submissions WHERE id = ?",
                    LocalDateTime.class, id)).isEqualTo(LocalDateTime.ofInstant(STARTED_AT, ZoneId.systemDefault()));
        });
    }

    @Test
    void aStartTheWorkerSendsMovesTheRowToJudging() {
        UUID id = insertPending();

        worker.send(KafkaTopics.SUBMISSION_STARTED, id.toString(), workerJson(id));

        awaitJudging(id);
    }

    @Test
    void aRecordThatIsNotJsonDoesNotHoldUpTheNextStart() {
        UUID id = insertPending();

        // Same key, so the same partition: the start can only be applied once the poison record is past.
        worker.send(KafkaTopics.SUBMISSION_STARTED, id.toString(), "not json");
        worker.send(KafkaTopics.SUBMISSION_STARTED, id.toString(), workerJson(id));

        awaitJudging(id);
    }
}
