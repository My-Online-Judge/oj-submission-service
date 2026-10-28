package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.ActiveProfiles;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.messaging.event.RunRequestedEvent;
import vn.thanhtuanle.messaging.event.RunTestCase;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.submission.problem.JudgeSpec;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge-worker reads {@code run.requested} as plain JSON: the names qduoj's {@code JudgeServer.judge} takes, inline
 * {@code test_case} in place of a bundle, and nothing that belongs to a submission (Run code design §7).
 */
@JsonTest
@ActiveProfiles("test")
class RunRequestedWireFormatTest {

    @Autowired ObjectMapper objectMapper;   // the mapper KafkaTemplate's JsonSerializer is built from

    private static final UUID PROBLEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final JudgeSpec SPEC = new JudgeSpec(PROBLEM_ID, "simple-a-plus-b", 1000, 256L, "8a0102cd409f");
    private static final JudgeSpec CHECKER_SPEC = new JudgeSpec(PROBLEM_ID, "any-order", 1000, 256L, "8a0102cd409f",
            null, "int main(){return 0;}", "ab12");
    private static final String RUN_ID = "9f0c0000-0000-0000-0000-000000000001";

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

    private ObjectNode json(RunRequestedEvent event) throws Exception {
        return (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsBytes(event));
    }

    @Test
    void theInstanceThatAskedTravelsAsReplyToAndIsOmittedWhenUnset() throws Exception {
        SubmissionRequestedEvent template = new JudgeService().buildRequestedEvent("ignored", "int main(){}", SPEC, c(), 0);
        RunRequestedEvent event = RunRequestedEvent.from(template, RUN_ID, 1761300000000L, List.of(new RunTestCase("1\n", "")));
        assertThat(json(event).has("replyTo")).isFalse();

        event.setReplyTo("submission-service-7c9f");

        assertThat(json(event).get("replyTo").asText()).isEqualTo("submission-service-7c9f");
    }

    @Test
    void aRunRequestCarriesInlineCasesAndNoBundleOrSubmissionFields() throws Exception {
        SubmissionRequestedEvent template = new JudgeService().buildRequestedEvent("ignored", "int main(){}", SPEC, c(), 0);
        RunRequestedEvent event = RunRequestedEvent.from(template, RUN_ID, 1761300000000L,
                List.of(new RunTestCase("4\n1 6 11 5\n", "1\n"), new RunTestCase("2\n5 5\n", "")));

        ObjectNode json = json(event);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "runId", "expiresAt", "src", "language_config", "max_cpu_time", "max_memory", "test_case", "output");
        assertThat(json.get("runId").asText()).isEqualTo(RUN_ID);
        assertThat(json.get("expiresAt").asLong()).isEqualTo(1761300000000L);
        assertThat(json.get("max_cpu_time").asInt()).isEqualTo(1000);
        assertThat(json.get("max_memory").asLong()).isEqualTo(256L * 1024 * 1024);
        assertThat(json.get("output").asBoolean()).isTrue();
        assertThat(json.get("test_case").get(0).get("input").asText()).isEqualTo("4\n1 6 11 5\n");
        assertThat(json.get("test_case").get(0).get("output").asText()).isEqualTo("1\n");
        assertThat(json.get("test_case").get(1).get("output").asText()).isEmpty();
        // The template's language config travels unchanged.
        assertThat(json.get("language_config").get("compile").get("src_name").asText()).isEqualTo("main.c");
    }

    @Test
    void aCheckerProblemsCasesCarryNoOutput() throws Exception {
        SubmissionRequestedEvent template = new JudgeService().buildRequestedEvent("ignored", "int main(){}",
                CHECKER_SPEC, c(), 0, cpp());
        RunRequestedEvent event = RunRequestedEvent.from(template, RUN_ID, 1L, List.of(new RunTestCase("1 2\n", null)));

        ObjectNode json = json(event);

        assertThat(json.get("test_case").get(0).fieldNames()).toIterable().containsExactly("input");
        assertThat(json.get("spj_version").asText()).isEqualTo("ab12");
        assertThat(json.has("spj_src")).isTrue();
        assertThat(json.has("spj_config")).isTrue();
        assertThat(json.has("spj_compile_config")).isTrue();
    }
}
