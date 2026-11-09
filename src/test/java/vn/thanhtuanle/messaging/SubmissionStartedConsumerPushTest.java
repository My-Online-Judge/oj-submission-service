package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.messaging.event.SubmissionStartedEvent;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The start reaches the SSE subscribers exactly when it moved the row (the database tests cover when that is). */
@ExtendWith(MockitoExtension.class)
class SubmissionStartedConsumerPushTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock JudgingPubSub judgingPubSub;

    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

    private SubmissionStartedConsumer consumer() {
        return new SubmissionStartedConsumer(submissionRepository, judgingPubSub);
    }

    @Test
    void anAppliedStartIsBroadcastAfterTheCommit() {
        UUID id = UUID.randomUUID();
        when(submissionRepository.applyStartedOfGeneration(eq(id), any(LocalDateTime.class), any(LocalDateTime.class), eq(1)))
                .thenReturn(1);

        consumer().onStarted(new SubmissionStartedEvent(id.toString(), "w1", LONG_AGO, 1));

        verify(judgingPubSub).publishStartedAfterCommit(id.toString());
    }

    @Test
    void aStartThatChangedNothingIsNotBroadcast() {
        UUID id = UUID.randomUUID();
        when(submissionRepository.applyStarted(eq(id), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);

        consumer().onStarted(new SubmissionStartedEvent(id.toString(), "w1", LONG_AGO, null));

        verifyNoInteractions(judgingPubSub);
    }

    @Test
    void aStartThisServiceCannotUseIsNotBroadcast() {
        consumer().onStarted(new SubmissionStartedEvent("not-a-uuid", "w1", LONG_AGO, null));
        consumer().onStarted(new SubmissionStartedEvent(UUID.randomUUID().toString(), "w1", null, null));

        verifyNoInteractions(judgingPubSub, submissionRepository);
    }
}
