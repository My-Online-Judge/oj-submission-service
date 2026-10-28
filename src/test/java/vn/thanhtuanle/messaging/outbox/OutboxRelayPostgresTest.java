package vn.thanhtuanle.messaging.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against real Postgres (V17 schema, {@code FOR UPDATE SKIP LOCKED}): every committed row is
 * sent, oldest first, a failure leaves the row for the next pass, and two relays never send the same row.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // each relay pass commits its own transaction
class OutboxRelayPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired OutboxRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void emptyOutbox() {
        repository.deleteAll();
        sent.clear();
    }

    private OutboxRelay relay(OutboxSender sender, int batchSize) {
        OutboxProperties props = new OutboxProperties();
        props.setBatchSize(batchSize);
        props.setRetryBackoffSeconds(0);   // a permanently failing row is tried again by the very next pass
        return new OutboxRelay(repository, sender, transactionManager, props);
    }

    private Map<String, Object> row(String key) {
        return jdbc.queryForMap("SELECT attempts, dead_at, published_at, claimed_until FROM t_outbox WHERE message_key = ?",
                key);
    }

    private void append(String key, int secondsAgo) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> repository.save(OutboxMessage.builder()
                .topic("t").messageKey(key).payload(key.getBytes(StandardCharsets.UTF_8))
                .createdAt(LocalDateTime.now().minusSeconds(secondsAgo)).build()));
    }

    @Test
    void sendsOldestFirstAndMarksEachRowPublished() {
        append("second", 20);
        append("third", 10);
        append("first", 30);

        int count = relay(m -> sent.add(m.getMessageKey()), 100).relayBatch();

        assertThat(count).isEqualTo(3);
        assertThat(sent).containsExactly("first", "second", "third");
        assertThat(repository.countPending()).isZero();
    }

    @Test
    void aFailedSendLeavesThatRowAndTheLaterOnesForTheNextPass() {
        append("a", 30);
        append("b", 20);
        append("c", 10);
        OutboxSender kafkaDownAtB = m -> {
            if (m.getMessageKey().equals("b")) {
                throw new IllegalStateException("broker down");
            }
            sent.add(m.getMessageKey());
        };

        assertThat(relay(kafkaDownAtB, 100).relayBatch()).isEqualTo(1);
        assertThat(repository.countPending()).isEqualTo(2);

        relay(m -> sent.add(m.getMessageKey()), 100).relayBatch();   // Kafka is back
        assertThat(sent).containsExactly("a", "b", "c");
        assertThat(repository.countPending()).isZero();
    }

    @Test
    void twoRelaysNeverSendTheSameRow() throws Exception {
        for (int i = 0; i < 20; i++) {
            append("m" + i, 100 - i);
        }
        CountDownLatch bothSending = new CountDownLatch(2);
        List<Boolean> overlapped = Collections.synchronizedList(new ArrayList<>());
        OutboxSender slow = m -> {
            bothSending.countDown();
            // Holds this relay's row locks until the other relay is sending too. With plain FOR UPDATE the
            // other relay would wait on these locks instead, and this would time out.
            overlapped.add(bothSending.await(5, TimeUnit.SECONDS));
            sent.add(m.getMessageKey());
        };
        ExecutorService twoInstances = Executors.newFixedThreadPool(2);
        Future<Integer> one = twoInstances.submit(() -> relay(slow, 5).relayBatch());
        Future<Integer> two = twoInstances.submit(() -> relay(slow, 5).relayBatch());

        assertThat(one.get(20, TimeUnit.SECONDS) + two.get(20, TimeUnit.SECONDS)).isEqualTo(10);
        assertThat(sent).hasSize(10).doesNotHaveDuplicates();
        assertThat(overlapped).as("both relays held disjoint rows at the same time").containsOnly(true);
        twoInstances.shutdown();
    }

    @Test
    void theCleanUpDeletesOnlyOldPublishedRows() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            repository.save(OutboxMessage.builder().topic("t").messageKey("old").payload(new byte[] {1})
                    .createdAt(LocalDateTime.now().minusDays(9)).publishedAt(LocalDateTime.now().minusDays(8)).build());
            repository.save(OutboxMessage.builder().topic("t").messageKey("recent").payload(new byte[] {1})
                    .createdAt(LocalDateTime.now().minusDays(1)).publishedAt(LocalDateTime.now().minusDays(1)).build());
            repository.save(OutboxMessage.builder().topic("t").messageKey("unsent").payload(new byte[] {1})
                    .createdAt(LocalDateTime.now().minusDays(9)).build());
        });

        relay(m -> { }, 100).deletePublished();

        assertThat(repository.findAll()).extracting(OutboxMessage::getMessageKey)
                .containsExactlyInAnyOrder("recent", "unsent");
    }

    // ---- a row the broker refuses for good must not stop the rows behind it ----------------------------------------

    private static OutboxSender refusesForGood(String key, List<String> sent) {
        return m -> {
            if (m.getMessageKey().equals(key)) {
                throw new java.util.concurrent.ExecutionException(new RecordTooLargeException("record too large"));
            }
            sent.add(m.getMessageKey());
        };
    }

    @Test
    void aRowTheBrokerRefusesForGoodDoesNotBlockTheRowsBehindIt() {
        append("bad", 30);
        append("good", 10);

        int count = relay(refusesForGood("bad", sent), 100).relayBatch();

        assertThat(count).isEqualTo(1);
        assertThat(sent).containsExactly("good");
        assertThat(row("bad").get("attempts")).isEqualTo(1);
        assertThat(row("bad").get("published_at")).isNull();
        assertThat(row("good").get("published_at")).isNotNull();
    }

    @Test
    void aRowThatIsRefusedForGoodFiveTimesIsDeadAndNeverClaimedAgain() {
        append("bad", 30);
        OutboxRelay relay = relay(refusesForGood("bad", sent), 100);

        for (int pass = 0; pass < 5; pass++) {
            relay.relayBatch();
        }

        assertThat(row("bad").get("attempts")).isEqualTo(5);
        assertThat(row("bad").get("dead_at")).isNotNull();
        assertThat(repository.countPending()).as("a dead row is not pending").isZero();
        assertThat(repository.countDead()).isEqualTo(1);
        int attemptsAfterDeath = (int) row("bad").get("attempts");
        relay.relayBatch();
        assertThat(row("bad").get("attempts")).as("a dead row is not tried again").isEqualTo(attemptsAfterDeath);
    }

    @Test
    void aFailureThatMayHealDoesNotCountAnAttempt() {
        append("a", 30);
        OutboxSender kafkaDown = m -> {
            throw new IllegalStateException("broker down");
        };
        OutboxRelay relay = relay(kafkaDown, 100);

        for (int pass = 0; pass < 8; pass++) {
            assertThat(relay.relayBatch()).isZero();
        }

        assertThat(row("a").get("attempts")).isEqualTo(0);
        assertThat(row("a").get("dead_at")).isNull();
        assertThat(row("a").get("claimed_until")).as("released, so the next pass can retry at once").isNull();
        assertThat(relay(m -> sent.add(m.getMessageKey()), 100).relayBatch()).isEqualTo(1);   // Kafka is back
    }

    @Test
    void aRefusedRowWaitsForItsBackoffBeforeItIsTriedAgain() {
        append("bad", 30);
        OutboxProperties props = new OutboxProperties();
        props.setRetryBackoffSeconds(3600);
        OutboxRelay relay = new OutboxRelay(repository, refusesForGood("bad", sent), transactionManager, props);

        relay.relayBatch();
        relay.relayBatch();

        assertThat(row("bad").get("attempts")).as("the second pass found it still backing off").isEqualTo(1);
    }

    // ---- sending does not hold a database transaction ---------------------------------------------------------------

    @Test
    void noDatabaseTransactionIsOpenWhileSending() {
        append("a", 10);
        List<Boolean> inTransaction = Collections.synchronizedList(new ArrayList<>());

        relay(m -> inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()), 100).relayBatch();

        assertThat(inTransaction).containsExactly(false);
    }

    // ---- a lease, so a crashed instance's rows are picked up again --------------------------------------------------

    @Test
    void aRowClaimedByAnInstanceThatDiedIsSentOnceItsLeaseEnds() {
        append("expired", 30);
        append("held", 20);
        jdbc.update("UPDATE t_outbox SET claimed_until = now() - interval '1 second' WHERE message_key = 'expired'");
        jdbc.update("UPDATE t_outbox SET claimed_until = now() + interval '1 hour' WHERE message_key = 'held'");

        relay(m -> sent.add(m.getMessageKey()), 100).relayBatch();

        assertThat(sent).containsExactly("expired");
        assertThat(row("held").get("published_at")).isNull();
    }

    @Test
    void aPassStopsSendingBeforeItsLeaseCouldEndAndGivesTheRestBack() {
        // Lease 16 s, a send may take 15 s: a pass may start a send only in the first second, or another instance could
        // claim the rest while this one still sends it (a duplicate judge request).
        for (int i = 0; i < 4; i++) {
            append("m" + i, 100 - i);
        }
        OutboxProperties props = new OutboxProperties();
        props.setLeaseSeconds(16);
        props.setSendTimeoutSeconds(15);
        OutboxSender slow = m -> {
            Thread.sleep(600);
            sent.add(m.getMessageKey());
        };

        int count = new OutboxRelay(repository, slow, transactionManager, props).relayBatch();

        assertThat(count).isEqualTo(2);                          // t = 0 and t = 0.6 s; at 1.2 s the lease margin is over
        assertThat(sent).containsExactly("m0", "m1");
        assertThat(row("m2").get("claimed_until")).as("given back at once, so the next pass sends it").isNull();
        assertThat(row("m3").get("claimed_until")).isNull();
        assertThat(row("m2").get("attempts")).as("not a refusal").isEqualTo(0);
    }
}
