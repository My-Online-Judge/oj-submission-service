package vn.thanhtuanle.messaging.outbox;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);

    @Test
    void anEmptyOutboxHasNoAge() {
        when(repository.oldestUnpublishedCreatedAt()).thenReturn(null);

        assertThat(OutboxMetrics.oldestAgeSeconds(repository)).isZero();
    }

    @Test
    void theAgeIsThatOfTheOldestUnsentRow() {
        when(repository.oldestUnpublishedCreatedAt()).thenReturn(LocalDateTime.now().minusSeconds(90));

        assertThat(OutboxMetrics.oldestAgeSeconds(repository)).isBetween(89.0, 95.0);
    }

    @Test
    void aFailingQueryReadsAsNoDataNotAsZero() {
        when(repository.countPending()).thenThrow(new IllegalStateException("database down"));

        assertThat(OutboxMetrics.pending(repository)).isNaN();
    }

    @Test
    void deadRowsAreCountedSeparately() {
        when(repository.countDead()).thenReturn(2L);

        assertThat(OutboxMetrics.dead(repository)).isEqualTo(2.0);
    }

    @Test
    void aFailingDeadQueryReadsAsNoData() {
        when(repository.countDead()).thenThrow(new IllegalStateException("database down"));

        assertThat(OutboxMetrics.dead(repository)).isNaN();
    }
}
