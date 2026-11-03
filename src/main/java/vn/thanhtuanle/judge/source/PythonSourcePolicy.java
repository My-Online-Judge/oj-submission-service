package vn.thanhtuanle.judge.source;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python. The sandbox's general seccomp rule is the barrier: Python can build any name at run time, so no reading of
 * the source can stop a determined program. This reports the common cases early and in words, and leaves alone what
 * competitive programmers really write: os.read and os.fstat for fast input, open(0), eval(input()).
 */
final class PythonSourcePolicy {

    private static final Set<String> BANNED_MODULES = Set.of("__builtin__", "asyncio", "builtins", "cPickle", "cffi",
            "cgi", "code", "codeop", "commands", "concurrent", "ctypes", "dill", "fcntl", "ftplib", "glob", "http",
            "imp", "importlib", "inspect", "marshal", "mmap", "multiprocessing", "pathlib", "pdb", "pickle", "pkgutil",
            "platform", "pty", "resource", "runpy", "shelve", "shutil", "signal", "smtplib", "socket", "ssl",
            "subprocess", "sysconfig", "telnetlib", "tempfile", "termios", "tty", "urllib", "urllib2", "webbrowser",
            "xmlrpc", "zipimport");

    private static final Set<String> ALLOWED_OS_MEMBERS = Set.of("environ", "fstat", "getenv", "linesep", "name",
            "path", "read", "sep", "write");

    private static final Pattern IMPORT_KEYWORD = Pattern.compile("(?<![\\w.])(from|import)(?![\\w])");
    private static final Pattern MODULE_NAME = Pattern.compile("^[\\s\\\\]*([\\w.]+)");
    private static final Pattern IMPORT_AFTER_FROM = Pattern.compile("^[\\s\\\\]*import(?![\\w])");
    private static final Pattern OS_MEMBER = Pattern.compile("(?<![\\w.])os\\s*\\.\\s*(\\w+)");

    private record Rule(Pattern pattern, String reason) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?<![\\w])__(?:import|builtins|subclasses|globals|bases|base|mro|code|closure"
                    + "|getattribute|reduce|reduce_ex|loader|spec)__(?![\\w])"),
                    "introspection is not available on this judge"),
            new Rule(Pattern.compile("(?<![\\w.])(?:exec|execfile|reload)(?![\\w])|(?<![\\w.])compile\\s*\\("),
                    "running generated code is not available on this judge"),
            new Rule(Pattern.compile("(?<![\\w.])file\\s*\\(|(?<![\\w.])open\\s*\\(\\s*(?![01]\\s*[,)])"),
                    "read standard input with open(0) or sys.stdin"));

    private PythonSourcePolicy() {
    }

    static Optional<SourceViolation> check(String source) {
        String code = stripCommentsAndStrings(source);
        Optional<SourceViolation> imports = checkImports(code);
        if (imports.isPresent()) {
            return imports;
        }
        Matcher member = OS_MEMBER.matcher(code);
        while (member.find()) {
            if (!ALLOWED_OS_MEMBERS.contains(member.group(1))) {
                return Optional.of(new SourceViolation(SourceViolation.lineAt(code, member.start()),
                        "os." + member.group(1) + " is not available on this judge"));
            }
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(code);
            if (matcher.find()) {
                return Optional.of(new SourceViolation(SourceViolation.lineAt(code, matcher.start()), rule.reason()));
            }
        }
        return Optional.empty();
    }

    private static Optional<SourceViolation> checkImports(String code) {
        Matcher keyword = IMPORT_KEYWORD.matcher(code);
        int from = 0;
        while (keyword.find(from)) {
            int line = SourceViolation.lineAt(code, keyword.start());
            from = keyword.end();
            Optional<String> reason;
            if (keyword.group(1).equals("import")) {
                int end = statementEnd(code, from);
                reason = checkImportList(code.substring(from, end));
                from = end;
            } else {
                Matcher module = MODULE_NAME.matcher(code.substring(from));
                if (!module.find()) {
                    continue;
                }
                int afterModule = from + module.end();
                Matcher imported = IMPORT_AFTER_FROM.matcher(code.substring(afterModule));
                if (!imported.find()) {
                    continue; // yield from x, raise a from b
                }
                int names = afterModule + imported.end();
                int end = statementEnd(code, names);
                reason = checkFromImport(module.group(1), code.substring(names, end));
                from = end;
            }
            if (reason.isPresent()) {
                return Optional.of(new SourceViolation(line, reason.get()));
            }
        }
        return Optional.empty();
    }

    /** Where the statement starting at {@code from} ends: a line break outside parentheses, or a semicolon. */
    private static int statementEnd(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '\\' && i + 1 < code.length() && code.charAt(i + 1) == '\n') {
                i++;
            } else if (c == ';' || (c == '\n' && depth <= 0)) {
                return i;
            }
        }
        return code.length();
    }

    private static Optional<String> checkImportList(String list) {
        for (String entry : list.replace('\\', ' ').split(",")) {
            String[] parts = entry.trim().split("\\s+");
            String root = parts[0].split("\\.", -1)[0];
            if (BANNED_MODULES.contains(root)) {
                return Optional.of("module " + root + " is not available on this judge");
            }
            if (parts[0].equals("os") && parts.length > 1) {
                return Optional.of("import os without renaming it");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkFromImport(String module, String names) {
        String root = module.split("\\.", -1)[0];
        if (BANNED_MODULES.contains(root)) {
            return Optional.of("module " + root + " is not available on this judge");
        }
        if (module.equals("os")) {
            for (String entry : names.replaceAll("[()\\\\]", " ").split(",")) {
                String name = entry.trim().split("\\s+")[0];
                if (!ALLOWED_OS_MEMBERS.contains(name)) {
                    return Optional.of("os." + name + " is not available on this judge");
                }
            }
        }
        return Optional.empty();
    }

    /** Comments go, strings are emptied. An f-string keeps its text: what it interpolates is code. */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '#') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (c == '"' || c == '\'') {
                int end = closeString(source, i);
                String body = source.substring(i, end);
                code.append(isFormatted(source, i) ? body.replace(c, ' ') : "\"\"");
                body.chars().filter(ch -> ch == '\n').forEach(ch -> code.append('\n'));
                i = end;
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    private static boolean isFormatted(String source, int quote) {
        for (int i = quote - 1; i >= 0 && quote - i <= 2 && Character.isLetter(source.charAt(i)); i--) {
            if (source.charAt(i) == 'f' || source.charAt(i) == 'F') {
                return true;
            }
        }
        return false;
    }

    /** The index just past the string opening at {@code open}; a line break ends an unterminated short one. */
    private static int closeString(String source, int open) {
        char quote = source.charAt(open);
        String triple = String.valueOf(quote).repeat(3);
        boolean isTriple = source.startsWith(triple, open);
        for (int i = open + (isTriple ? 3 : 1); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (isTriple ? source.startsWith(triple, i) : c == quote) {
                return i + (isTriple ? 3 : 1);
            } else if (c == '\n' && !isTriple) {
                return i;
            }
        }
        return source.length();
    }
}
