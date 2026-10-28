package vn.thanhtuanle.submission.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.judge.JudgeService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A submission is refused with a 400 before any service code runs when it has no source, no language or no problem, or a
 * source over the cap (JudgeService.MAX_CODE_LENGTH). The constraints only run with a Bean Validation provider on the
 * classpath: the starter is declared in this pom (it used to arrive only through springdoc).
 */
class SubmissionRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static SubmissionRequestDto.SubmissionRequestDtoBuilder valid() {
        return SubmissionRequestDto.builder().sourceCode("int main(){}").languageIdentifier("cpp")
                .problemSlug("a-plus-b");
    }

    private Set<String> violated(SubmissionRequestDto dto) {
        return validator.validate(dto).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void aCompleteSubmissionHasNoViolation() {
        assertThat(violated(valid().build())).isEmpty();
    }

    @Test
    void aMissingOrBlankSourceLanguageOrProblemIsAViolation() {
        assertThat(violated(valid().sourceCode(" ").build())).containsExactly("sourceCode");
        assertThat(violated(valid().languageIdentifier(null).build())).containsExactly("languageIdentifier");
        assertThat(violated(valid().problemSlug("").build())).containsExactly("problemSlug");
    }

    @Test
    void aSourceOverTheCapIsAViolationAndOneAtTheCapIsNot() {
        assertThat(violated(valid().sourceCode("x".repeat(JudgeService.MAX_CODE_LENGTH + 1)).build()))
                .containsExactly("sourceCode");
        assertThat(violated(valid().sourceCode("x".repeat(JudgeService.MAX_CODE_LENGTH)).build())).isEmpty();
    }

    @Test
    void thePomDeclaresTheValidationStarterItself() throws Exception {
        assertThat(Files.readString(Path.of("pom.xml")))
                .containsPattern("(?s)<dependency>\\s*<groupId>org\\.springframework\\.boot</groupId>\\s*"
                        + "<artifactId>spring-boot-starter-validation</artifactId>");
    }
}
