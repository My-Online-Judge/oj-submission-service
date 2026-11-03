package vn.thanhtuanle.judge.source;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Java. At run time the security manager and sandbox/java.policy are the barrier, so this only turns the common cases
 * into a clear message instead of an AccessControlException. Unicode escapes are decoded first, as javac does, and
 * comments and strings are then ignored: a word there does nothing, and a name built at run time meets the security
 * manager.
 */
final class JavaSourcePolicy {

    private record Rule(Pattern pattern, String reason) {
    }

    private static final String NOT_AVAILABLE = " is not available on this judge";

    private static final List<Rule> RULES = List.of(
            rule(dotted("java.net", "javax.net", "java.lang.reflect", "java.lang.invoke", "java.lang.instrument",
                    "java.lang.management", "java.nio.file", "java.nio.channels", "java.rmi", "javax.script",
                    "sun.misc", "sun.nio", "sun.reflect", "com.sun", "jdk.internal"), "this package" + NOT_AVAILABLE),
            rule("(?<![\\w.])(?:ProcessBuilder|ClassLoader|URLClassLoader|ScriptEngine|ScriptEngineManager|Unsafe"
                    + "|MethodHandles|AccessController|Socket|ServerSocket|DatagramSocket|URL|URLConnection"
                    + "|RandomAccessFile)(?![\\w])", "this class" + NOT_AVAILABLE),
            rule("\\.\\s*exec\\s*\\(", "starting a process" + NOT_AVAILABLE),
            rule(dotted("System.load", "System.getenv", "Class.forName") + "\\s*\\(", "this call" + NOT_AVAILABLE),
            rule("(?<![\\w])(?:setSecurityManager|loadLibrary|defineClass|setAccessible|getDeclaredField"
                    + "|getDeclaredFields|getDeclaredMethod|getDeclaredMethods|getDeclaredConstructor"
                    + "|getDeclaredConstructors)\\s*\\(", "this call" + NOT_AVAILABLE));

    private JavaSourcePolicy() {
    }

    static Optional<SourceViolation> check(String source) {
        String code = stripCommentsAndStrings(decodeUnicodeEscapes(source));
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(code);
            if (matcher.find()) {
                return Optional.of(new SourceViolation(SourceViolation.lineAt(code, matcher.start()),
                        matcher.group().replaceAll("\\s+", "") + ": " + rule.reason()));
            }
        }
        return Optional.empty();
    }

    private static Rule rule(String pattern, String reason) {
        return new Rule(Pattern.compile(pattern), reason);
    }

    /** A regex matching any of the dotted {@code names}, with the spaces Java allows around each dot. */
    private static String dotted(String... names) {
        return "(?<![\\w.])(?:" + Arrays.stream(names)
                .map(name -> name.replace(".", "\\s*\\.\\s*"))
                .collect(Collectors.joining("|")) + ")(?![\\w])";
    }

    /** Decodes backslash-u escapes; a backslash that is itself escaped starts none (JLS 3.3). */
    private static String decodeUnicodeEscapes(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        boolean escaped = false;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && !escaped && i + 1 < source.length() && source.charAt(i + 1) == 'u') {
                int digits = i + 1;
                while (digits < source.length() && source.charAt(digits) == 'u') {
                    digits++;
                }
                if (digits + 4 <= source.length() && isHex(source, digits)) {
                    out.append((char) Integer.parseInt(source.substring(digits, digits + 4), 16));
                    i = digits + 4;
                    continue;
                }
            }
            escaped = c == '\\' && !escaped;
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(String source, int at) {
        for (int i = at; i < at + 4; i++) {
            if (Character.digit(source.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String stripCommentsAndStrings(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                for (int at = i; at < end; at++) {
                    if (source.charAt(at) == '\n') {
                        code.append('\n');
                    }
                }
                i = end;
            } else if (c == '"' || c == '\'') {
                i = closeLiteral(source, i, c);
                code.append("\"\"");
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    /** The index just past the literal opening at {@code open}; a line break ends an unterminated one. */
    private static int closeLiteral(String source, int open, char quote) {
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n') {
                return i;
            }
        }
        return source.length();
    }
}
