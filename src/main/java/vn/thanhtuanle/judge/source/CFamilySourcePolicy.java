package vn.thanhtuanle.judge.source;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C and C++. At run time the sandbox's c_cpp seccomp rule is the barrier, so the calls listed here only get a clear
 * message instead of a Runtime Error. The compiler runs without that filter, so what a source can make it read is
 * what this policy really closes: an include outside the system headers, a computed include, and .incbin.
 *
 * <p>The source is read as the preprocessor reads it (trigraphs, spliced lines, digraph #). Macros are not expanded:
 * a call assembled with ## passes here and still meets seccomp.
 */
final class CFamilySourcePolicy {

    private static final Pattern DIRECTIVE = Pattern.compile("^[ \\t]*#[ \\t]*(\\w+)[ \\t]*(.*)$", Pattern.MULTILINE);
    private static final Pattern INCLUDE_ARGUMENT = Pattern.compile("^(?:<([^<>]+)>|\"([^\"]+)\")");
    private static final Pattern PRAGMA_DEPENDENCY = Pattern.compile("^GCC\\s+dependency\\b");

    private static final Set<String> BANNED_DIRECTIVES = Set.of("include_next", "import");
    private static final Set<String> BANNED_HEADERS = Set.of("dlfcn.h", "netdb.h", "pty.h", "seccomp.h", "spawn.h",
            "sys/mount.h", "sys/prctl.h", "sys/ptrace.h", "sys/reboot.h", "sys/sendfile.h", "sys/socket.h",
            "sys/syscall.h", "sys/un.h");
    private static final List<String> BANNED_HEADER_DIRECTORIES = List.of("arpa/", "asm/", "linux/", "net/", "netinet/");

    private static final Pattern ASSEMBLY = Pattern.compile("(?<![\\w])(?:asm|__asm|__asm__)(?![\\w])");
    private static final Pattern INCBIN = Pattern.compile("\\.incbin\\b");
    private static final Pattern PRAGMA_OPERATOR = Pattern.compile("(?<![\\w])_Pragma(?![\\w])");
    private static final Pattern BANNED_CALL = Pattern.compile("(?<![\\w.>])(system|popen|fork|vfork|execl|execlp|execle"
            + "|execv|execvp|execvpe|execve|fexecve|syscall|socket|ptrace|dlopen|dlsym|mprotect|prctl)\\s*\\(");

    private CFamilySourcePolicy() {
    }

    static Optional<SourceViolation> check(String source) {
        Stripped stripped = strip(splice(source.replace("??=", "#").replace("??/", "\\")));
        Optional<SourceViolation> directive = checkDirectives(stripped.code());
        if (directive.isPresent()) {
            return directive;
        }
        if (INCBIN.matcher(stripped.literals()).find()) {
            return Optional.of(new SourceViolation(1, "reading files while compiling is not allowed"));
        }
        return first(stripped.code(), ASSEMBLY, "assembly is not available on this judge")
                .or(() -> first(stripped.code(), INCBIN, "reading files while compiling is not allowed"))
                .or(() -> first(stripped.code(), PRAGMA_OPERATOR, "use #pragma instead of _Pragma"))
                .or(() -> firstCall(stripped.code()));
    }

    private static Optional<SourceViolation> checkDirectives(String code) {
        Matcher directive = DIRECTIVE.matcher(code);
        while (directive.find()) {
            int line = SourceViolation.lineAt(code, directive.start());
            String name = directive.group(1);
            String argument = directive.group(2).trim();
            if (BANNED_DIRECTIVES.contains(name)) {
                return Optional.of(new SourceViolation(line, "#" + name + " is not available on this judge"));
            }
            if (name.equals("pragma") && PRAGMA_DEPENDENCY.matcher(argument).find()) {
                return Optional.of(new SourceViolation(line, "#pragma GCC dependency is not available on this judge"));
            }
            if (name.equals("include")) {
                Optional<String> reason = checkInclude(argument);
                if (reason.isPresent()) {
                    return Optional.of(new SourceViolation(line, reason.get()));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkInclude(String argument) {
        Matcher include = INCLUDE_ARGUMENT.matcher(argument);
        if (!include.find()) {
            return Optional.of("write the header name directly in #include");
        }
        String header = include.group(1) != null ? include.group(1) : include.group(2);
        if (header.startsWith("/") || header.contains("..")) {
            return Optional.of("include only standard headers, not a file path");
        }
        if (BANNED_HEADERS.contains(header) || BANNED_HEADER_DIRECTORIES.stream().anyMatch(header::startsWith)) {
            return Optional.of("header <" + header + "> is not available on this judge");
        }
        return Optional.empty();
    }

    private static Optional<SourceViolation> first(String code, Pattern pattern, String reason) {
        Matcher matcher = pattern.matcher(code);
        return matcher.find()
                ? Optional.of(new SourceViolation(SourceViolation.lineAt(code, matcher.start()), reason))
                : Optional.empty();
    }

    private static Optional<SourceViolation> firstCall(String code) {
        Matcher matcher = BANNED_CALL.matcher(code);
        return matcher.find()
                ? Optional.of(new SourceViolation(SourceViolation.lineAt(code, matcher.start()),
                        matcher.group(1) + "() is not available on this judge"))
                : Optional.empty();
    }

    /** Joins lines ending in a backslash, and gives the line breaks back at the end of the joined line. */
    private static String splice(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int owed = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                owed++;
                i++;
            } else if (c == '\n') {
                out.append("\n".repeat(owed + 1));
                owed = 0;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@code code} has comments removed and strings emptied, except in directives; {@code literals} has those strings. */
    private record Stripped(String code, String literals) {
    }

    private static Stripped strip(String source) {
        StringBuilder code = new StringBuilder(source.length());
        StringBuilder literals = new StringBuilder();
        boolean lineStart = true;
        boolean directive = false;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                code.append(c);
                lineStart = true;
                directive = false;
                i++;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                code.append(' ').append("\n".repeat(newlines(source, i, end)));
                i = end;
            } else if (lineStart && (c == '#' || source.startsWith("%:", i))) {
                code.append('#');
                i += c == '#' ? 1 : 2;
                lineStart = false;
                directive = true;
            } else if (c == '"') {
                int end = isRawStringAt(source, i) ? closeRawString(source, i) : closeLiteral(source, i, '"');
                String literal = source.substring(i, end);
                literals.append(literal);
                code.append(directive ? literal : "\"\"").append(directive ? "" : "\n".repeat(newlines(source, i, end)));
                i = end;
                lineStart = false;
            } else if (c == '\'' && !(i > 0 && Character.isLetterOrDigit(source.charAt(i - 1)))) {
                i = closeLiteral(source, i, '\'');
                code.append("' '");
                lineStart = false;
            } else {
                code.append(c);
                lineStart = lineStart && Character.isWhitespace(c);
                i++;
            }
        }
        return new Stripped(code.toString(), literals.toString());
    }

    private static boolean isRawStringAt(String source, int quote) {
        if (quote == 0 || source.charAt(quote - 1) != 'R') {
            return false;
        }
        int before = quote - 2;
        while (before >= 0 && "uUL8".indexOf(source.charAt(before)) >= 0) {
            before--;
        }
        return before < 0 || !(Character.isLetterOrDigit(source.charAt(before)) || source.charAt(before) == '_');
    }

    /** The index just past the raw string opening at {@code quote}; an unterminated one runs to the end, as in gcc. */
    private static int closeRawString(String source, int quote) {
        int paren = source.indexOf('(', quote);
        if (paren < 0) {
            return source.length();
        }
        String close = ")" + source.substring(quote + 1, paren) + "\"";
        int end = source.indexOf(close, paren);
        return end < 0 ? source.length() : end + close.length();
    }

    /** The index just past the literal opening at {@code open}; a line break ends an unterminated one, as in gcc. */
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

    private static int newlines(String source, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
