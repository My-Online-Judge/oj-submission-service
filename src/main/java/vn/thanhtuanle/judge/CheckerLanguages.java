package vn.thanhtuanle.judge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.submission.problem.JudgeSpec;

/**
 * The language a problem's checker is compiled with. Checkers are C++ only: whatever language a submission is in, its
 * problem's checker compiles with the {@code cpp} row's command and compile memory.
 */
@Component
@RequiredArgsConstructor
public class CheckerLanguages {

    private final LanguageRepository languageRepository;

    /**
     * @return null for a problem without a checker (nothing is read), else the {@code cpp} language
     * @throws IllegalStateException when the problem has a checker and there is no {@code cpp} language
     */
    public Language forSpec(JudgeSpec spec) {
        if (spec.spjVersion() == null) {
            return null;
        }
        return languageRepository.findByIdentifier("cpp").orElseThrow(
                () -> new IllegalStateException("The C++ language is required to compile this problem's checker"));
    }
}
