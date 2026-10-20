package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final JudgeSpec SPEC = new JudgeSpec(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "simple-a-plus-b", 1000, 256L, "8a0102cd409f");

    private static SubmissionRequestedEvent cJudgeRequest() {
        return new JudgeService().buildRequestedEvent("00000000-0000-0000-0000-0000000000aa",
                "#include <stdio.h>\nint main(){return 0;}\n", SPEC, c(), 0);
    }

    private static Language c() {
        Language c = new Language();
        c.setIdentifier("c");
        c.setSrcName("main.c");
        c.setExeName("main");
        c.setCompileMaxMemory(268435456L);
        c.setCompileCommand("/usr/bin/gcc -DONLINE_JUDGE -O2 -w -fmax-errors=3 -std=c11 {src_path} -lm -o {exe_path}");
        c.setRunCommand("{exe_path}");
        c.setSeccompRule("c_cpp");
        return c;
    }

    /** The cpp row as V2 + V3 leave it: the language every checker is compiled with. */
    private static Language cpp() {
        Language cpp = new Language();
        cpp.setIdentifier("cpp");
        cpp.setSrcName("main.cpp");
        cpp.setExeName("main");
        cpp.setCompileMaxMemory(1073741824L);
        cpp.setCompileCommand("/usr/bin/g++ -DONLINE_JUDGE -O2 -w -fmax-errors=3 -std=c++17 {src_path} -lm -o {exe_path}");
        cpp.setRunCommand("{exe_path}");
        cpp.setSeccompRule("c_cpp");
        return cpp;
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
                + "\"src\":\"#include <stdio.h>\\nint main(){return 0;}\\n\",\"output\":true,\"rejudgeCount\":0,"
                + "\"language_config\":{\"compile\":{\"env\":[],\"src_name\":\"main.c\",\"exe_name\":\"main\","
                + "\"max_cpu_time\":3000,\"max_real_time\":5000,\"max_memory\":268435456,"
                + "\"compile_command\":\"/usr/bin/gcc -DONLINE_JUDGE -O2 -w -fmax-errors=3 -std=c11 {src_path} -lm -o {exe_path}\"},"
                + "\"run\":{\"command\":\"{exe_path}\",\"env\":[\"LANG=en_US.UTF-8\",\"LANGUAGE=en_US:en\",\"LC_ALL=en_US.UTF-8\"],"
                + "\"seccomp_rule\":\"c_cpp\",\"memory_limit_check_only\":0}},"
                + "\"max_cpu_time\":1000,\"max_memory\":268435456,\"test_case_id\":\"simple-a-plus-b__8a0102cd409f\"}");
    }

    /**
     * A checker problem's request is the plain request plus qduoj's four spj fields, under the names
     * {@code JudgeServer.judge} takes. The braces are qduoj's own placeholders: it formats them itself.
     */
    @Test
    void spjRequestUsesQduojFieldNames() throws Exception {
        JudgeSpec checkerSpec = new JudgeSpec(SPEC.problemId(), SPEC.problemSlug(), SPEC.timeLimitMs(),
                SPEC.memoryLimitMb(), SPEC.testCaseVersion(), null, "int main(){}", "ab12");

        SubmissionRequestedEvent event = new JudgeService().buildRequestedEvent("00000000-0000-0000-0000-0000000000aa",
                "#include <stdio.h>\nint main(){return 0;}\n", checkerSpec, c(), 0, cpp());

        ObjectNode expected = (ObjectNode) objectMapper.readTree(outbox(cJudgeRequest()));
        expected.setAll((ObjectNode) objectMapper.readTree("{\"spj_version\":\"ab12\","
                + "\"spj_config\":{\"exe_name\":\"spj-{spj_version}\","
                + "\"command\":\"{exe_path} {in_file_path} {user_out_file_path}\",\"seccomp_rule\":\"c_cpp\"},"
                + "\"spj_compile_config\":{\"env\":[],\"src_name\":\"spj-{spj_version}.cpp\","
                + "\"exe_name\":\"spj-{spj_version}\",\"max_cpu_time\":10000,\"max_real_time\":20000,"
                + "\"max_memory\":1073741824,"
                + "\"compile_command\":\"/usr/bin/g++ -DONLINE_JUDGE -O2 -w -fmax-errors=3 -std=c++17 {src_path} -lm -o {exe_path}\"},"
                + "\"spj_src\":\"int main(){}\"}"));
        assertThat(objectMapper.readTree(outbox(event))).isEqualTo(expected);
    }
}
