package vn.thanhtuanle.judge.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SourcePoliciesTest {

    @ParameterizedTest
    @ValueSource(strings = {"c", "cpp", "go", "java", "python2", "python3", "unknown"})
    void aNulCharacterIsRejectedInEveryLanguage(String language) {
        assertThat(SourcePolicies.check(language, "int x;\0")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"‮", "⁦", "⁩"})
    void aBidirectionalControlIsRejected(String control) {
        assertThat(SourcePolicies.check("cpp", "int x; // " + control + "\n")).isPresent();
    }

    @Test
    void vietnameseTextInCommentsAndStringsPasses() {
        assertThat(SourcePolicies.check("python3", "# Tính tổng dãy số\nprint('Kết quả')\n")).isEmpty();
        assertThat(SourcePolicies.check("cpp", "// Tính tổng\nint main() { puts(\"Kết quả\"); }\n")).isEmpty();
    }

    @Test
    void aLanguageWithoutAPolicyGetsOnlyTheCommonChecks() {
        assertThat(SourcePolicies.check("unknown", "system('x')\n")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"c", "cpp", "go", "java", "python3"})
    void aPathologicalSourceAtTheSizeCapIsCheckedQuickly(String language) {
        String[] inputs = {
                "\\\n".repeat(5000), "/*".repeat(5000), "#include ".repeat(1100), "import ".repeat(1400),
                "'".repeat(10240), "\"".repeat(10240), "\\u".repeat(5000), "os.".repeat(3400), " ".repeat(10240),
                "(".repeat(10240), "import a,".repeat(1100),
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (String input : inputs) {
                SourcePolicies.check(language, input);
            }
        });
    }
}
