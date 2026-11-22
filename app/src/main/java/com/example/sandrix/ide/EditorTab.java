package com.example.sandrix.ide;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class EditorTab {
    public final File file;
    public final String content;
    public final boolean isDirty;
    public final Integer pendingJumpLine;
    public final Set<Integer> errorLines;
    public final Set<Integer> warningLines;

    public EditorTab(File file, String content, boolean isDirty, Integer pendingJumpLine, Set<Integer> errorLines) {
        this(file, content, isDirty, pendingJumpLine, errorLines, Collections.emptySet());
    }

    public EditorTab(
            File file,
            String content,
            boolean isDirty,
            Integer pendingJumpLine,
            Set<Integer> errorLines,
            Set<Integer> warningLines) {
        this.file = file;
        this.content = content != null ? content : "";
        this.isDirty = isDirty;
        this.pendingJumpLine = pendingJumpLine;
        this.errorLines = errorLines != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(errorLines))
                : Collections.emptySet();
        this.warningLines = warningLines != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(warningLines))
                : Collections.emptySet();
    }

    public EditorTab withContent(String newContent, boolean dirty) {
        return new EditorTab(file, newContent, dirty, pendingJumpLine, errorLines, warningLines);
    }

    public EditorTab withDirty(boolean dirty) {
        return new EditorTab(file, content, dirty, pendingJumpLine, errorLines, warningLines);
    }

    public EditorTab withPendingJumpLine(Integer line) {
        return new EditorTab(file, content, isDirty, line, errorLines, warningLines);
    }

    public EditorTab withAddedErrorLine(int line) {
        Set<Integer> next = new LinkedHashSet<>(errorLines);
        next.add(line);
        return new EditorTab(file, content, isDirty, pendingJumpLine, next, warningLines);
    }

    public EditorTab withLineMarkers(Set<Integer> nextErrors, Set<Integer> nextWarnings) {
        return new EditorTab(file, content, isDirty, pendingJumpLine, nextErrors, nextWarnings);
    }
}
