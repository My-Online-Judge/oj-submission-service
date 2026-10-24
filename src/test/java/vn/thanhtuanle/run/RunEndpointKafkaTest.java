package vn.thanhtuanle.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.thanhtuanle.messaging.KafkaTopics;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The run endpoint end to end over Kafka (Run code design §5–§7): what goes out on {@code run.requested}, and the HTTP
 * answer built from a reply on {@code run.judged} sent the way judge-worker sends it (plain JSON, no type headers).
 * No Redis in this context: the Run cooldown fails open, as the submit one does.
 */
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {KafkaTopics.RUN_REQUESTED, KafkaTopics.RUN_JUDGED},
        bootstrapServersProperty = "spring.embedded.kafka.brokers")
@TestPropertySource(properties = {"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "oj.run.timeout=3s"})
class RunEndpointKafkaTest extends PostgresTest {

    private static final String BODY = """
            {"sourceCode": "print(input())", "languageIdentifier": "python3", "problemSlug": "a-plus-b",
             "cases": [{"input": "1 2\\n", "expectedOutput": "3\\n"}, {"input": "5\\r\\n"}]}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ProblemCatalog problemCatalog;

    private DefaultKafkaProducerFactory<String, String> producerFactory;
    private KafkaTemplate<String, String> worker;
    private KafkaConsumer<String, String> requested;

    @BeforeEach
    void fakeWorker() {
        when(problemCatalog.judgeSpec(eq("a-plus-b"), anyBoolean(), isNull()))
                .thenReturn(new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "v1"));
        // What kafka-python sends: UTF-8 key and JSON value, no Spring __TypeId__ header.
        producerFactory = new DefaultKafkaProducerFactory<>(KafkaTestUtils.producerProps(broker),
                new StringSerializer(), new StringSerializer());
        worker = new KafkaTemplate<>(producerFactory);
        // What the worker reads: subscribed before the request, at the end of the topic, so a test sees only the
        // request it makes (the topic keeps the earlier tests' requests).
        Map<String, Object> props = KafkaTestUtils.consumerProps("run-requested-probe-" + UUID.randomUUID(), "false",
                broker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        requested = new KafkaConsumer<>(props);
        broker.consumeFromEmbeddedTopics(requested, true, KafkaTopics.RUN_REQUESTED);
    }

    @AfterEach
    void closeFakeWorker() {
        requested.close();
        producerFactory.destroy();
    }

    private MockHttpServletRequestBuilder run(UUID userId) {
        return post("/api/v1/submissions/run")
                .with(jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    private ConsumerRecord<String, String> nextRequest() {
        return KafkaTestUtils.getSingleRecord(requested, KafkaTopics.RUN_REQUESTED, Duration.ofSeconds(30));
    }

    @Test
    void aRunIsAnsweredFromTheWorkersReply() throws Exception {
        MvcResult started = mockMvc.perform(run(UUID.randomUUID()))
                .andExpect(request().asyncStarted()).andReturn();

        ConsumerRecord<String, String> record = nextRequest();
        JsonNode body = objectMapper.readTree(record.value());
        assertThat(body.get("runId").asText()).isEqualTo(record.key());
        assertThat(body.get("test_case").get(1).get("input").asText()).isEqualTo("5\n");   // CR LF normalised
        assertThat(body.get("test_case").get(1).get("output").asText()).isEmpty();
        assertThat(body.has("test_case_id")).isFalse();
        assertThat(body.has("submissionId")).isFalse();
        // No traceparent assertion: the OTel Java agent is not attached in tests; the live stack shows the trace.

        worker.send(KafkaTopics.RUN_JUDGED, record.key(), "{\"runId\": \"" + record.key() + "\", \"status\": -1, "
                + "\"result\": -1, \"cpu_time\": 2, \"real_time\": 3, \"memory\": 1024, \"error_message\": null, "
                + "\"details\": [{\"result\": 0, \"cpu_time\": 2, \"memory\": 1024, \"output\": \"3\\n\", "
                + "\"test_case\": \"1\", \"error\": 0}, {\"result\": -1, \"cpu_time\": 1, \"memory\": 1024, "
                + "\"output\": \"5\\n\", \"test_case\": \"2\", \"error\": 0}]}");

        started.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").doesNotExist())
                .andExpect(jsonPath("$.data.cases[0].result").value(0))
                .andExpect(jsonPath("$.data.cases[0].output").value("3\n"))
                .andExpect(jsonPath("$.data.cases[1].result").value(0))      // no expected output: WA → ran
                .andExpect(jsonPath("$.data.cases[1].output").value("5\n"));
    }

    @Test
    void aRunNobodyAnswersIs504AndFreesTheSlot() throws Exception {
        UUID user = UUID.randomUUID();
        MvcResult started = mockMvc.perform(run(user)).andExpect(request().asyncStarted()).andReturn();
        nextRequest();   // published, nobody replies

        // MockMvc has no container timeout thread: fire the async timeout the way the servlet container does once
        // the DeferredResult's wait (oj.run.timeout, 3 s in this class) is over.
        MockAsyncContext async = (MockAsyncContext) started.getRequest().getAsyncContext();
        assertThat(async.getTimeout()).isEqualTo(3_000L);
        for (AsyncListener listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
        }

        started.getAsyncResult(10_000);
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("RUN_TIMED_OUT"));

        // The slot is free: the same user may run again (no 429 RUN_IN_PROGRESS).
        mockMvc.perform(run(user)).andExpect(request().asyncStarted());
    }

    @Test
    void aCompileErrorIsAWholeRunAnswer() throws Exception {
        MvcResult started = mockMvc.perform(run(UUID.randomUUID()))
                .andExpect(request().asyncStarted()).andReturn();
        ConsumerRecord<String, String> record = nextRequest();

        worker.send(KafkaTopics.RUN_JUDGED, record.key(), "{\"runId\": \"" + record.key() + "\", \"status\": -2, "
                + "\"result\": null, \"cpu_time\": 0, \"real_time\": 0, \"memory\": 0, "
                + "\"error_message\": \"CompileError: x\", \"details\": []}");

        started.getAsyncResult(30_000);
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value(-2))
                .andExpect(jsonPath("$.data.errorMessage").value("CompileError: x"))
                .andExpect(jsonPath("$.data.cases").isEmpty());
    }
}
