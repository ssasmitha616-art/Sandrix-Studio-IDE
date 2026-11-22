package com.example.sandrix.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable / UI-friendly data models for Sandrix Studio's Advanced Debugger.
 */
public final class DebugModels {

    private DebugModels() {}

    public enum Mode {
        IDLE,
        RUNNING,
        PAUSED
    }

    public enum Backend {
        LOCAL_STEP_VM,
        JDWP
    }

    public static final class BreakpointSpec {
        public final String filePath;
        public final String fileName;
        public final int lineNumber;
        public final String condition;
        public final boolean enabled;
        public final int hitCount;

        public BreakpointSpec(
                String filePath,
                String fileName,
                int lineNumber,
                String condition,
                boolean enabled,
                int hitCount) {
            this.filePath = filePath != null ? filePath : "";
            this.fileName = fileName != null ? fileName : "";
            this.lineNumber = lineNumber;
            this.condition = condition != null ? condition.trim() : "";
            this.enabled = enabled;
            this.hitCount = hitCount;
        }

        public boolean hasCondition() {
            return condition != null && !condition.isEmpty();
        }

        public BreakpointSpec withCondition(String newCondition) {
            return new BreakpointSpec(filePath, fileName, lineNumber, newCondition, enabled, hitCount);
        }

        public BreakpointSpec withEnabled(boolean isEnabled) {
            return new BreakpointSpec(filePath, fileName, lineNumber, condition, isEnabled, hitCount);
        }

        public BreakpointSpec incrementHit() {
            return new BreakpointSpec(filePath, fileName, lineNumber, condition, enabled, hitCount + 1);
        }
    }

    public static final class StackFrameInfo {
        public final long frameId;
        public final String className;
        public final String methodName;
        public final String filePath;
        public final String fileName;
        public final int lineNumber;

        public StackFrameInfo(
                long frameId,
                String className,
                String methodName,
                String filePath,
                String fileName,
                int lineNumber) {
            this.frameId = frameId;
            this.className = className != null ? className : "<unknown>";
            this.methodName = methodName != null ? methodName : "<block>";
            this.filePath = filePath != null ? filePath : "";
            this.fileName = fileName != null ? fileName : "";
            this.lineNumber = lineNumber;
        }

        public String toDisplayLabel() {
            String simple = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;
            return simple + "." + methodName + "()  (" + fileName + ":" + lineNumber + ")";
        }
    }

    public static final class DebugVariable {
        public final String name;
        public final String type;
        public final String value;
        public final String scope;
        public final boolean editable;
        public final List<DebugVariable> children;

        public DebugVariable(
                String name,
                String type,
                String value,
                String scope,
                boolean editable,
                List<DebugVariable> children) {
            this.name = name != null ? name : "";
            this.type = type != null ? type : "Object";
            this.value = value != null ? value : "null";
            this.scope = scope != null ? scope : "Local";
            this.editable = editable;
            this.children = children != null
                    ? Collections.unmodifiableList(new ArrayList<>(children))
                    : Collections.emptyList();
        }
    }

    public static final class WatchItem {
        public final String expression;
        public final String value;
        public final String type;
        public final boolean error;

        public WatchItem(String expression, String value, String type, boolean error) {
            this.expression = expression != null ? expression : "";
            this.value = value != null ? value : "";
            this.type = type != null ? type : "";
            this.error = error;
        }
    }

    public static final class DebugSessionState {
        public final Mode mode;
        public final Backend backend;
        public final String activeFilePath;
        public final String activeFileName;
        public final int activeLine;
        public final String suspendReason;
        public final List<StackFrameInfo> frames;
        public final int selectedFrameIndex;
        public final List<DebugVariable> variables;
        public final List<WatchItem> watches;
        public final List<String> consoleOutput;
        public final String lastError;
        public final String jdwpHost;
        public final int jdwpPort;

        public DebugSessionState(
                Mode mode,
                Backend backend,
                String activeFilePath,
                String activeFileName,
                int activeLine,
                String suspendReason,
                List<StackFrameInfo> frames,
                int selectedFrameIndex,
                List<DebugVariable> variables,
                List<WatchItem> watches,
                List<String> consoleOutput,
                String lastError,
                String jdwpHost,
                int jdwpPort) {
            this.mode = mode;
            this.backend = backend;
            this.activeFilePath = activeFilePath != null ? activeFilePath : "";
            this.activeFileName = activeFileName != null ? activeFileName : "";
            this.activeLine = activeLine;
            this.suspendReason = suspendReason != null ? suspendReason : "";
            this.frames = frames != null ? Collections.unmodifiableList(new ArrayList<>(frames)) : Collections.emptyList();
            this.selectedFrameIndex = selectedFrameIndex;
            this.variables = variables != null ? Collections.unmodifiableList(new ArrayList<>(variables)) : Collections.emptyList();
            this.watches = watches != null ? Collections.unmodifiableList(new ArrayList<>(watches)) : Collections.emptyList();
            this.consoleOutput = consoleOutput != null ? Collections.unmodifiableList(new ArrayList<>(consoleOutput)) : Collections.emptyList();
            this.lastError = lastError;
            this.jdwpHost = jdwpHost != null ? jdwpHost : "127.0.0.1";
            this.jdwpPort = jdwpPort > 0 ? jdwpPort : 8700;
        }

        public static DebugSessionState idle() {
            return new DebugSessionState(
                    Mode.IDLE,
                    Backend.LOCAL_STEP_VM,
                    "",
                    "",
                    -1,
                    "",
                    Collections.emptyList(),
                    0,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    "127.0.0.1",
                    8700
            );
        }

        public boolean isActive() {
            return mode != Mode.IDLE;
        }
    }
}
