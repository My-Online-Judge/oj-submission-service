package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.submission.problem.JudgeSpec;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge-worker reads {@code submission.requested} and is deployed separately: moving the producer from
 * Spring Kafka's {@code JsonSerializer} to the outbox (Boot's {@code ObjectMapper}) must not change a byte.
 */
@JsonTest
@ActiveProfiles("test")
class SubmissionRequestedWireFormatTest {

    @Autowired ObjectMapper objectMapper;   // the mapper OutboxWriter serializes with

    private static SubmissionRequestedEvent cJudgeRequest() {
        Language c = new Language();
        c.setIdentifier("c");
        c.setSrcName("main.c");
        c.setExeName("main");
        c.setCompileMaxMemory(268435456L);
        c.setCompileCommand("/usr/bin/gcc -DONLINE_JUDGE -O2 -w -fmax-errors=3 -std=c11 {src_path} -lm -o {exe_path}");
        c.setRunCommand("{exe_path}");
        c.setSeccompRule("c_cpp");
        JudgeSpec spec = new JudgeSpec(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "simple-a-plus-b", 1000, 256L, "8a0102cd409f");
        return new JudgeService().buildRequestedEvent("00000000-0000-0000-0000-0000000000aa",
                "#include <stdio.h>\nint main(){return 0;}\n", spec, c);
    }

    private String outbox(SubmissionRequestedEvent event) throws Exception {
        return new String(objectMapper.writeValueAsBytes(event), StandardCharsets.UTF_8);
    }

    @Test
    void theOutboxWritesExactlyWhatJsonSerializerWrote() throws Exception {
        SubmissionRequestedEvent sparse = SubmissionRequestedEvent.builder().submissionId("x").build();
        for (SubmissionRequestedEvent event : new SubmissionRequestedEvent[] {cJudgeRequest(), sparse}) {
            try (JsonSerializer<SubmissionRequestedEvent> before = new JsonSerializer<>()) {
                assertThat(outbox(event))
                        .isEqualTo(new String(before.serialize(KafkaTopics.SUBMISSION_REQUESTED, event), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void theWireFormatIsPinned() throws Exception {
        assertThat(outbox(cJudgeRequest())).isEqualTo("{\"submissionId\":\"00000000-0000-0000-0000-0000000000aa\","
                + "\"src\":\"#include <stdio.h>\\nint main(){return 0;}\\n\",\"output\":true,"
                + "\"language_config\":{\"compile\":{\"env\":[],\"src_name\":\"main.c\",\"exe_name\":\"main\","
                + "\"max_cpu_time\":3000,\"max_real_time\":5000,\"max_memory\":268435456,"
                + "\"compile_command\":\"/usr/bin/gcc -DONLINE_JUDGE -O2 -w -fmax-errors=3 -std=c11 {src_path} -lm -o {exe_path}\"},"
                + "\"run\":{\"command\":\"{exe_path}\",\"env\":[\"LANG=en_US.UTF-8\",\"LANGUAGE=en_US:en\",\"LC_ALL=en_US.UTF-8\"],"
                + "\"seccomp_rule\":\"c_cpp\",\"memory_limit_check_only\":0}},"
                + "\"max_cpu_time\":1000,\"max_memory\":268435456,\"test_case_id\":\"simple-a-plus-b__8a0102cd409f\"}");
    }
}
