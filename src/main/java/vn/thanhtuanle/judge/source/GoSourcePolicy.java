package vn.thanhtuanle.judge.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The barrier for Go: the sandbox's seccomp rules kill the Go runtime (it needs clone for its threads), so a Go
 * program runs without a syscall filter. What it can reach is decided here instead. Go makes that possible: every
 * package is named in an import, and without unsafe, syscall or cgo a program can only do what those packages expose.
 * So this is an allowlist, not a denylist, and anything it cannot read for certain is refused. os and io/ioutil are in
 * it for the standard streams, so they are limited to those members and cannot be renamed.
 *
 * <p>What stays open: a data race on an interface value can corrupt memory in any Go program, which is how a
 * determined attacker could still reach a raw syscall. Closing that needs a sandbox rule for Go.
 */
final class GoSourcePolicy {

    private static final Set<String> ALLOWED_IMPORTS = Set.of(
            "bufio", "bytes", "container/heap", "container/list", "container/ring", "context", "crypto/md5",
            "crypto/sha1", "crypto/sha256", "encoding/binary", "encoding/hex", "encoding/json", "errors", "fmt",
            "hash", "hash/crc32", "hash/fnv", "io", "io/ioutil", "log", "math", "math/big", "math/bits", "math/cmplx",
            "math/rand", "os", "regexp", "runtime", "runtime/debug", "sort", "strconv", "strings", "sync",
            "sync/atomic", "time", "unicode", "unicode/utf16", "unicode/utf8");

    /** Import path, the name it is used by, and the only members of it a program may use. */
    private static final Map<String, Restricted> RESTRICTED = Map.of(
            "os", new Restricted("os", Set.of("Args", "Exit", "File", "Stderr", "Stdin", "Stdout")),
            "io/ioutil", new Restricted("ioutil", Set.of("Discard", "ReadAll")));

    private static final Set<String> ALLOWED_DIRECTIVES = Set.of("noinline", "nosplit");

    private record Restricted(String name, Set<String> members) {
    }

    private enum Kind { IDENTIFIER, STRING, OTHER }

    private record Token(Kind kind, String text, int line) {
    }

    private static final class Refused extends RuntimeException {
        private final transient SourceViolation violation;

        Refused(int line, String reason) {
            super(reason, null, false, false);
            this.violation = new SourceViolation(line, reason);
        }
    }

    private GoSourcePolicy() {
    }

    static Optional<SourceViolation> check(String source) {
        try {
            List<Token> tokens = tokenize(source);
            checkSelectors(tokens, checkImports(tokens));
            return Optional.empty();
        } catch (Refused refused) {
            return Optional.of(refused.violation);
        }
    }

    /** Checks every import, and returns the restricted packages this source uses, by the name it uses them under. */
    private static Map<String, Restricted> checkImports(List<Token> tokens) {
        Map<String, Restricted> restricted = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() != Kind.IDENTIFIER || !token.text().equals("import")) {
                continue;
            }
            i++;
            if (i < tokens.size() && tokens.get(i).text().equals("(")) {
                i++;
                while (i < tokens.size() && !tokens.get(i).text().equals(")")) {
                    i = checkImportSpec(tokens, i, restricted);
                    if (i < tokens.size() && tokens.get(i).text().equals(";")) {
                        i++;
                    }
                }
            } else {
                i = checkImportSpec(tokens, i, restricted) - 1;
            }
        }
        return restricted;
    }

    /** Checks the import spec at {@code at}: an optional name, then the path. Returns the index just past it. */
    private static int checkImportSpec(List<Token> tokens, int at, Map<String, Restricted> restricted) {
        Token first = at < tokens.size() ? tokens.get(at) : null;
        boolean named = first != null && first.kind() != Kind.STRING;
        Token path = named ? (at + 1 < tokens.size() ? tokens.get(at + 1) : null) : first;
        if (path == null || path.kind() != Kind.STRING) {
            int line = first != null ? first.line() : 1;
            throw new Refused(line, "this import could not be read; write it as import \"package\"");
        }
        if (!ALLOWED_IMPORTS.contains(path.text())) {
            throw new Refused(path.line(), "package \"" + path.text() + "\" is not available on this judge");
        }
        Restricted limits = RESTRICTED.get(path.text());
        if (limits != null) {
            if (named) {
                throw new Refused(path.line(), "import \"" + path.text() + "\" without renaming it");
            }
            restricted.put(limits.name(), limits);
        }
        return at + (named ? 2 : 1);
    }

    private static void checkSelectors(List<Token> tokens, Map<String, Restricted> restricted) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            Token name = tokens.get(i);
            if (name.kind() != Kind.IDENTIFIER || !tokens.get(i + 1).text().equals(".")) {
                continue;
            }
            Restricted limits = restricted.get(name.text());
            Token member = tokens.get(i + 2);
            if (limits != null && !limits.members().contains(member.text())) {
                throw new Refused(member.line(), name.text() + "." + member.text()
                        + " is not available on this judge; read standard input and write standard output");
            }
        }
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int line = 1;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? source.length() : end;
                checkDirective(source.substring(i, end), line);
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new Refused(line, "this comment is never closed");
                }
                line += countNewlines(source, i, end);
                i = end + 2;
            } else if (c == '`') {
                int end = source.indexOf('`', i + 1);
                if (end < 0) {
                    throw new Refused(line, "this string is never closed");
                }
                tokens.add(new Token(Kind.STRING, source.substring(i + 1, end), line));
                line += countNewlines(source, i, end);
                i = end + 1;
            } else if (c == '"' || c == '\'') {
                int end = closingQuote(source, i, line);
                tokens.add(new Token(Kind.STRING, source.substring(i + 1, end), line));
                i = end + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                int end = i;
                while (end < source.length()
                        && (Character.isLetterOrDigit(source.charAt(end)) || source.charAt(end) == '_')) {
                    end++;
                }
                tokens.add(new Token(Kind.IDENTIFIER, source.substring(i, end), line));
                i = end;
            } else {
                tokens.add(new Token(Kind.OTHER, String.valueOf(c), line));
                i++;
            }
        }
        return tokens;
    }

    /** The index of the quote closing the one at {@code open}. A line break inside is a compile error in Go too. */
    private static int closingQuote(String source, int open, int line) {
        char quote = source.charAt(open);
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            } else if (c == '\n') {
                break;
            }
        }
        throw new Refused(line, "this string is never closed");
    }

    private static void checkDirective(String comment, int line) {
        if (comment.startsWith("//go:")
                && !ALLOWED_DIRECTIVES.contains(comment.substring("//go:".length()).trim())) {
            throw new Refused(line, "compiler directives are not available on this judge");
        }
    }

    private static int countNewlines(String source, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
