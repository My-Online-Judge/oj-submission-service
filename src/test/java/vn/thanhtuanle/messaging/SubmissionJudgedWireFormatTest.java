package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge-worker echoes the request's {@code rejudgeCount} into its verdict, under that exact key. A misspelt key would
 * read as null and silently turn the generation guard off, so the key is pinned here with Spring Kafka's own mapper.
 */
class SubmissionJudgedWireFormatTest {

    private static final String VERDICT = "{\"submissionId\":\"0b6f0a4e-9a4e-4c47-9d0e-5f3c2b1a0d9e\",\"status\":0,"
            + "\"result\":0,\"cpu_time\":1,\"real_time\":2,\"memory\":3,\"error_message\":null,\"details\":[]";

    @Test
    void theGenerationTheWorkerEchoesIsRead() throws Exception {
        SubmissionJudgedEvent event = JacksonUtils.enhancedObjectMapper()
                .readValue(VERDICT + ",\"rejudgeCount\":3}", SubmissionJudgedEvent.class);

        assertThat(event.getRejudgeCount()).isEqualTo(3);
    }

    @Test
    void anOldWorkersVerdictHasNoGeneration() throws Exception {
        SubmissionJudgedEvent event = JacksonUtils.enhancedObjectMapper()
                .readValue(VERDICT + "}", SubmissionJudgedEvent.class);

        assertThat(event.getRejudgeCount()).isNull();
    }

    @Test
    void theWorkerThatJudgedItIsRead() throws Exception {
        SubmissionJudgedEvent event = JacksonUtils.enhancedObjectMapper()
                .readValue(VERDICT + ",\"judgedBy\":\"judge-7c9f-abcde\"}", SubmissionJudgedEvent.class);

        assertThat(event.getJudgedBy()).isEqualTo("judge-7c9f-abcde");
    }

    @Test
    void anOldWorkersVerdictNamesNoWorker() throws Exception {
        SubmissionJudgedEvent event = JacksonUtils.enhancedObjectMapper()
                .readValue(VERDICT + "}", SubmissionJudgedEvent.class);

        assertThat(event.getJudgedBy()).isNull();
    }

    /** Spring Kafka's mapper tolerates unknown fields; the event itself must too, as RunJudgedEvent does. */
    @Test
    void aFieldAFutureWorkerAddsDoesNotBreakTheEventWithAStrictMapper() throws Exception {
        SubmissionJudgedEvent event = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(VERDICT + ",\"aFutureField\":1}", SubmissionJudgedEvent.class);

        assertThat(event.getSubmissionId()).isEqualTo("0b6f0a4e-9a4e-4c47-9d0e-5f3c2b1a0d9e");
    }
}
