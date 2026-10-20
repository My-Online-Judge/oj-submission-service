package vn.thanhtuanle.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OjMetricsQueueDepthTest {

    /** PENDING 6 and JUDGING 7: the statuses that still wait for the judge. */
    private static final List<Integer> QUEUED = List.of(6, 7);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SubmissionRepository repo = mock(SubmissionRepository.class);
    private final OjMetrics metrics = new OjMetrics(registry, repo);

    private double gauge() {
        return registry.get("oj.queue.depth").gauge().value();
    }

    @Test
    void theGaugeReadsTheCachedCount() {
        when(repo.countByStatusIn(QUEUED)).thenReturn(4L);

        assertThat(gauge()).as("nothing is counted before the first refresh").isZero();
        metrics.refreshQueueDepth();
        assertThat(gauge()).isEqualTo(4.0);

        when(repo.countByStatusIn(QUEUED)).thenReturn(9L);
        assertThat(gauge()).as("a scrape does not query the database").isEqualTo(4.0);
        metrics.refreshQueueDepth();
        assertThat(gauge()).isEqualTo(9.0);
    }

    @Test
    void aFailedRefreshKeepsTheLastCount() {
        when(repo.countByStatusIn(QUEUED)).thenReturn(4L);
        metrics.refreshQueueDepth();

        when(repo.countByStatusIn(QUEUED)).thenThrow(new DataAccessResourceFailureException("db down"));
        metrics.refreshQueueDepth();

        assertThat(metrics.queueDepth()).isEqualTo(4);
        assertThat(gauge()).isEqualTo(4.0);
    }

    @Test
    void reservationsStopAtTheLimit() {
        when(repo.countByStatusIn(QUEUED)).thenReturn(498L);
        metrics.refreshQueueDepth();

        assertThat(metrics.tryReserveQueueSlots(2, 500)).isTrue();
        assertThat(metrics.tryReserveQueueSlots(1, 500)).isFalse();
        assertThat(metrics.queueDepth()).isEqualTo(500);
    }

    @Test
    void releasedSlotsGoBackButTheDepthNeverGoesBelowZero() {
        when(repo.countByStatusIn(QUEUED)).thenReturn(499L);
        metrics.refreshQueueDepth();
        metrics.tryReserveQueueSlots(1, 500);

        metrics.releaseQueueSlots(1);
        assertThat(metrics.queueDepth()).isEqualTo(499);

        // A refresh already dropped the reservation (its row was never committed): the release must not go negative.
        when(repo.countByStatusIn(QUEUED)).thenReturn(0L);
        metrics.refreshQueueDepth();
        metrics.releaseQueueSlots(1);
        assertThat(metrics.queueDepth()).isZero();
    }

    @Test
    void theNextRefreshOverwritesTheReservations() {
        when(repo.countByStatusIn(QUEUED)).thenReturn(10L);
        metrics.refreshQueueDepth();
        metrics.tryReserveQueueSlots(5, 500);

        when(repo.countByStatusIn(QUEUED)).thenReturn(12L);
        metrics.refreshQueueDepth();

        assertThat(metrics.queueDepth()).isEqualTo(12);
    }

    /**
     * Review Focus: every submit inside one refresh window reads the same stale depth. A check-then-add lets them all
     * past the limit; the reservation is one compare-and-set. Repeated so that a racy implementation loses at least once.
     */
    @Test
    void aBurstBetweenRefreshesCannotOvershoot() throws Exception {
        when(repo.countByStatusIn(QUEUED)).thenReturn(495L);
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 200; round++) {
                metrics.refreshQueueDepth();
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return metrics.tryReserveQueueSlots(1, 500);
                    }));
                }
                int admitted = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(5, TimeUnit.SECONDS)) {
                        admitted++;
                    }
                }

                assertThat(admitted).as("round %d", round).isEqualTo(5);
                assertThat(metrics.queueDepth()).as("round %d", round).isEqualTo(500);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
