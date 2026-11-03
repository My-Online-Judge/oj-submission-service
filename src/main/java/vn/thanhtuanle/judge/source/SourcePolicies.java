package vn.thanhtuanle.judge.source;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a source may not do, per language, checked before it is queued. Each language's policy states how much it is
 * worth: only {@link GoSourcePolicy} is the barrier for its language. The others report early and in words what the
 * sandbox would stop anyway (seccomp for C, C++ and Python, the Java security policy), and close what the sandbox
 * leaves open at compile time.
 */
public final class SourcePolicies {

    private static final Function<String, Optional<SourceViolation>> C_FAMILY = CFamilySourcePolicy::check;
    private static final Function<String, Optional<SourceViolation>> PYTHON = PythonSourcePolicy::check;

    private static final Map<String, Function<String, Optional<SourceViolation>>> BY_LANGUAGE = Map.of(
            "c", C_FAMILY,
            "cpp", C_FAMILY,
            "go", GoSourcePolicy::check,
            "java", JavaSourcePolicy::check,
            "python2", PYTHON,
            "python3", PYTHON);

    private SourcePolicies() {
    }

    /** The first violation in {@code source}; a language without a policy gets the common checks alone. */
    public static Optional<SourceViolation> check(String languageIdentifier, String source) {
        Optional<SourceViolation> common = checkCharacters(source);
        if (common.isPresent()) {
            return common;
        }
        return BY_LANGUAGE.getOrDefault(languageIdentifier, s -> Optional.empty()).apply(source);
    }

    /** NUL truncates what some tools read; a bidirectional control shows a reviewer different code from what runs. */
    private static Optional<SourceViolation> checkCharacters(String source) {
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean bidi = (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩');
            if (c == '\0' || bidi) {
                return Optional.of(new SourceViolation(SourceViolation.lineAt(source, i),
                        "remove the control character U+" + String.format("%04X", (int) c)));
            }
        }
        return Optional.empty();
    }
}
