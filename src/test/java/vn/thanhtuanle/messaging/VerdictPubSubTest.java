package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.submission.VerdictPush;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerdictPubSubTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock VerdictPush verdictPush;
    // Configured like Spring Boot's ObjectMapper (unknown properties ignored), which the service uses.
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private VerdictPubSub pubSub() {
        return new VerdictPubSub(redisTemplate, objectMapper, verdictPush);
    }

    private String sentBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(VerdictPubSub.CHANNEL), body.capture());
        return body.getValue();
    }

    private static Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    @Test
    void publish_sendsOnlyTheIdToTheChannel() throws Exception {
        pubSub().publish("sub-1");

        assertThat(objectMapper.readTree(sentBody())).isEqualTo(objectMapper.readTree("{\"submissionId\":\"sub-1\"}"));
    }

    @Test
    void onMessage_handsTheIdToVerdictPush() {
        pubSub().onMessage(message("{\"submissionId\":\"sub-2\"}"), null);

        verify(verdictPush).pushIfWatched("sub-2");
    }

    @Test
    void onMessage_acceptsTheOldFormat() {
        // An instance not yet redeployed still sends {submissionId, payload}: the id is enough.
        pubSub().onMessage(message("{\"submissionId\":\"sub-6\",\"payload\":{\"status\":0}}"), null);

        verify(verdictPush).pushIfWatched("sub-6");
    }

    @Test
    void onMessage_anUnreadableBodyIsLoggedNotThrown() {
        assertThatCode(() -> pubSub().onMessage(message("not json"), null)).doesNotThrowAnyException();
        verifyNoInteractions(verdictPush);
    }

    @Test
    void publishAfterCommit_withActiveTransaction_defersUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            pubSub().publishAfterCommit("sub-4");

            // The transaction has not committed: nothing may reach the wire yet.
            verify(redisTemplate, never()).convertAndSend(any(), any());

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(sentBody()).contains("sub-4");
    }

    @Test
    void publishAfterCommit_withoutTransaction_publishesImmediately() {
        pubSub().publishAfterCommit("sub-5");

        assertThat(sentBody()).contains("sub-5");
    }

    @Test
    void publishOutput_roundTripsThroughOnMessage() {
        pubSub().publish("sub-3");

        pubSub().onMessage(message(sentBody()), null);

        verify(verdictPush).pushIfWatched("sub-3");
    }
}
