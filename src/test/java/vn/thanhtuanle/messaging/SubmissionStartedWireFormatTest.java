package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;
import vn.thanhtuanle.messaging.event.SubmissionStartedEvent;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge-worker writes {@code submission.started} as plain JSON (no Spring type headers), with a UTC instant. The
 * listener's {@code JsonDeserializer} reads it with Spring Kafka's own mapper, which must take the instant as it is
 * and skip fields this service does not know.
 */
class SubmissionStartedWireFormatTest {

    @Test
    void theWorkersStartReadsIntoTheEvent() throws Exception {
        String json = "{\"submissionId\":\"0b6f0a4e-9a4e-4c47-9d0e-5f3c2b1a0d9e\",\"worker\":\"w1\","
                + "\"startedAt\":\"2026-10-19T05:00:00.123Z\",\"extra\":1}";

        SubmissionStartedEvent event = JacksonUtils.enhancedObjectMapper().readValue(json, SubmissionStartedEvent.class);

        assertThat(event.submissionId()).isEqualTo("0b6f0a4e-9a4e-4c47-9d0e-5f3c2b1a0d9e");
        assertThat(event.worker()).isEqualTo("w1");
        assertThat(event.startedAt()).isEqualTo(Instant.parse("2026-10-19T05:00:00.123Z"));
        assertThat(event.rejudgeCount()).as("an old worker sends no generation").isNull();
    }

    @Test
    void theGenerationTheWorkerEchoesIsRead() throws Exception {
        String json = "{\"submissionId\":\"0b6f0a4e-9a4e-4c47-9d0e-5f3c2b1a0d9e\",\"worker\":\"w1\","
                + "\"startedAt\":\"2026-10-19T05:00:00.123Z\",\"rejudgeCount\":3}";

        SubmissionStartedEvent event = JacksonUtils.enhancedObjectMapper().readValue(json, SubmissionStartedEvent.class);

        assertThat(event.rejudgeCount()).isEqualTo(3);
    }
}
