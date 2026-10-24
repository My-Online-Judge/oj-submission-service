package vn.thanhtuanle.run;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.run.dto.RunCaseDto;
import vn.thanhtuanle.run.dto.RunRequestDto;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The limits and the normalisation of Run code design §5, with the defaults of {@link RunProperties}. */
class RunRequestValidatorTest {

    private final RunRequestValidator validator = new RunRequestValidator(new RunProperties());

    private static RunRequestDto request(RunCaseDto... cases) {
        return RunRequestDto.builder().problemSlug("a-plus-b").languageIdentifier("cpp").sourceCode("int main(){}")
                .cases(cases == null ? null : Arrays.asList(cases)).build();
    }

    private static RunCaseDto kase(String input, String expected) {
        return new RunCaseDto(input, expected);
    }

    @Test
    void sevenCasesAreRefused() {
        RunCaseDto[] seven = new RunCaseDto[7];
        Arrays.fill(seven, kase("1\n", null));

        assertThatThrownBy(() -> validator.validate(request(seven)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Run needs between 1 and 6 cases");
    }

    @Test
    void noCasesAreRefused() {
        assertThatThrownBy(() -> validator.validate(request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Run needs between 1 and 6 cases");
        assertThatThrownBy(() -> validator.validate(request((RunCaseDto[]) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Run needs between 1 and 6 cases");
    }

    @Test
    void aMissingInputIsRefused() {
        assertThatThrownBy(() -> validator.validate(request(kase(null, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Case 1 input is required");
    }

    @Test
    void anInputOver64KiBIsRefused() {
        assertThatThrownBy(() -> validator.validate(request(kase("a".repeat(65_537), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Case 1 input exceeds 65536 bytes");
        // Bytes, not characters: ă is two bytes in UTF-8.
        assertThatThrownBy(() -> validator.validate(request(kase("ă".repeat(32_769), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Case 1 input exceeds 65536 bytes");
        assertThatCode(() -> validator.validate(request(kase("a".repeat(65_536), null)))).doesNotThrowAnyException();
    }

    @Test
    void anExpectedOutputOver64KiBIsRefused() {
        assertThatThrownBy(() -> validator.validate(request(kase("1\n", "1\n"), kase("2\n", "b".repeat(65_537)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Case 2 expected output exceeds 65536 bytes");
    }

    @Test
    void windowsLineEndingsAreNormalisedToLf() {
        List<RunCaseDto> cases = validator.validate(request(kase("1 2\r\n3\r", "3\r\n"), kase("", null)));

        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).getInput()).isEqualTo("1 2\n3\n");
        assertThat(cases.get(0).getExpectedOutput()).isEqualTo("3\n");
        assertThat(cases.get(1).getInput()).isEmpty();
        assertThat(cases.get(1).getExpectedOutput()).isNull();
    }
}
