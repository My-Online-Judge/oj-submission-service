package vn.thanhtuanle.judge;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.submission.problem.JudgeSpec;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CheckerLanguagesTest {

    @Test
    void aPlainProblemNeedsNoCpp() {
        LanguageRepository languages = mock(LanguageRepository.class);

        assertThat(new CheckerLanguages(languages)
                .forSpec(new JudgeSpec(UUID.randomUUID(), "simple-a-plus-b", 1000, 64L, "abc123def456"))).isNull();
        verifyNoInteractions(languages);
    }
}
