package vn.thanhtuanle.messaging.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A problem rejudge commits up to 500 judge requests and then calls {@link OutboxRelay#relaySoon} once per request.
 * Each call used to queue a whole relay pass on the single relay thread, so 500 passes ran where one or two would do.
 * At most one pass now waits in the queue; it starts by clearing its flag, so a row committed while a pass runs still
 * gets the next one.
 */
class OutboxRelayCoalesceTest {

    private final List<OutboxMessage> rows = IntStream.range(0, 3)
            .mapToObj(i -> OutboxMessage.builder().topic("t").messageKey("m" + i)
                    .payload(("m" + i).getBytes(StandardCharsets.UTF_8)).createdAt(LocalDateTime.now()).build())
            .toList();

    private List<OutboxMessage> unpublished(int limit) {
        return rows.stream().filter(m -> m.getPublishedAt() == null).limit(limit).toList();
    }

    @Test
    void fiveHundredRelaySoonCallsQueueAtMostTwoPasses() throws Exception {
        // Fewer rows than a batch: each pass locks once, so the lock calls count the passes.
        AtomicInteger passes = new AtomicInteger();
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.lockOldestUnpublished(anyInt())).thenAnswer(call -> {
            passes.incrementAndGet();
            return unpublished(call.getArgument(0));
        });
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        OutboxSender blocksOnTheFirstSend = message -> {
            if (first.getAndSet(false)) {
                sending.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
        };
        OutboxRelay relay = new OutboxRelay(repository, blocksOnTheFirstSend, mock(PlatformTransactionManager.class),
                new OutboxProperties());
        try {
            relay.relaySoon();
            assertThat(sending.await(5, TimeUnit.SECONDS)).as("the first pass is sending").isTrue();

            for (int i = 0; i < 500; i++) {
                relay.relaySoon();
            }
            release.countDown();

            await().atMost(Duration.ofSeconds(10))
                    .until(() -> rows.stream().allMatch(m -> m.getPublishedAt() != null));
            // Holds for a while after the last row went out: no further pass was waiting.
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(passes.get()).as("relay passes").isLessThanOrEqualTo(2));
            assertThat(passes.get()).isBetween(1, 2);
        } finally {
            relay.stop();
        }
    }
}
