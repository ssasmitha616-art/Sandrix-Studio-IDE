package com.example.sandrix.ide;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable build log entry with optional parsed {@code filePath:lineNumber} jump target
 * for clickable compiler diagnostics and JVM/Android stack traces.
 */
public final class BuildLogLine {

    public static final class ParsedLocation {
        public final String filePath;
        public final int lineNumber;

        public ParsedLocation(String filePath, int lineNumber) {
            this.filePath = filePath;
            this.lineNumber = Math.max(1, lineNumber);
        }
    }

    private static final Pattern COMPILER_LOC_PATTERN = Pattern.compile(
            "(?:^|[\\s\\[\\]()'\"])((?:[A-Za-z]:)?[^\\s:\"']*?\\.(?:java|kt|kts|xml|sd)):(\\d+)(?::\\d+)?(?:[:\\s]|$)");

    private static final Pattern PATH_WITH_SPACES_LOC_PATTERN = Pattern.compile(
            "(?:^|\\]\\s*)([A-Za-z0-9_./\\\\ +-]+?\\.(?:java|kt|kts|xml|sd)):(\\d+)(?::\\d+)?(?:[:\\s]|$)");

    private static final Pattern STACKTRACE_LOC_PATTERN = Pattern.compile(
            "\\(([A-Za-z0-9_.$ -]+?\\.(?:java|kt|kts)):(\\d+)\\)");

    public final String text;
    public final boolean isError;
    public final String filePath;
    public final int lineNumber;

    public BuildLogLine(String text, boolean isError) {
        this.text = text != null ? text : "";
        this.isError = isError;
        ParsedLocation parsed = parseLocation(this.text);
        if (parsed != null) {
            this.filePath = parsed.filePath;
            this.lineNumber = parsed.lineNumber;
        } else {
            this.filePath = null;
            this.lineNumber = -1;
        }
    }

    public BuildLogLine(String text, boolean isError, String filePath, int lineNumber) {
        this.text = text != null ? text : "";
        this.isError = isError;
        this.filePath = (filePath != null && !filePath.trim().isEmpty()) ? filePath.trim() : null;
        this.lineNumber = this.filePath != null ? Math.max(1, lineNumber) : -1;
    }

    public boolean hasLocation() {
        return filePath != null && !filePath.isEmpty() && lineNumber > 0;
    }

    public static ParsedLocation parseLocation(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        Matcher stack = STACKTRACE_LOC_PATTERN.matcher(text);
        if (stack.find()) {
            String path = cleanCandidatePath(stack.group(1));
            int line = parseLineSafe(stack.group(2));
            if (path != null && line > 0) {
                return new ParsedLocation(path, line);
            }
        }

        Matcher compiler = COMPILER_LOC_PATTERN.matcher(text);
        if (compiler.find()) {
            String path = cleanCandidatePath(compiler.group(1));
            int line = parseLineSafe(compiler.group(2));
            if (path != null && line > 0) {
                return new ParsedLocation(path, line);
            }
        }

        Matcher spaced = PATH_WITH_SPACES_LOC_PATTERN.matcher(text);
        if (spaced.find()) {
            String path = cleanCandidatePath(spaced.group(1));
            int line = parseLineSafe(spaced.group(2));
            if (path != null && line > 0) {
                return new ParsedLocation(path, line);
            }
        }

        return null;
    }

    private static String cleanCandidatePath(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        while (trimmed.startsWith("[") || trimmed.startsWith("(")
                || trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parseLineSafe(String digits) {
        if (digits == null) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
