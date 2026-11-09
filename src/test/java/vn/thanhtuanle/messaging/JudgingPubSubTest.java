package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import vn.thanhtuanle.messaging.event.SubmissionProgressEvent;
import vn.thanhtuanle.submission.SubmissionSseRegistry;

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
class JudgingPubSubTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock SubmissionSseRegistry sseRegistry;
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private JudgingPubSub pubSub() {
        return new JudgingPubSub(redisTemplate, objectMapper, sseRegistry, registry);
    }

    private static SubmissionProgressEvent progress() {
        return new SubmissionProgressEvent("sub-1", "w1", "7", -1, 0, 21L, 25L, 4096L, 12, 2);
    }

    private String sentBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq(JudgingPubSub.CHANNEL), body.capture());
        return body.getValue();
    }

    private static Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    @Test
    void publishProgress_sendsTheCaseWithoutTheWorkerOrTheGeneration() throws Exception {
        pubSub().publishProgress(progress());

        JsonNode sent = objectMapper.readTree(sentBody());
        assertThat(sent.get("submissionId").asText()).isEqualTo("sub-1");
        assertThat(sent.get("event").asText()).isEqualTo("progress");
        assertThat(sent.get("data")).isEqualTo(objectMapper.readTree(
                "{\"testCase\":\"7\",\"result\":-1,\"error\":0,\"cpuTime\":21,\"realTime\":25,\"memory\":4096,\"total\":12}"));
    }

    @Test
    void publishStartedAfterCommit_sendsJudgingOnceTheTransactionCommits() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            pubSub().publishStartedAfterCommit("sub-2");
            verify(redisTemplate, never()).convertAndSend(any(), any());
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        JsonNode sent = objectMapper.readTree(sentBody());
        assertThat(sent.get("event").asText()).isEqualTo("started");
        assertThat(sent.get("data")).isEqualTo(objectMapper.readTree("{\"status\":7}"));
    }

    @Test
    void publishStartedAfterCommit_withoutATransactionPublishesAtOnce() {
        pubSub().publishStartedAfterCommit("sub-3");

        assertThat(sentBody()).contains("sub-3").contains("started");
    }

    @Test
    void publish_aRedisFailureIsCountedAsAFallbackNotThrown() {
        when(redisTemplate.convertAndSend(eq(JudgingPubSub.CHANNEL), any())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> pubSub().publishProgress(progress())).doesNotThrowAnyException();

        assertThat(registry.counter("oj.redis.fallback", "lookup", "judging_publish").count()).isEqualTo(1);
    }

    @Test
    void onMessage_handsTheEventToTheRegistryWithoutCompletingTheStream() {
        pubSub().onMessage(message("{\"submissionId\":\"s1\",\"event\":\"progress\",\"data\":{\"testCase\":\"1\"}}"), null);

        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(sseRegistry).send(eq("s1"), eq("progress"), data.capture());
        assertThat(((JsonNode) data.getValue()).get("testCase").asText()).isEqualTo("1");
        verify(sseRegistry, never()).complete(any(), any());
    }

    @Test
    void publishOutput_roundTripsThroughOnMessage() {
        pubSub().publishProgress(progress());

        pubSub().onMessage(message(sentBody()), null);

        verify(sseRegistry).send(eq("sub-1"), eq("progress"), any());
    }

    @Test
    void onMessage_anUnreadableOrIncompleteBodyIsLoggedNotThrown() {
        assertThatCode(() -> pubSub().onMessage(message("not json"), null)).doesNotThrowAnyException();
        assertThatCode(() -> pubSub().onMessage(message("{\"submissionId\":\"s1\"}"), null)).doesNotThrowAnyException();
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void aMessageWithUnknownFieldsIsStillHandled() {
        new JudgingPubSub(redisTemplate, new ObjectMapper(), sseRegistry, registry)
                .onMessage(message("{\"submissionId\":\"s1\",\"event\":\"started\",\"data\":{},\"extra\":1}"), null);

        verify(sseRegistry).send(eq("s1"), eq("started"), any());
    }
}
