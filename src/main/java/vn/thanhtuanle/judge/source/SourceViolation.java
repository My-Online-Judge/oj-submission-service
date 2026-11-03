package vn.thanhtuanle.judge.source;

/** Why a source was refused, in words for the person who wrote it. {@code line} is 1-based. */
public record SourceViolation(int line, String reason) {

    public String message() {
        return "Line " + line + ": " + reason;
    }

    /** The 1-based line of {@code offset} in {@code text}. */
    static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
