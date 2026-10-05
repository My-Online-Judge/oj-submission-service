package vn.thanhtuanle.judge;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.common.constant.AppProperties;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.judge.dto.JudgeLanguageConfigDto;
import vn.thanhtuanle.submission.problem.JudgeSpec;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeServiceLanguageConfigTest {

    private final JudgeService service = new JudgeService();

    @Test
    void go_compilesWithABuildCache_andIsOnlyMemoryChecked() {
        JudgeLanguageConfigDto config = configFor("go");

        assertThat(config.getCompile().getEnv())
                .as("go build refuses to run without a build cache, and the judge sets no HOME")
                .containsExactly("GOCACHE=/tmp", "GOPATH=/root/go");
        assertThat(config.getRun().getMemoryLimitCheckOnly())
                .as("the Go runtime reserves more address space than any memory rlimit allows")
                .isEqualTo(1);
        assertThat(config.getRun().getEnv())
                .startsWith("GODEBUG=madvdontneed=1")
                .containsAll(AppProperties.JUDGE_ENV);
    }

    @Test
    void c_compilesWithAnEmptyEnv_andIsRlimited() {
        JudgeLanguageConfigDto config = configFor("c");

        assertThat(config.getCompile().getEnv())
                .as("an empty list, never null: judge-server appends PATH to whatever it is given")
                .isEmpty();
        assertThat(config.getRun().getMemoryLimitCheckOnly()).isZero();
        assertThat(config.getRun().getEnv()).isEqualTo(AppProperties.JUDGE_ENV);
    }

    private JudgeLanguageConfigDto configFor(String identifier) {
        JudgeSpec spec = new JudgeSpec(UUID.randomUUID(), "simple-a-plus-b", 1000, 64L, "abc123def456");
        Language l = new Language();
        l.setIdentifier(identifier);
        l.setSrcName("main");
        l.setExeName("main");
        l.setCompileCommand("cc {src_path}");
        l.setRunCommand("{exe_path}");
        return service.buildRequestedEvent("sub-1", "src", spec, l).getLanguageConfig();
    }
}
