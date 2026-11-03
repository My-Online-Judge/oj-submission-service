package vn.thanhtuanle.judge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeServiceValidateTest {

    private final JudgeService service = new JudgeService();

    @Test
    void aSourceTheLanguagesPolicyRefusesIsA400WithItsReason() {
        assertThatThrownBy(() -> service.validate("package main\nimport \"syscall\"\nfunc main() {}\n", "go"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Line 2: package \"syscall\" is not available on this judge");
    }

    @Test
    void wordsInACommentNoLongerRefuseASource() {
        assertThatCode(() -> service.validate("# never call /bin/sh here\nprint(1)\n", "python3"))
                .doesNotThrowAnyException();
    }

    @Test
    void anEmptySourceIsStillRefused() {
        assertThatThrownBy(() -> service.validate("", "c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate(null, "c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOversizedSourceIsStillRefused() {
        String tooLong = "x".repeat(JudgeService.MAX_CODE_LENGTH + 1);
        assertThatThrownBy(() -> service.validate(tooLong, "c")).isInstanceOf(IllegalArgumentException.class);
    }
}
