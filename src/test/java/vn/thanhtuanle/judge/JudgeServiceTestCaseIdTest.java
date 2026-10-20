package vn.thanhtuanle.judge;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.submission.problem.JudgeSpec;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeServiceTestCaseIdTest {

    @Test
    void buildRequestedEvent_takesTheLimitsAndTheBundleVersionFromTheJudgeSpec() {
        JudgeService service = new JudgeService();
        JudgeSpec spec = new JudgeSpec(UUID.randomUUID(), "simple-a-plus-b", 1000, 64L, "abc123def456");

        SubmissionRequestedEvent event =
                service.buildRequestedEvent("sub-1", "int main(){}", spec, languageStub(), 0, null);

        assertThat(event.getTestCaseId()).isEqualTo("simple-a-plus-b__abc123def456");
        assertThat(event.getMaxCpuTime()).isEqualTo(1000);
        assertThat(event.getMaxMemory()).isEqualTo(64L * 1024 * 1024);
    }

    private Language languageStub() {
        Language l = new Language();
        l.setIdentifier("c");
        l.setSrcName("main.c");
        l.setExeName("main");
        l.setCompileMaxMemory(268435456L);
        l.setCompileCommand("/usr/bin/gcc -o main main.c");
        l.setRunCommand("main");
        l.setSeccompRule("c_cpp");
        return l;
    }
}
