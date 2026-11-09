package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.messaging.event.SubmissionProgressEvent;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SubmissionProgressConsumerTest {

    @Mock JudgingPubSub judgingPubSub;

    private SubmissionProgressConsumer consumer() {
        return new SubmissionProgressConsumer(judgingPubSub);
    }

    @Test
    void aProgressRecordIsForwardedAsItIs() {
        SubmissionProgressEvent event = new SubmissionProgressEvent(UUID.randomUUID().toString(), "w1", "3", 0, 0,
                12L, 15L, 4096L, 10, null);

        consumer().onProgress(event);

        verify(judgingPubSub).publishProgress(event);
    }

    @Test
    void aRecordThisServiceCannotUseIsSkippedWithoutThrowing() {
        String id = UUID.randomUUID().toString();
        for (SubmissionProgressEvent bad : new SubmissionProgressEvent[] {
                null,
                new SubmissionProgressEvent(null, "w1", "3", 0, 0, 1L, 1L, 1L, 10, null),
                new SubmissionProgressEvent("not-a-uuid", "w1", "3", 0, 0, 1L, 1L, 1L, 10, null),
                new SubmissionProgressEvent(id, "w1", null, 0, 0, 1L, 1L, 1L, 10, null),
                new SubmissionProgressEvent(id, "w1", "3", null, 0, 1L, 1L, 1L, 10, null)}) {
            assertThatCode(() -> consumer().onProgress(bad)).doesNotThrowAnyException();
        }
        verifyNoInteractions(judgingPubSub);
    }
}
