package com.example.sandrix.debug;

import com.example.sandrix.logcat.SandrixLogcatBus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real on-device interactive Java Step-Through Debugger VM.
 *
 * <p>Parses project {@code .java} files with exact line numbers, supports classes, fields,
 * Java 17 {@code record} types, switch expressions, {@code var}, loops, recursion, call stacks,
 * conditional breakpoints, hit counts, live variable editing, watch/expression evaluation,
 * and {@code Step Over}, {@code Step Into}, {@code Step Out}, {@code Resume}, {@code Pause}.</p>
 */
public final class LocalJavaDebugEngine {

    public enum StepCommand {
        RESUME,
        STEP_OVER,
        STEP_INTO,
        STEP_OUT,
        STOP
    }

    public interface SessionListener {
        void onPaused(
                String reason,
                String filePath,
                String fileName,
                int lineNumber,
                List<DebugModels.StackFrameInfo> frames,
                int preferredFrameIndex);

        void onResumed();

        void onConsoleOutput(String line);

        void onBreakpointHitUpdated(String filePath, int lineNumber);

        void onTerminated(String reason);
    }

    public interface BreakpointLookup {
        DebugModels.BreakpointSpec findBreakpoint(String filePath, String fileName, int lineNumber);
    }

    private enum TokenType {
        IDENT, NUMBER, STRING, CHAR, SYMBOL, EOF
    }

    private static final class Token {
        final TokenType type;
        final String text;
        final int line;

        Token(TokenType type, String text, int line) {
            this.type = type;
            this.text = text;
            this.line = line;
        }

        @Override
        public String toString() {
            return text + "@" + line;
        }
    }

    private static final class ParsedField {
        final String type;
        final String name;
        final List<Token> initExprTokens;
        final boolean isStatic;
        final int line;

        ParsedField(String type, String name, List<Token> initExprTokens, boolean isStatic, int line) {
            this.type = type;
            this.name = name;
            this.initExprTokens = initExprTokens;
            this.isStatic = isStatic;
            this.line = line;
        }
    }

    private static final class ParsedParam {
        final String type;
        final String name;

        ParsedParam(String type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    private static final class ParsedMethod {
        final String name;
        final String returnType;
        final List<ParsedParam> params;
        final int startLine;
        final int endLine;
        final List<Token> bodyTokens;
        final boolean isStatic;
        final boolean isConstructor;

        ParsedMethod(
                String name,
                String returnType,
                List<ParsedParam> params,
                int startLine,
                int endLine,
                List<Token> bodyTokens,
                boolean isStatic,
                boolean isConstructor) {
            this.name = name;
            this.returnType = returnType;
            this.params = params;
            this.startLine = startLine;
            this.endLine = endLine;
            this.bodyTokens = bodyTokens;
            this.isStatic = isStatic;
            this.isConstructor = isConstructor;
        }
    }

    private static final class ParsedClass {
        final String simpleName;
        final String fullName;
        final File sourceFile;
        final boolean isRecord;
        final List<ParsedParam> recordComponents;
        final List<ParsedField> fields = new ArrayList<>();
        final List<ParsedMethod> methods = new ArrayList<>();
        final Map<String, Object> staticFields = new LinkedHashMap<>();

        ParsedClass(String simpleName, String fullName, File sourceFile,
                    boolean isRecord, List<ParsedParam> recordComponents) {
            this.simpleName = simpleName;
            this.fullName = fullName;
            this.sourceFile = sourceFile;
            this.isRecord = isRecord;
            this.recordComponents = recordComponents != null ? recordComponents : Collections.emptyList();
        }

        ParsedMethod findMethod(String name, int argCount) {
            ParsedMethod fallback = null;
            for (ParsedMethod m : methods) {
                if (m.name.equals(name)) {
                    if (m.params.size() == argCount) return m;
                    if (fallback == null) fallback = m;
                }
            }
            return fallback;
        }
    }

    private static final class LambdaClosure {
        final List<String> paramNames;
        final List<Token> bodyTokens;
        final Frame callerFrame;
        final File sourceFile;

        LambdaClosure(List<String> paramNames, List<Token> bodyTokens, Frame callerFrame, File sourceFile) {
            this.paramNames = paramNames;
            this.bodyTokens = bodyTokens;
            this.callerFrame = callerFrame;
            this.sourceFile = sourceFile;
        }
    }

    private static final class WidgetInstance {
        final String idName;
        final String typeName;
        String text = "";
        int visibility = 0;
        LambdaClosure onClickListener;
        ParsedClass anonListenerClass;
        ObjectInstance anonListenerInstance;

        WidgetInstance(String idName, String typeName) {
            this.idName = idName;
            this.typeName = typeName;
        }

        @Override
        public String toString() {
            return typeName + "(" + idName + ", text=\"" + text + "\")";
        }
    }

    private static final class ObjectInstance {
        final ParsedClass parsedClass;
        final Map<String, Object> fields = new LinkedHashMap<>();

        ObjectInstance(ParsedClass parsedClass) {
            this.parsedClass = parsedClass;
        }

        @Override
        public String toString() {
            return parsedClass.simpleName + fields;
        }
    }

    private static final class ControlSignal extends RuntimeException {
        enum Kind { RETURN, BREAK, CONTINUE, THROW }
        final Kind kind;
        final Object value;

        ControlSignal(Kind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }
    }

    private static final class StopDebugException extends RuntimeException {}

    private static final class Frame {
        final long frameId;
        final String className;
        final String methodName;
        final String filePath;
        final String fileName;
        int currentLine;
        final ObjectInstance thisInstance;
        final Deque<Map<String, Object>> scopes = new ArrayDeque<>();
        final Deque<Map<String, String>> scopeTypes = new ArrayDeque<>();

        Frame(long frameId, String className, String methodName, File file, int line, ObjectInstance thisInstance) {
            this.frameId = frameId;
            this.className = className;
            this.methodName = methodName;
            this.filePath = file != null ? file.getAbsolutePath() : "";
            this.fileName = file != null ? file.getName() : "";
            this.currentLine = line;
            this.thisInstance = thisInstance;
            pushScope();
        }

        void pushScope() {
            scopes.push(new LinkedHashMap<>());
            scopeTypes.push(new LinkedHashMap<>());
        }

        void popScope() {
            if (scopes.size() > 1) {
                scopes.pop();
                scopeTypes.pop();
            }
        }

        void declare(String name, String type, Object value) {
            Map<String, Object> top = scopes.peek();
            Map<String, String> types = scopeTypes.peek();
            if (top != null) top.put(name, value);
            if (types != null) types.put(name, type != null ? type : "Object");
        }

        boolean hasLocal(String name) {
            for (Map<String, Object> s : scopes) {
                if (s.containsKey(name)) return true;
            }
            return false;
        }

        Object getLocal(String name) {
            for (Map<String, Object> s : scopes) {
                if (s.containsKey(name)) return s.get(name);
            }
            return null;
        }

        String getLocalType(String name) {
            for (Map<String, String> s : scopeTypes) {
                if (s.containsKey(name)) return s.get(name);
            }
            return "Object";
        }

        boolean setLocal(String name, Object value) {
            for (Map<String, Object> s : scopes) {
                if (s.containsKey(name)) {
                    s.put(name, value);
                    return true;
                }
            }
            return false;
        }

        Map<String, Object> flattenLocals() {
            Map<String, Object> merged = new LinkedHashMap<>();
            List<Map<String, Object>> rev = new ArrayList<>(scopes);
            Collections.reverse(rev);
            for (Map<String, Object> s : rev) {
                merged.putAll(s);
            }
            return merged;
        }
    }

    private final Map<String, ParsedClass> classesBySimpleName = new LinkedHashMap<>();
    private final Map<String, WidgetInstance> uiWidgets = new LinkedHashMap<>();
    private final List<Frame> callStack = new ArrayList<>();
    private final BlockingQueue<StepCommand> commandQueue = new LinkedBlockingQueue<>();

    private BreakpointLookup breakpointLookup;
    private SessionListener listener;
    private Thread workerThread;

    private volatile boolean stopRequested = false;
    private volatile boolean pauseRequested = false;
    private volatile boolean paused = false;
    private volatile int stepMode = 0; // 0 = running, 1 = stepInto, 2 = stepOver, 3 = stepOut
    private volatile int stepTargetDepth = Integer.MAX_VALUE;
    private volatile long frameIdSeq = 1L;
    private volatile int lastPausedLine = -1;
    private volatile String lastPausedFile = "";

    public synchronized void start(
            File projectDir,
            File activeFile,
            BreakpointLookup bpLookup,
            SessionListener sessionListener) {
        stop();
        this.breakpointLookup = bpLookup;
        this.listener = sessionListener;
        this.stopRequested = false;
        this.pauseRequested = false;
        this.paused = false;
        this.stepMode = 0;
        this.stepTargetDepth = Integer.MAX_VALUE;
        this.lastPausedLine = -1;
        this.lastPausedFile = "";
        this.classesBySimpleName.clear();
        this.uiWidgets.clear();
        synchronized (callStack) {
            callStack.clear();
        }
        commandQueue.clear();

        workerThread = new Thread(() -> runDebugProgram(projectDir, activeFile), "SandrixStepDebugVM");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void sendCommand(StepCommand cmd) {
        if (cmd == StepCommand.STOP) {
            stop();
            return;
        }
        commandQueue.offer(cmd);
    }

    public void requestPause() {
        pauseRequested = true;
    }

    public void stop() {
        stopRequested = true;
        commandQueue.offer(StepCommand.STOP);
        Thread t = workerThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        Thread t = workerThread;
        return t != null && t.isAlive();
    }

    public boolean isPaused() {
        return paused;
    }

    public List<DebugModels.DebugVariable> inspectVariables(int frameIndex) {
        Frame frame;
        synchronized (callStack) {
            if (callStack.isEmpty()) return Collections.emptyList();
            int idx = Math.max(0, Math.min(frameIndex, callStack.size() - 1));
            frame = callStack.get(idx);
        }
        List<DebugModels.DebugVariable> vars = new ArrayList<>();
        Map<String, Object> locals = frame.flattenLocals();
        for (Map.Entry<String, Object> e : locals.entrySet()) {
            vars.add(toDebugVariable(e.getKey(), frame.getLocalType(e.getKey()), e.getValue(), "Local", true, 0));
        }
        if (frame.thisInstance != null) {
            List<DebugModels.DebugVariable> fieldChildren = new ArrayList<>();
            for (Map.Entry<String, Object> fe : frame.thisInstance.fields.entrySet()) {
                fieldChildren.add(toDebugVariable(fe.getKey(), inferType(fe.getValue()), fe.getValue(), "Field", true, 1));
            }
            vars.add(new DebugModels.DebugVariable(
                    "this",
                    frame.thisInstance.parsedClass.simpleName,
                    frame.thisInstance.parsedClass.simpleName + "@" + Integer.toHexString(System.identityHashCode(frame.thisInstance)),
                    "Field",
                    false,
                    fieldChildren
            ));
        }
        return vars;
    }

    public boolean setVariableValue(int frameIndex, String varName, String valueExpression) {
        Frame frame;
        synchronized (callStack) {
            if (callStack.isEmpty()) return false;
            int idx = Math.max(0, Math.min(frameIndex, callStack.size() - 1));
            frame = callStack.get(idx);
        }
        try {
            List<Token> exprTokens = tokenize(valueExpression, frame.currentLine);
            Object val = new ExprEvaluator(frame, exprTokens).eval();
            if (frame.hasLocal(varName)) {
                return frame.setLocal(varName, val);
            }
            if (frame.thisInstance != null && frame.thisInstance.fields.containsKey(varName)) {
                frame.thisInstance.fields.put(varName, val);
                return true;
            }
            ParsedClass pc = classesBySimpleName.get(frame.className);
            if (pc != null && pc.staticFields.containsKey(varName)) {
                pc.staticFields.put(varName, val);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public DebugModels.WatchItem evaluateWatch(int frameIndex, String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return new DebugModels.WatchItem("", "", "", false);
        }
        Frame frame;
        synchronized (callStack) {
            if (callStack.isEmpty()) {
                return new DebugModels.WatchItem(expression, "<No active stack frame>", "", true);
            }
            int idx = Math.max(0, Math.min(frameIndex, callStack.size() - 1));
            frame = callStack.get(idx);
        }
        try {
            List<Token> tokens = tokenize(expression, frame.currentLine);
            Object val = new ExprEvaluator(frame, tokens).eval();
            return new DebugModels.WatchItem(expression, formatValue(val), inferType(val), false);
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return new DebugModels.WatchItem(expression, "Error: " + msg, "Error", true);
        }
    }

    public void triggerUiWidgets() {
        for (WidgetInstance widget : new ArrayList<>(uiWidgets.values())) {
            if (widget.onClickListener != null) {
                logConsole("[UI Debug] Triggering click listener on " + widget.idName);
                invokeLambda(widget.onClickListener, Collections.singletonList(widget));
            } else if (widget.anonListenerClass != null && widget.anonListenerInstance != null) {
                ParsedMethod onClick = widget.anonListenerClass.findMethod("onClick", 1);
                if (onClick != null) {
                    logConsole("[UI Debug] Triggering onClick on " + widget.idName);
                    invokeMethod(widget.anonListenerInstance, widget.anonListenerClass, onClick, Collections.singletonList(widget));
                }
            }
        }
    }

    private void runDebugProgram(File projectDir, File activeFile) {
        String terminationReason = "Debug session finished";
        try {
            List<File> javaFiles = collectJavaFiles(projectDir);
            if (activeFile != null && activeFile.isFile() && activeFile.getName().endsWith(".java")) {
                boolean present = false;
                for (File f : javaFiles) {
                    if (f.getAbsolutePath().equals(activeFile.getAbsolutePath())) {
                        present = true;
                        break;
                    }
                }
                if (!present) javaFiles.add(activeFile);
            }

            if (javaFiles.isEmpty()) {
                logConsole("[Debugger] No .java files found in project.");
                terminationReason = "No Java source files to debug";
                return;
            }

            for (File f : javaFiles) {
                String source = readUtf8(f);
                List<ParsedClass> parsed = parseClassesFromFile(f, source);
                for (ParsedClass pc : parsed) {
                    classesBySimpleName.put(pc.simpleName, pc);
                }
            }

            // Initialize static fields
            for (ParsedClass pc : classesBySimpleName.values()) {
                for (ParsedField pf : pc.fields) {
                    if (pf.isStatic) {
                        Object initVal = pf.initExprTokens.isEmpty()
                                ? defaultValueForType(pf.type)
                                : evalStandalone(pc, null, pf.initExprTokens);
                        pc.staticFields.put(pf.name, initVal);
                    }
                }
            }

            // Pick entry point: prefer activeFile's main or onCreate, else MainActivity.onCreate, else any main/onCreate/first method
            EntryTarget target = selectEntryTarget(activeFile);
            if (target == null) {
                logConsole("[Debugger] No main(...) or onCreate(...) entry method found.");
                terminationReason = "No entry method found";
                return;
            }

            logConsole("[Debugger] Started Local Step VM -> " + target.parsedClass.simpleName + "." + target.method.name + "()");
            ObjectInstance rootInstance = null;
            if (!target.method.isStatic) {
                rootInstance = createInstance(target.parsedClass, Collections.emptyList());
            }

            List<Object> entryArgs = new ArrayList<>();
            for (ParsedParam p : target.method.params) {
                if (p.type.contains("String[]")) {
                    entryArgs.add(new ArrayList<String>());
                } else {
                    entryArgs.add(null);
                }
            }
            invokeMethod(rootInstance, target.parsedClass, target.method, entryArgs);

            // Auto-dispatch UI click listeners if any widgets registered them during onCreate
            if (!stopRequested && !uiWidgets.isEmpty()) {
                boolean hasListeners = false;
                for (WidgetInstance w : uiWidgets.values()) {
                    if (w.onClickListener != null || w.anonListenerClass != null) {
                        hasListeners = true;
                        break;
                    }
                }
                if (hasListeners) {
                    triggerUiWidgets();
                }
            }
        } catch (StopDebugException e) {
            terminationReason = "Stopped by user";
        } catch (Throwable t) {
            terminationReason = "Runtime exception: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            logConsole("[Debugger Error] " + terminationReason);
        } finally {
            paused = false;
            synchronized (callStack) {
                callStack.clear();
            }
            if (listener != null) {
                listener.onTerminated(terminationReason);
            }
        }
    }

    private static final class EntryTarget {
        final ParsedClass parsedClass;
        final ParsedMethod method;

        EntryTarget(ParsedClass parsedClass, ParsedMethod method) {
            this.parsedClass = parsedClass;
            this.method = method;
        }
    }

    private EntryTarget selectEntryTarget(File activeFile) {
        if (activeFile != null) {
            for (ParsedClass pc : classesBySimpleName.values()) {
                if (pc.sourceFile.getAbsolutePath().equals(activeFile.getAbsolutePath())) {
                    ParsedMethod main = pc.findMethod("main", 1);
                    if (main != null) return new EntryTarget(pc, main);
                    ParsedMethod onCreate = pc.findMethod("onCreate", 1);
                    if (onCreate != null) return new EntryTarget(pc, onCreate);
                }
            }
        }
        ParsedClass mainAct = classesBySimpleName.get("MainActivity");
        if (mainAct != null) {
            ParsedMethod main = mainAct.findMethod("main", 1);
            if (main != null) return new EntryTarget(mainAct, main);
            ParsedMethod onCreate = mainAct.findMethod("onCreate", 1);
            if (onCreate != null) return new EntryTarget(mainAct, onCreate);
            if (!mainAct.methods.isEmpty()) return new EntryTarget(mainAct, mainAct.methods.get(0));
        }
        for (ParsedClass pc : classesBySimpleName.values()) {
            ParsedMethod main = pc.findMethod("main", 1);
            if (main != null) return new EntryTarget(pc, main);
            ParsedMethod onCreate = pc.findMethod("onCreate", 1);
            if (onCreate != null) return new EntryTarget(pc, onCreate);
        }
        for (ParsedClass pc : classesBySimpleName.values()) {
            if (!pc.methods.isEmpty()) return new EntryTarget(pc, pc.methods.get(0));
        }
        return null;
    }

    private Object invokeMethod(
            ObjectInstance instance,
            ParsedClass ownerClass,
            ParsedMethod method,
            List<Object> args) {
        Frame frame = new Frame(
                frameIdSeq++,
                ownerClass.simpleName,
                method.name,
                ownerClass.sourceFile,
                method.startLine,
                instance);
        for (int i = 0; i < method.params.size(); i++) {
            ParsedParam p = method.params.get(i);
            Object val = i < args.size() ? args.get(i) : defaultValueForType(p.type);
            frame.declare(p.name, p.type, val);
        }

        synchronized (callStack) {
            callStack.add(0, frame);
        }
        try {
            if (stepMode == 1 && callStack.size() >= 1) {
                stepTargetDepth = callStack.size();
            }
            executeBlock(frame, method.bodyTokens);
            return null;
        } catch (ControlSignal sig) {
            if (sig.kind == ControlSignal.Kind.RETURN) {
                return sig.value;
            }
            throw sig;
        } finally {
            synchronized (callStack) {
                callStack.remove(frame);
            }
            if (stepMode == 3 && callStack.size() < stepTargetDepth) {
                stepTargetDepth = callStack.size();
                stepMode = 1; // Pause as soon as caller executes next statement
            }
        }
    }

    private Object invokeLambda(LambdaClosure closure, List<Object> args) {
        Frame frame = new Frame(
                frameIdSeq++,
                closure.callerFrame.className + "$lambda",
                "lambda",
                closure.sourceFile,
                closure.bodyTokens.isEmpty() ? closure.callerFrame.currentLine : closure.bodyTokens.get(0).line,
                closure.callerFrame.thisInstance);
        // Inherit read access to captured outer variables
        Map<String, Object> captured = closure.callerFrame.flattenLocals();
        for (Map.Entry<String, Object> e : captured.entrySet()) {
            frame.declare(e.getKey(), closure.callerFrame.getLocalType(e.getKey()), e.getValue());
        }
        for (int i = 0; i < closure.paramNames.size(); i++) {
            frame.declare(closure.paramNames.get(i), "Object", i < args.size() ? args.get(i) : null);
        }

        synchronized (callStack) {
            callStack.add(0, frame);
        }
        try {
            if (!closure.bodyTokens.isEmpty() && "{".equals(closure.bodyTokens.get(0).text)) {
                executeBlock(frame, stripOuterBraces(closure.bodyTokens));
                return null;
            } else {
                maybePauseAtLine(frame, frame.currentLine);
                return new ExprEvaluator(frame, closure.bodyTokens).eval();
            }
        } catch (ControlSignal sig) {
            if (sig.kind == ControlSignal.Kind.RETURN) return sig.value;
            throw sig;
        } finally {
            synchronized (callStack) {
                callStack.remove(frame);
            }
        }
    }

    private void executeBlock(Frame frame, List<Token> tokens) {
        frame.pushScope();
        try {
            int i = 0;
            while (i < tokens.size()) {
                if (stopRequested) throw new StopDebugException();
                Token tok = tokens.get(i);
                if (";".equals(tok.text)) {
                    i++;
                    continue;
                }
                i = executeStatement(frame, tokens, i);
            }
        } finally {
            frame.popScope();
        }
    }

    private int executeStatement(Frame frame, List<Token> tokens, int idx) {
        if (idx >= tokens.size()) return idx;
        Token first = tokens.get(idx);
        frame.currentLine = first.line;

        if ("{".equals(first.text)) {
            int end = findMatchingBrace(tokens, idx);
            executeBlock(frame, tokens.subList(idx + 1, end));
            return end + 1;
        }

        if ("if".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            int openParen = idx + 1;
            int closeParen = findMatchingParen(tokens, openParen);
            boolean cond = toBoolean(new ExprEvaluator(frame, tokens.subList(openParen + 1, closeParen)).eval());
            int thenStart = closeParen + 1;
            int afterThen = skipOrExecuteSingleOrBlock(frame, tokens, thenStart, cond);
            if (afterThen < tokens.size() && "else".equals(tokens.get(afterThen).text)) {
                return skipOrExecuteSingleOrBlock(frame, tokens, afterThen + 1, !cond);
            }
            return afterThen;
        }

        if ("while".equals(first.text)) {
            int openParen = idx + 1;
            int closeParen = findMatchingParen(tokens, openParen);
            int bodyStart = closeParen + 1;
            int bodyEnd = findStatementEnd(tokens, bodyStart);
            int guard = 0;
            while (!stopRequested && guard++ < 50000) {
                maybePauseAtLine(frame, first.line);
                boolean cond = toBoolean(new ExprEvaluator(frame, tokens.subList(openParen + 1, closeParen)).eval());
                if (!cond) break;
                try {
                    executeSingleOrBlock(frame, tokens, bodyStart);
                } catch (ControlSignal sig) {
                    if (sig.kind == ControlSignal.Kind.BREAK) break;
                    if (sig.kind == ControlSignal.Kind.CONTINUE) continue;
                    throw sig;
                }
            }
            return bodyEnd;
        }

        if ("for".equals(first.text)) {
            int openParen = idx + 1;
            int closeParen = findMatchingParen(tokens, openParen);
            int bodyStart = closeParen + 1;
            int bodyEnd = findStatementEnd(tokens, bodyStart);
            List<Token> header = tokens.subList(openParen + 1, closeParen);

            int colonIdx = findTopLevelToken(header, ":");
            frame.pushScope();
            try {
                if (colonIdx > 0) {
                    // Enhanced for (Type item : iterable)
                    String varName = header.get(colonIdx - 1).text;
                    String varType = colonIdx >= 2 ? header.get(0).text : "var";
                    Object iterableObj = new ExprEvaluator(frame, header.subList(colonIdx + 1, header.size())).eval();
                    frame.declare(varName, varType, null);
                    Iterable<?> iterable = toIterable(iterableObj);
                    for (Object item : iterable) {
                        if (stopRequested) throw new StopDebugException();
                        maybePauseAtLine(frame, first.line);
                        frame.setLocal(varName, item);
                        try {
                            executeSingleOrBlock(frame, tokens, bodyStart);
                        } catch (ControlSignal sig) {
                            if (sig.kind == ControlSignal.Kind.BREAK) break;
                            if (sig.kind == ControlSignal.Kind.CONTINUE) continue;
                            throw sig;
                        }
                    }
                } else {
                    int semi1 = findTopLevelToken(header, ";");
                    int semi2 = findTopLevelTokenFrom(header, ";", semi1 + 1);
                    if (semi1 >= 0 && semi2 > semi1) {
                        List<Token> initToks = header.subList(0, semi1);
                        List<Token> condToks = header.subList(semi1 + 1, semi2);
                        List<Token> updToks = header.subList(semi2 + 1, header.size());
                        if (!initToks.isEmpty()) {
                            executeInlineExprOrDecl(frame, initToks);
                        }
                        int guard = 0;
                        while (!stopRequested && guard++ < 50000) {
                            maybePauseAtLine(frame, first.line);
                            if (!condToks.isEmpty() && !toBoolean(new ExprEvaluator(frame, condToks).eval())) {
                                break;
                            }
                            try {
                                executeSingleOrBlock(frame, tokens, bodyStart);
                            } catch (ControlSignal sig) {
                                if (sig.kind == ControlSignal.Kind.BREAK) break;
                                if (sig.kind != ControlSignal.Kind.CONTINUE) throw sig;
                            }
                            if (!updToks.isEmpty()) {
                                executeInlineExprOrDecl(frame, updToks);
                            }
                        }
                    }
                }
            } finally {
                frame.popScope();
            }
            return bodyEnd;
        }

        if ("switch".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            int openParen = idx + 1;
            int closeParen = findMatchingParen(tokens, openParen);
            Object switchVal = new ExprEvaluator(frame, tokens.subList(openParen + 1, closeParen)).eval();
            int braceOpen = closeParen + 1;
            int braceClose = findMatchingBrace(tokens, braceOpen);
            executeSwitchBlock(frame, switchVal, tokens.subList(braceOpen + 1, braceClose));
            return braceClose + 1;
        }

        if ("try".equals(first.text)) {
            int braceOpen = idx + 1;
            int braceClose = findMatchingBrace(tokens, braceOpen);
            int cursor = braceClose + 1;
            RuntimeException caught = null;
            try {
                executeBlock(frame, tokens.subList(braceOpen + 1, braceClose));
            } catch (StopDebugException | ControlSignal cs) {
                throw cs;
            } catch (RuntimeException re) {
                caught = re;
            }
            while (cursor < tokens.size() && "catch".equals(tokens.get(cursor).text)) {
                int cpOpen = cursor + 1;
                int cpClose = findMatchingParen(tokens, cpOpen);
                int cbOpen = cpClose + 1;
                int cbClose = findMatchingBrace(tokens, cbOpen);
                if (caught != null) {
                    String excVar = cpClose - cpOpen >= 3 ? tokens.get(cpClose - 1).text : "e";
                    frame.pushScope();
                    frame.declare(excVar, "Exception", caught);
                    try {
                        executeBlock(frame, tokens.subList(cbOpen + 1, cbClose));
                        caught = null;
                    } finally {
                        frame.popScope();
                    }
                }
                cursor = cbClose + 1;
            }
            if (cursor < tokens.size() && "finally".equals(tokens.get(cursor).text)) {
                int fbOpen = cursor + 1;
                int fbClose = findMatchingBrace(tokens, fbOpen);
                executeBlock(frame, tokens.subList(fbOpen + 1, fbClose));
                cursor = fbClose + 1;
            }
            return cursor;
        }

        if ("return".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            int semi = findStatementSemicolon(tokens, idx + 1);
            Object val = semi > idx + 1 ? new ExprEvaluator(frame, tokens.subList(idx + 1, semi)).eval() : null;
            throw new ControlSignal(ControlSignal.Kind.RETURN, val);
        }

        if ("break".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            throw new ControlSignal(ControlSignal.Kind.BREAK, null);
        }

        if ("continue".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            throw new ControlSignal(ControlSignal.Kind.CONTINUE, null);
        }

        if ("throw".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            int semi = findStatementSemicolon(tokens, idx + 1);
            Object val = new ExprEvaluator(frame, tokens.subList(idx + 1, semi)).eval();
            throw new RuntimeException(String.valueOf(val));
        }

        if ("yield".equals(first.text)) {
            maybePauseAtLine(frame, first.line);
            int semi = findStatementSemicolon(tokens, idx + 1);
            Object val = new ExprEvaluator(frame, tokens.subList(idx + 1, semi)).eval();
            throw new ControlSignal(ControlSignal.Kind.RETURN, val);
        }

        // Standard declaration or expression statement ending with ';'
        int semi = findStatementSemicolon(tokens, idx);
        maybePauseAtLine(frame, first.line);
        executeInlineExprOrDecl(frame, tokens.subList(idx, semi));
        return Math.min(tokens.size(), semi + 1);
    }

    private void executeSwitchBlock(Frame frame, Object switchVal, List<Token> body) {
        int i = 0;
        boolean matched = false;
        while (i < body.size()) {
            String t = body.get(i).text;
            if ("case".equals(t) || "default".equals(t)) {
                boolean isDefault = "default".equals(t);
                int arrowOrColon = findTopLevelTokenAny(body, i + 1, "->", ":");
                if (arrowOrColon < 0) break;
                boolean hit = isDefault;
                if (!isDefault) {
                    List<Token> caseLabels = body.subList(i + 1, arrowOrColon);
                    for (List<Token> part : splitTopLevelComma(caseLabels)) {
                        Object cv = new ExprEvaluator(frame, part).eval();
                        if (equalsValue(switchVal, cv)) {
                            hit = true;
                            break;
                        }
                    }
                }
                boolean isArrow = "->".equals(body.get(arrowOrColon).text);
                int armStart = arrowOrColon + 1;
                if (isArrow) {
                    int armEnd = findStatementEnd(body, armStart);
                    if (hit && !matched) {
                        matched = true;
                        executeSingleOrBlock(frame, body, armStart);
                        return;
                    }
                    i = armEnd;
                } else {
                    if (hit) matched = true;
                    i = armStart;
                }
            } else {
                if (matched) {
                    try {
                        i = executeStatement(frame, body, i);
                    } catch (ControlSignal sig) {
                        if (sig.kind == ControlSignal.Kind.BREAK) return;
                        throw sig;
                    }
                } else {
                    i++;
                }
            }
        }
    }

    private void executeInlineExprOrDecl(Frame frame, List<Token> stmt) {
        if (stmt.isEmpty()) return;
        // Skip modifiers like 'final'
        int start = 0;
        while (start < stmt.size() && "final".equals(stmt.get(start).text)) {
            start++;
        }
        if (start >= stmt.size()) return;
        List<Token> s = stmt.subList(start, stmt.size());
        if (s.size() >= 2 && isLocalDeclaration(s)) {
            int eqIdx = findTopLevelToken(s, "=");
            if (eqIdx > 0) {
                String varName = s.get(eqIdx - 1).text;
                String varType = joinTokens(s.subList(0, eqIdx - 1));
                Object val = new ExprEvaluator(frame, s.subList(eqIdx + 1, s.size())).eval();
                frame.declare(varName, varType, val);
            } else {
                String varName = s.get(s.size() - 1).text;
                String varType = joinTokens(s.subList(0, s.size() - 1));
                frame.declare(varName, varType, defaultValueForType(varType));
            }
            return;
        }
        new ExprEvaluator(frame, s).eval();
    }

    private boolean isLocalDeclaration(List<Token> s) {
        if (s.size() < 2) return false;
        String first = s.get(0).text;
        if ("=".equals(s.get(1).text) || "+=".equals(s.get(1).text) || "-=".equals(s.get(1).text)
                || "(".equals(s.get(1).text) || ".".equals(s.get(1).text)
                || "++".equals(s.get(1).text) || "--".equals(s.get(1).text)) {
            return false;
        }
        if ("var".equals(first) || isPrimitive(first)) return true;
        int eqIdx = findTopLevelToken(s, "=");
        int nameIdx = eqIdx > 0 ? eqIdx - 1 : s.size() - 1;
        if (nameIdx >= 1 && s.get(nameIdx).type == TokenType.IDENT) {
            String prev = s.get(nameIdx - 1).text;
            return s.get(nameIdx - 1).type == TokenType.IDENT || ">".equals(prev) || "]".equals(prev);
        }
        return false;
    }

    private int skipOrExecuteSingleOrBlock(Frame frame, List<Token> tokens, int start, boolean execute) {
        if (start >= tokens.size()) return start;
        if (execute) {
            return executeSingleOrBlock(frame, tokens, start);
        }
        return findStatementEnd(tokens, start);
    }

    private int executeSingleOrBlock(Frame frame, List<Token> tokens, int start) {
        if (start >= tokens.size()) return start;
        if ("{".equals(tokens.get(start).text)) {
            int end = findMatchingBrace(tokens, start);
            executeBlock(frame, tokens.subList(start + 1, end));
            return end + 1;
        }
        return executeStatement(frame, tokens, start);
    }

    private void maybePauseAtLine(Frame frame, int line) {
        if (stopRequested) throw new StopDebugException();
        if (line <= 0) return;
        frame.currentLine = line;

        String pauseReason = null;
        if (breakpointLookup != null) {
            DebugModels.BreakpointSpec bp = breakpointLookup.findBreakpoint(frame.filePath, frame.fileName, line);
            if (bp != null && bp.enabled) {
                boolean condMet = true;
                if (bp.hasCondition()) {
                    try {
                        Object res = new ExprEvaluator(frame, tokenize(bp.condition, line)).eval();
                        condMet = toBoolean(res);
                    } catch (Throwable ignored) {
                        condMet = false;
                    }
                }
                if (condMet && !(line == lastPausedLine && frame.fileName.equals(lastPausedFile) && stepMode != 0)) {
                    if (listener != null) {
                        listener.onBreakpointHitUpdated(frame.filePath, line);
                    }
                    pauseReason = bp.hasCondition()
                            ? "Breakpoint hit (cond: " + bp.condition + ")"
                            : "Breakpoint hit";
                }
            }
        }

        if (pauseReason == null && pauseRequested) {
            pauseRequested = false;
            pauseReason = "Paused by user";
        }

        int depth;
        synchronized (callStack) {
            depth = callStack.size();
        }

        if (pauseReason == null) {
            if (stepMode == 1) {
                if (line != lastPausedLine || !frame.fileName.equals(lastPausedFile)) {
                    pauseReason = "Step Into";
                }
            } else if (stepMode == 2 && depth <= stepTargetDepth) {
                if (line != lastPausedLine || !frame.fileName.equals(lastPausedFile) || depth < stepTargetDepth) {
                    pauseReason = "Step Over";
                }
            } else if (stepMode == 3 && depth < stepTargetDepth) {
                pauseReason = "Step Out";
            }
        }

        if (pauseReason == null) {
            return;
        }

        lastPausedLine = line;
        lastPausedFile = frame.fileName;
        paused = true;
        List<DebugModels.StackFrameInfo> snapshot = snapshotFrames();
        if (listener != null) {
            listener.onPaused(pauseReason, frame.filePath, frame.fileName, line, snapshot, 0);
        }

        while (!stopRequested) {
            try {
                StepCommand cmd = commandQueue.take();
                if (cmd == StepCommand.STOP) {
                    throw new StopDebugException();
                } else if (cmd == StepCommand.RESUME) {
                    stepMode = 0;
                    stepTargetDepth = Integer.MAX_VALUE;
                    break;
                } else if (cmd == StepCommand.STEP_INTO) {
                    stepMode = 1;
                    stepTargetDepth = Integer.MAX_VALUE;
                    break;
                } else if (cmd == StepCommand.STEP_OVER) {
                    stepMode = 2;
                    stepTargetDepth = depth;
                    break;
                } else if (cmd == StepCommand.STEP_OUT) {
                    stepMode = 3;
                    stepTargetDepth = depth;
                    break;
                }
            } catch (InterruptedException e) {
                if (stopRequested) throw new StopDebugException();
            }
        }
        paused = false;
        if (listener != null) {
            listener.onResumed();
        }
    }

    private List<DebugModels.StackFrameInfo> snapshotFrames() {
        synchronized (callStack) {
            List<DebugModels.StackFrameInfo> list = new ArrayList<>(callStack.size());
            for (Frame f : callStack) {
                list.add(new DebugModels.StackFrameInfo(
                        f.frameId, f.className, f.methodName, f.filePath, f.fileName, f.currentLine));
            }
            return list;
        }
    }

    private ObjectInstance createInstance(ParsedClass pc, List<Object> ctorArgs) {
        ObjectInstance inst = new ObjectInstance(pc);
        if (pc.isRecord) {
            for (int i = 0; i < pc.recordComponents.size(); i++) {
                ParsedParam comp = pc.recordComponents.get(i);
                Object v = i < ctorArgs.size() ? ctorArgs.get(i) : defaultValueForType(comp.type);
                inst.fields.put(comp.name, v);
            }
        }
        for (ParsedField pf : pc.fields) {
            if (!pf.isStatic) {
                Object val = pf.initExprTokens.isEmpty()
                        ? defaultValueForType(pf.type)
                        : evalStandalone(pc, inst, pf.initExprTokens);
                inst.fields.put(pf.name, val);
            }
        }
        ParsedMethod ctor = pc.findMethod(pc.simpleName, ctorArgs.size());
        if (ctor != null && ctor.isConstructor) {
            invokeMethod(inst, pc, ctor, ctorArgs);
        }
        return inst;
    }

    private Object evalStandalone(ParsedClass pc, ObjectInstance inst, List<Token> tokens) {
        Frame temp = new Frame(frameIdSeq++, pc.simpleName, "<init>", pc.sourceFile,
                tokens.isEmpty() ? 1 : tokens.get(0).line, inst);
        return new ExprEvaluator(temp, tokens).eval();
    }

    private final class ExprEvaluator {
        private final Frame frame;
        private final List<Token> tokens;
        private int pos = 0;

        ExprEvaluator(Frame frame, List<Token> tokens) {
            this.frame = frame;
            this.tokens = tokens;
        }

        Object eval() {
            if (tokens.isEmpty()) return null;
            return parseAssignment();
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : new Token(TokenType.EOF, "", frame.currentLine);
        }

        private Token peekAt(int offset) {
            int i = pos + offset;
            return (i >= 0 && i < tokens.size()) ? tokens.get(i) : new Token(TokenType.EOF, "", frame.currentLine);
        }

        private Token consume() {
            return pos < tokens.size() ? tokens.get(pos++) : new Token(TokenType.EOF, "", frame.currentLine);
        }

        private boolean match(String text) {
            if (text.equals(peek().text)) {
                pos++;
                return true;
            }
            return false;
        }

        private Object parseAssignment() {
            // Handle lambda: v -> ... or (a, b) -> ...
            LambdaClosure lambda = tryParseLambda();
            if (lambda != null) return lambda;

            int saved = pos;
            if (peek().type == TokenType.IDENT || "this".equals(peek().text)) {
                String targetName = peek().text;
                String second = peekAt(1).text;
                if ("=".equals(second) || "+=".equals(second) || "-=".equals(second)
                        || "*=".equals(second) || "/=".equals(second) || "%=".equals(second)) {
                    pos += 2;
                    Object rhs = parseAssignment();
                    Object cur = readVar(targetName);
                    Object next = applyCompound(second, cur, rhs);
                    writeVar(targetName, next);
                    return next;
                }
                if ("this".equals(targetName) && ".".equals(second) && peekAt(2).type == TokenType.IDENT) {
                    String third = peekAt(3).text;
                    if ("=".equals(third) || "+=".equals(third) || "-=".equals(third)
                            || "*=".equals(third) || "/=".equals(third)) {
                        String fieldName = peekAt(2).text;
                        pos += 4;
                        Object rhs = parseAssignment();
                        if (frame.thisInstance != null) {
                            Object next = applyCompound(third, frame.thisInstance.fields.get(fieldName), rhs);
                            frame.thisInstance.fields.put(fieldName, next);
                            return next;
                        }
                    }
                }
            }
            pos = saved;
            return parseTernary();
        }

        private LambdaClosure tryParseLambda() {
            int saved = pos;
            if (peek().type == TokenType.IDENT && "->".equals(peekAt(1).text)) {
                String p = consume().text;
                consume(); // ->
                List<Token> body = consumeLambdaBody();
                return new LambdaClosure(Collections.singletonList(p), body, frame, new File(frame.filePath));
            }
            if ("(".equals(peek().text)) {
                int close = findMatchingParen(tokens, pos);
                if (close > pos && close + 1 < tokens.size() && "->".equals(tokens.get(close + 1).text)) {
                    List<String> params = new ArrayList<>();
                    for (int i = pos + 1; i < close; i++) {
                        if (tokens.get(i).type == TokenType.IDENT
                                && (i + 1 == close || ",".equals(tokens.get(i + 1).text))) {
                            params.add(tokens.get(i).text);
                        }
                    }
                    pos = close + 2;
                    List<Token> body = consumeLambdaBody();
                    return new LambdaClosure(params, body, frame, new File(frame.filePath));
                }
            }
            pos = saved;
            return null;
        }

        private List<Token> consumeLambdaBody() {
            if ("{".equals(peek().text)) {
                int end = findMatchingBrace(tokens, pos);
                List<Token> slice = new ArrayList<>(tokens.subList(pos, end + 1));
                pos = end + 1;
                return slice;
            }
            List<Token> slice = new ArrayList<>(tokens.subList(pos, tokens.size()));
            pos = tokens.size();
            return slice;
        }

        private Object parseTernary() {
            Object cond = parseOr();
            if (match("?")) {
                Object tVal = parseAssignment();
                match(":");
                Object fVal = parseAssignment();
                return toBoolean(cond) ? tVal : fVal;
            }
            return cond;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (match("||")) {
                if (toBoolean(left)) {
                    parseAnd(); // skip
                    left = Boolean.TRUE;
                } else {
                    left = toBoolean(parseAnd());
                }
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseEq();
            while (match("&&")) {
                if (!toBoolean(left)) {
                    parseEq();
                    left = Boolean.FALSE;
                } else {
                    left = toBoolean(parseEq());
                }
            }
            return left;
        }

        private Object parseEq() {
            Object left = parseRel();
            while ("==".equals(peek().text) || "!=".equals(peek().text)) {
                String op = consume().text;
                Object right = parseRel();
                boolean eq = equalsValue(left, right);
                left = "==".equals(op) ? eq : !eq;
            }
            return left;
        }

        private Object parseRel() {
            Object left = parseAdd();
            while (true) {
                String op = peek().text;
                if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
                    consume();
                    Object right = parseAdd();
                    double a = toDouble(left);
                    double b = toDouble(right);
                    switch (op) {
                        case "<": left = a < b; break;
                        case "<=": left = a <= b; break;
                        case ">": left = a > b; break;
                        default: left = a >= b; break;
                    }
                } else if ("instanceof".equals(op)) {
                    consume();
                    String typeName = consume().text;
                    if (peek().type == TokenType.IDENT && !"&&".equals(peek().text) && !"||".equals(peek().text)
                            && !")".equals(peek().text) && !"?".equals(peek().text)) {
                        String patVar = consume().text;
                        boolean matches = isInstanceOf(left, typeName);
                        if (matches) frame.declare(patVar, typeName, left);
                        left = matches;
                    } else {
                        left = isInstanceOf(left, typeName);
                    }
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseAdd() {
            Object left = parseMul();
            while ("+".equals(peek().text) || "-".equals(peek().text)) {
                String op = consume().text;
                Object right = parseMul();
                if ("+".equals(op)) {
                    if (left instanceof String || right instanceof String) {
                        left = String.valueOf(left) + String.valueOf(right);
                    } else {
                        left = numericBinary(left, right, '+');
                    }
                } else {
                    left = numericBinary(left, right, '-');
                }
            }
            return left;
        }

        private Object parseMul() {
            Object left = parseUnary();
            while ("*".equals(peek().text) || "/".equals(peek().text) || "%".equals(peek().text)) {
                char op = consume().text.charAt(0);
                Object right = parseUnary();
                left = numericBinary(left, right, op);
            }
            return left;
        }

        private Object parseUnary() {
            String op = peek().text;
            if ("!".equals(op)) {
                consume();
                return !toBoolean(parseUnary());
            }
            if ("-".equals(op)) {
                consume();
                return negateNumber(parseUnary());
            }
            if ("+".equals(op)) {
                consume();
                return parseUnary();
            }
            if ("++".equals(op) || "--".equals(op)) {
                consume();
                String name = consume().text;
                Object cur = readVar(name);
                Object next = numericBinary(cur, 1, "++".equals(op) ? '+' : '-');
                writeVar(name, next);
                return next;
            }
            // Cast (Type) expr
            if ("(".equals(op) && pos + 2 < tokens.size() && ")".equals(peekAt(2).text)
                    && peekAt(1).type == TokenType.IDENT && !isKnownValueToken(peekAt(1).text)
                    && pos + 3 < tokens.size()) {
                String castType = peekAt(1).text;
                if (isPrimitive(castType) || Character.isUpperCase(castType.charAt(0))) {
                    pos += 3;
                    return castValue(parseUnary(), castType);
                }
            }
            return parsePostfix();
        }

        private Object parsePostfix() {
            Object target = parsePrimary();
            while (pos < tokens.size()) {
                String t = peek().text;
                if ("++".equals(t) || "--".equals(t)) {
                    consume();
                    if (pos >= 2) {
                        String varName = tokens.get(pos - 2).text;
                        if (frame.hasLocal(varName) || hasField(varName)) {
                            Object cur = readVar(varName);
                            Object next = numericBinary(cur, 1, "++".equals(t) ? '+' : '-');
                            writeVar(varName, next);
                            return cur;
                        }
                    }
                    return target;
                } else if (".".equals(t)) {
                    consume();
                    String member = consume().text;
                    if ("(".equals(peek().text)) {
                        List<Object> args = parseArgList();
                        target = dispatchMethodCall(target, member, args);
                    } else {
                        target = readMemberField(target, member);
                    }
                } else if ("[".equals(t)) {
                    consume();
                    Object idxObj = parseAssignment();
                    match("]");
                    int idx = (int) toLong(idxObj);
                    if (target instanceof List<?>) {
                        List<?> list = (List<?>) target;
                        target = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                    } else if (target != null && target.getClass().isArray()) {
                        target = java.lang.reflect.Array.get(target, idx);
                    }
                } else {
                    break;
                }
            }
            return target;
        }

        private Object parsePrimary() {
            Token t = peek();
            if ("(".equals(t.text)) {
                consume();
                Object val = parseAssignment();
                match(")");
                return val;
            }
            if ("switch".equals(t.text)) {
                consume();
                match("(");
                Object key = parseAssignment();
                match(")");
                int bOpen = pos;
                int bClose = findMatchingBrace(tokens, bOpen);
                pos = bClose + 1;
                return evalSwitchExpression(key, tokens.subList(bOpen + 1, bClose));
            }
            if ("new".equals(t.text)) {
                consume();
                return parseNewExpression();
            }
            if (t.type == TokenType.NUMBER) {
                consume();
                return parseNumberLiteral(t.text);
            }
            if (t.type == TokenType.STRING) {
                consume();
                return unescapeStringLiteral(t.text);
            }
            if (t.type == TokenType.CHAR) {
                consume();
                return t.text.length() >= 3 ? t.text.charAt(1) : '\0';
            }
            if ("true".equals(t.text)) { consume(); return Boolean.TRUE; }
            if ("false".equals(t.text)) { consume(); return Boolean.FALSE; }
            if ("null".equals(t.text)) { consume(); return null; }
            if ("this".equals(t.text)) {
                consume();
                return frame.thisInstance != null ? frame.thisInstance : "<this:" + frame.className + ">";
            }
            if (t.type == TokenType.IDENT) {
                String name = consume().text;
                if ("(".equals(peek().text)) {
                    List<Object> args = parseArgList();
                    return dispatchUnqualifiedCall(name, args);
                }
                if ("R".equals(name)) {
                    if (".".equals(peek().text)) {
                        consume();
                        String ns = consume().text;
                        if (".".equals(peek().text)) {
                            consume();
                            String resName = consume().text;
                            return "R." + ns + "." + resName;
                        }
                    }
                    return "R";
                }
                return resolveIdentifier(name);
            }
            consume();
            return null;
        }

        private Object evalSwitchExpression(Object key, List<Token> body) {
            int i = 0;
            while (i < body.size()) {
                String t = body.get(i).text;
                if ("case".equals(t) || "default".equals(t)) {
                    boolean isDef = "default".equals(t);
                    int sep = findTopLevelTokenAny(body, i + 1, "->", ":");
                    if (sep < 0) break;
                    boolean hit = isDef;
                    if (!isDef) {
                        for (List<Token> p : splitTopLevelComma(body.subList(i + 1, sep))) {
                            if (equalsValue(key, new ExprEvaluator(frame, p).eval())) {
                                hit = true;
                                break;
                            }
                        }
                    }
                    int armStart = sep + 1;
                    if (hit) {
                        if ("{".equals(body.get(armStart).text)) {
                            int end = findMatchingBrace(body, armStart);
                            try {
                                executeBlock(frame, body.subList(armStart + 1, end));
                            } catch (ControlSignal sig) {
                                if (sig.kind == ControlSignal.Kind.RETURN) return sig.value;
                            }
                            return null;
                        } else {
                            int semi = findStatementSemicolon(body, armStart);
                            return new ExprEvaluator(frame, body.subList(armStart, semi)).eval();
                        }
                    }
                    i = findStatementEnd(body, armStart);
                } else {
                    i++;
                }
            }
            return null;
        }

        private Object parseNewExpression() {
            StringBuilder className = new StringBuilder(consume().text);
            while (".".equals(peek().text)) {
                consume();
                className.append('.').append(consume().text);
            }
            if ("<".equals(peek().text)) {
                int depth = 1;
                consume();
                while (pos < tokens.size() && depth > 0) {
                    String g = consume().text;
                    if ("<".equals(g)) depth++;
                    else if (">".equals(g)) depth--;
                }
            }
            if ("[".equals(peek().text)) {
                consume();
                int len = 0;
                if (!"]".equals(peek().text)) {
                    len = (int) toLong(parseAssignment());
                }
                match("]");
                Object[] arr = new Object[Math.max(0, len)];
                if ("{".equals(peek().text)) {
                    int bEnd = findMatchingBrace(tokens, pos);
                    pos = bEnd + 1;
                }
                return new ArrayList<>(Arrays.asList(arr));
            }
            List<Object> args = "(".equals(peek().text) ? parseArgList() : Collections.emptyList();
            if ("{".equals(peek().text)) {
                int bEnd = findMatchingBrace(tokens, pos);
                List<Token> anonBody = tokens.subList(pos + 1, bEnd);
                pos = bEnd + 1;
                ParsedClass anonCls = parseAnonymousClass("Anon_" + frameIdSeq++, anonBody, new File(frame.filePath));
                return new ObjectInstance(anonCls);
            }
            String full = className.toString();
            String simple = full.contains(".") ? full.substring(full.lastIndexOf('.') + 1) : full;
            if ("ArrayList".equals(simple) || "LinkedList".equals(simple)) return new ArrayList<>();
            if ("HashMap".equals(simple) || "LinkedHashMap".equals(simple)) return new LinkedHashMap<>();
            if ("StringBuilder".equals(simple)) return new StringBuilder(args.isEmpty() ? "" : String.valueOf(args.get(0)));
            if ("Bundle".equals(simple)) return new LinkedHashMap<String, Object>();
            if ("Intent".equals(simple)) return new LinkedHashMap<String, Object>();
            ParsedClass pc = classesBySimpleName.get(simple);
            if (pc != null) {
                return createInstance(pc, args);
            }
            return new WidgetInstance(simple, simple);
        }

        private List<Object> parseArgList() {
            List<Object> args = new ArrayList<>();
            if (!match("(")) return args;
            if (match(")")) return args;
            while (pos < tokens.size()) {
                args.add(parseAssignment());
                if (match(")")) break;
                if (!match(",")) break;
            }
            return args;
        }

        private Object resolveIdentifier(String name) {
            if (frame.hasLocal(name)) return frame.getLocal(name);
            if (frame.thisInstance != null && frame.thisInstance.fields.containsKey(name)) {
                return frame.thisInstance.fields.get(name);
            }
            ParsedClass owner = classesBySimpleName.get(frame.className);
            if (owner != null && owner.staticFields.containsKey(name)) {
                return owner.staticFields.get(name);
            }
            if (classesBySimpleName.containsKey(name)) {
                return classesBySimpleName.get(name);
            }
            if ("Math".equals(name) || "Log".equals(name) || "Toast".equals(name)
                    || "String".equals(name) || "Integer".equals(name) || "Double".equals(name)
                    || "System".equals(name) || "List".equals(name) || "Map".equals(name)
                    || "View".equals(name)) {
                return name;
            }
            return null;
        }

        private Object readVar(String name) {
            return resolveIdentifier(name);
        }

        private boolean hasField(String name) {
            return (frame.thisInstance != null && frame.thisInstance.fields.containsKey(name))
                    || (classesBySimpleName.get(frame.className) != null
                    && classesBySimpleName.get(frame.className).staticFields.containsKey(name));
        }

        private void writeVar(String name, Object val) {
            if (frame.hasLocal(name)) {
                frame.setLocal(name, val);
                return;
            }
            if (frame.thisInstance != null) {
                frame.thisInstance.fields.put(name, val);
                return;
            }
            ParsedClass owner = classesBySimpleName.get(frame.className);
            if (owner != null) {
                owner.staticFields.put(name, val);
            } else {
                frame.declare(name, inferType(val), val);
            }
        }

        private Object dispatchUnqualifiedCall(String method, List<Object> args) {
            if ("findViewById".equals(method)) {
                String id = args.isEmpty() ? "view" : String.valueOf(args.get(0));
                return uiWidgets.computeIfAbsent(id, k -> new WidgetInstance(k, "View"));
            }
            if ("setContentView".equals(method) || "onCreate".equals(method)) {
                return null;
            }
            if (frame.thisInstance != null) {
                ParsedMethod pm = frame.thisInstance.parsedClass.findMethod(method, args.size());
                if (pm != null) return invokeMethod(frame.thisInstance, frame.thisInstance.parsedClass, pm, args);
            }
            ParsedClass owner = classesBySimpleName.get(frame.className);
            if (owner != null) {
                ParsedMethod pm = owner.findMethod(method, args.size());
                if (pm != null) return invokeMethod(frame.thisInstance, owner, pm, args);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private Object dispatchMethodCall(Object target, String method, List<Object> args) {
            if ("Toast".equals(target) && "makeText".equals(method)) {
                String msg = args.size() >= 2 ? String.valueOf(args.get(1)) : "";
                return "TOAST:" + msg;
            }
            if (target instanceof String && ((String) target).startsWith("TOAST:") && "show".equals(method)) {
                logConsole("[Toast] " + ((String) target).substring(6));
                return null;
            }
            if ("Log".equals(target) && args.size() >= 2) {
                logConsole("[" + method.toUpperCase(Locale.ROOT) + "/" + args.get(0) + "] " + args.get(1));
                return 0;
            }
            if ("System.out".equals(target) && ("println".equals(method) || "print".equals(method))) {
                logConsole(args.isEmpty() ? "" : String.valueOf(args.get(0)));
                return null;
            }
            if ("Math".equals(target)) {
                return evalMath(method, args);
            }
            if ("String".equals(target) && "valueOf".equals(method)) {
                return args.isEmpty() ? "null" : String.valueOf(args.get(0));
            }
            if ("String".equals(target) && "format".equals(method) && !args.isEmpty()) {
                return String.valueOf(args.get(0));
            }
            if ("Integer".equals(target) && "parseInt".equals(method) && !args.isEmpty()) {
                return Integer.parseInt(String.valueOf(args.get(0)).trim());
            }
            if ("List".equals(target) && "of".equals(method)) {
                return new ArrayList<>(args);
            }
            if ("Map".equals(target) && "of".equals(method)) {
                Map<Object, Object> m = new LinkedHashMap<>();
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    m.put(args.get(i), args.get(i + 1));
                }
                return m;
            }
            if (target instanceof WidgetInstance) {
                WidgetInstance w = (WidgetInstance) target;
                if ("setText".equals(method) && !args.isEmpty()) {
                    w.text = String.valueOf(args.get(0));
                    logConsole("[Widget " + w.idName + "] text = \"" + w.text + "\"");
                    return null;
                }
                if ("getText".equals(method)) return w.text;
                if ("setOnClickListener".equals(method) && !args.isEmpty()) {
                    Object l = args.get(0);
                    if (l instanceof LambdaClosure) {
                        w.onClickListener = (LambdaClosure) l;
                    } else if (l instanceof ObjectInstance) {
                        w.anonListenerInstance = (ObjectInstance) l;
                        w.anonListenerClass = ((ObjectInstance) l).parsedClass;
                    }
                    return null;
                }
            }
            if (target instanceof ParsedClass) {
                ParsedClass pc = (ParsedClass) target;
                ParsedMethod pm = pc.findMethod(method, args.size());
                if (pm != null) return invokeMethod(null, pc, pm, args);
            }
            if (target instanceof ObjectInstance) {
                ObjectInstance inst = (ObjectInstance) target;
                if (inst.parsedClass.isRecord && args.isEmpty() && inst.fields.containsKey(method)) {
                    return inst.fields.get(method);
                }
                ParsedMethod pm = inst.parsedClass.findMethod(method, args.size());
                if (pm != null) return invokeMethod(inst, inst.parsedClass, pm, args);
            }
            if (target instanceof List<?>) {
                List<Object> list = (List<Object>) target;
                switch (method) {
                    case "add": return args.size() == 1 ? list.add(args.get(0)) : false;
                    case "get": return list.get((int) toLong(args.get(0)));
                    case "set": return list.set((int) toLong(args.get(0)), args.get(1));
                    case "size": return list.size();
                    case "isEmpty": return list.isEmpty();
                    case "clear": list.clear(); return null;
                    case "contains": return list.contains(args.get(0));
                }
            }
            if (target instanceof Map<?, ?>) {
                Map<Object, Object> map = (Map<Object, Object>) target;
                switch (method) {
                    case "put": return map.put(args.get(0), args.get(1));
                    case "get": return map.get(args.get(0));
                    case "size": return map.size();
                    case "isEmpty": return map.isEmpty();
                    case "containsKey": return map.containsKey(args.get(0));
                    case "putString": return map.put(args.get(0), args.get(1));
                    case "getString": return map.getOrDefault(args.get(0), args.size() > 1 ? args.get(1) : null);
                }
            }
            if (target instanceof String) {
                String s = (String) target;
                switch (method) {
                    case "length": return s.length();
                    case "trim": return s.trim();
                    case "isEmpty": return s.isEmpty();
                    case "toUpperCase": return s.toUpperCase(Locale.ROOT);
                    case "toLowerCase": return s.toLowerCase(Locale.ROOT);
                    case "equals": return !args.isEmpty() && s.equals(args.get(0));
                    case "contains": return !args.isEmpty() && s.contains(String.valueOf(args.get(0)));
                    case "startsWith": return !args.isEmpty() && s.startsWith(String.valueOf(args.get(0)));
                    case "substring":
                        return args.size() == 1
                                ? s.substring((int) toLong(args.get(0)))
                                : s.substring((int) toLong(args.get(0)), (int) toLong(args.get(1)));
                }
            }
            if (target instanceof StringBuilder) {
                StringBuilder sb = (StringBuilder) target;
                if ("append".equals(method) && !args.isEmpty()) return sb.append(args.get(0));
                if ("toString".equals(method)) return sb.toString();
            }
            return target;
        }

        private Object readMemberField(Object target, String field) {
            if ("System".equals(target) && "out".equals(field)) return "System.out";
            if ("View".equals(target)) {
                if ("VISIBLE".equals(field)) return 0;
                if ("INVISIBLE".equals(field)) return 4;
                if ("GONE".equals(field)) return 8;
            }
            if (target instanceof ObjectInstance) {
                ObjectInstance inst = (ObjectInstance) target;
                if (inst.fields.containsKey(field)) return inst.fields.get(field);
            }
            if (target instanceof ParsedClass) {
                return ((ParsedClass) target).staticFields.get(field);
            }
            if (target instanceof List<?> && "length".equals(field)) {
                return ((List<?>) target).size();
            }
            return null;
        }

        private boolean isKnownValueToken(String t) {
            return "true".equals(t) || "false".equals(t) || "null".equals(t) || "this".equals(t)
                    || frame.hasLocal(t) || hasField(t);
        }
    }

    private DebugModels.DebugVariable toDebugVariable(
            String name, String declaredType, Object value, String scope, boolean editable, int depth) {
        List<DebugModels.DebugVariable> children = Collections.emptyList();
        if (depth < 2) {
            if (value instanceof ObjectInstance) {
                ObjectInstance oi = (ObjectInstance) value;
                List<DebugModels.DebugVariable> list = new ArrayList<>();
                for (Map.Entry<String, Object> fe : oi.fields.entrySet()) {
                    list.add(toDebugVariable(fe.getKey(), inferType(fe.getValue()), fe.getValue(), "Field", true, depth + 1));
                }
                children = list;
            } else if (value instanceof WidgetInstance) {
                WidgetInstance w = (WidgetInstance) value;
                children = Arrays.asList(
                        new DebugModels.DebugVariable("id", "String", "\"" + w.idName + "\"", "Field", false, null),
                        new DebugModels.DebugVariable("text", "String", "\"" + w.text + "\"", "Field", false, null)
                );
            } else if (value instanceof List<?>) {
                List<?> l = (List<?>) value;
                List<DebugModels.DebugVariable> list = new ArrayList<>();
                int max = Math.min(l.size(), 25);
                for (int i = 0; i < max; i++) {
                    Object item = l.get(i);
                    list.add(toDebugVariable("[" + i + "]", inferType(item), item, "Element", false, depth + 1));
                }
                children = list;
            } else if (value instanceof Map<?, ?>) {
                Map<?, ?> m = (Map<?, ?>) value;
                List<DebugModels.DebugVariable> list = new ArrayList<>();
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (i++ >= 25) break;
                    list.add(toDebugVariable(String.valueOf(e.getKey()), inferType(e.getValue()), e.getValue(), "Entry", false, depth + 1));
                }
                children = list;
            }
        }
        String typeStr = declaredType != null && !"var".equals(declaredType) ? declaredType : inferType(value);
        return new DebugModels.DebugVariable(name, typeStr, formatValue(value), scope, editable, children);
    }

    private void logConsole(String msg) {
        SandrixLogcatBus.log("I", "SandrixDebugVM", msg);
        if (listener != null) {
            listener.onConsoleOutput(msg);
        }
    }

    // --- Java source class/method parser with line preservation ---

    private static List<ParsedClass> parseClassesFromFile(File file, String source) {
        List<Token> all = tokenize(source, 1);
        String pkg = "";
        int i = 0;
        while (i < all.size()) {
            if ("package".equals(all.get(i).text)) {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < all.size() && !";".equals(all.get(i).text)) {
                    sb.append(all.get(i).text);
                    i++;
                }
                pkg = sb.toString();
            } else if ("import".equals(all.get(i).text)) {
                while (i < all.size() && !";".equals(all.get(i).text)) i++;
                i++;
            } else {
                break;
            }
            if (i < all.size() && ";".equals(all.get(i).text)) i++;
        }

        List<ParsedClass> result = new ArrayList<>();
        while (i < all.size()) {
            String t = all.get(i).text;
            if ("class".equals(t) || "record".equals(t) || "interface".equals(t) || "enum".equals(t)) {
                boolean isRecord = "record".equals(t);
                String clsName = (i + 1 < all.size()) ? all.get(i + 1).text : "Main";
                int cursor = i + 2;
                List<ParsedParam> recParams = new ArrayList<>();
                if (isRecord && cursor < all.size() && "(".equals(all.get(cursor).text)) {
                    int cp = findMatchingParen(all, cursor);
                    recParams = parseParamTokens(all.subList(cursor + 1, cp));
                    cursor = cp + 1;
                }
                while (cursor < all.size() && !"{".equals(all.get(cursor).text)) cursor++;
                if (cursor < all.size()) {
                    int bodyEnd = findMatchingBrace(all, cursor);
                    String full = pkg.isEmpty() ? clsName : (pkg + "." + clsName);
                    ParsedClass pc = new ParsedClass(clsName, full, file, isRecord, recParams);
                    parseClassMembers(pc, all.subList(cursor + 1, bodyEnd), file);
                    result.add(pc);
                    i = bodyEnd + 1;
                    continue;
                }
            }
            i++;
        }
        return result;
    }

    private static ParsedClass parseAnonymousClass(String name, List<Token> bodyTokens, File file) {
        ParsedClass pc = new ParsedClass(name, name, file, false, null);
        parseClassMembers(pc, bodyTokens, file);
        return pc;
    }

    private static void parseClassMembers(ParsedClass pc, List<Token> members, File file) {
        int i = 0;
        while (i < members.size()) {
            boolean isStatic = false;
            int headerStart = i;
            // Skip annotations
            while (i < members.size() && "@".equals(members.get(i).text)) {
                i += 2;
                if (i < members.size() && "(".equals(members.get(i).text)) {
                    i = findMatchingParen(members, i) + 1;
                }
            }
            headerStart = i;
            while (i < members.size()) {
                String tok = members.get(i).text;
                if ("static".equals(tok)) isStatic = true;
                if ("{".equals(tok) || ";".equals(tok) || "(".equals(tok)) break;
                i++;
            }
            if (i >= members.size()) break;
            String stop = members.get(i).text;
            if ("(".equals(stop)) {
                int closeParen = findMatchingParen(members, i);
                List<ParsedParam> params = parseParamTokens(members.subList(i + 1, closeParen));
                int braceIdx = closeParen + 1;
                while (braceIdx < members.size() && !"{".equals(members.get(braceIdx).text)
                        && !";".equals(members.get(braceIdx).text)) {
                    braceIdx++;
                }
                if (braceIdx < members.size() && "{".equals(members.get(braceIdx).text)) {
                    int endBrace = findMatchingBrace(members, braceIdx);
                    String methodName = members.get(i - 1).text;
                    boolean isCtor = methodName.equals(pc.simpleName);
                    String retType = isCtor ? methodName : findReturnType(members, headerStart, i - 1);
                    List<Token> bodySlice = new ArrayList<>(members.subList(braceIdx + 1, endBrace));
                    int sLine = members.get(braceIdx).line;
                    int eLine = members.get(endBrace).line;
                    pc.methods.add(new ParsedMethod(methodName, retType, params, sLine, eLine, bodySlice, isStatic, isCtor));
                    i = endBrace + 1;
                    continue;
                } else {
                    i = braceIdx + 1;
                    continue;
                }
            } else if (";".equals(stop)) {
                // Field declaration
                int eq = findTopLevelToken(members.subList(headerStart, i), "=");
                int fieldEnd = i;
                int nameIdx = eq >= 0 ? (headerStart + eq - 1) : (fieldEnd - 1);
                if (nameIdx > headerStart) {
                    String name = members.get(nameIdx).text;
                    String type = members.get(Math.max(headerStart, nameIdx - 1)).text;
                    List<Token> init = eq >= 0
                            ? new ArrayList<>(members.subList(headerStart + eq + 1, fieldEnd))
                            : Collections.emptyList();
                    pc.fields.add(new ParsedField(type, name, init, isStatic, members.get(headerStart).line));
                }
                i = fieldEnd + 1;
            } else if ("{".equals(stop)) {
                int end = findMatchingBrace(members, i);
                i = end + 1;
            }
        }
    }

    private static String findReturnType(List<Token> toks, int start, int nameIdx) {
        if (nameIdx <= start) return "void";
        return toks.get(nameIdx - 1).text;
    }

    private static List<ParsedParam> parseParamTokens(List<Token> toks) {
        if (toks.isEmpty()) return Collections.emptyList();
        List<ParsedParam> params = new ArrayList<>();
        for (List<Token> part : splitTopLevelComma(toks)) {
            if (part.size() >= 2) {
                String name = part.get(part.size() - 1).text;
                StringBuilder type = new StringBuilder();
                for (int i = 0; i < part.size() - 1; i++) {
                    String s = part.get(i).text;
                    if (!"final".equals(s)) type.append(s);
                }
                params.add(new ParsedParam(type.toString(), name));
            }
        }
        return params;
    }

    // --- Tokenizer preserving exact 1-based line numbers ---

    private static List<Token> tokenize(String src, int initialLine) {
        List<Token> list = new ArrayList<>();
        int n = src.length();
        int i = 0;
        int line = initialLine;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            // Text block """..."""
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int startLine = line;
                int start = i;
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                i = Math.min(n, i + 3);
                list.add(new Token(TokenType.STRING, src.substring(start, i), startLine));
                continue;
            }
            if (c == '"') {
                int start = i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\' && i + 1 < n) i++;
                    else if (src.charAt(i) == '\n') line++;
                    i++;
                }
                if (i < n) i++;
                list.add(new Token(TokenType.STRING, src.substring(start, i), line));
                continue;
            }
            if (c == '\'') {
                int start = i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++;
                list.add(new Token(TokenType.CHAR, src.substring(start, i), line));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i++;
                while (i < n) {
                    char ch = src.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_') i++;
                    else break;
                }
                list.add(new Token(TokenType.NUMBER, src.substring(start, i), line));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) i++;
                list.add(new Token(TokenType.IDENT, src.substring(start, i), line));
                continue;
            }
            // Two-char operators
            if (i + 1 < n) {
                String two = src.substring(i, i + 2);
                if ("==".equals(two) || "!=".equals(two) || "<=".equals(two) || ">=".equals(two)
                        || "&&".equals(two) || "||".equals(two) || "++".equals(two) || "--".equals(two)
                        || "+=".equals(two) || "-=".equals(two) || "*=".equals(two) || "/=".equals(two)
                        || "%=".equals(two) || "->".equals(two) || "::".equals(two)) {
                    list.add(new Token(TokenType.SYMBOL, two, line));
                    i += 2;
                    continue;
                }
            }
            list.add(new Token(TokenType.SYMBOL, String.valueOf(c), line));
            i++;
        }
        return list;
    }

    // --- Token Helpers ---

    private static int findMatchingBrace(List<Token> toks, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < toks.size(); i++) {
            String t = toks.get(i).text;
            if ("{".equals(t)) depth++;
            else if ("}".equals(t)) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return toks.size() - 1;
    }

    private static int findMatchingParen(List<Token> toks, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < toks.size(); i++) {
            String t = toks.get(i).text;
            if ("(".equals(t)) depth++;
            else if (")".equals(t)) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return toks.size() - 1;
    }

    private static int findStatementSemicolon(List<Token> toks, int startIdx) {
        int pDepth = 0;
        int bDepth = 0;
        for (int i = startIdx; i < toks.size(); i++) {
            String t = toks.get(i).text;
            if ("(".equals(t)) pDepth++;
            else if (")".equals(t)) pDepth = Math.max(0, pDepth - 1);
            else if ("{".equals(t)) bDepth++;
            else if ("}".equals(t)) {
                if (bDepth == 0) return i;
                bDepth--;
            } else if (";".equals(t) && pDepth == 0 && bDepth == 0) {
                return i;
            }
        }
        return toks.size();
    }

    private static int findStatementEnd(List<Token> toks, int startIdx) {
        if (startIdx >= toks.size()) return startIdx;
        if ("{".equals(toks.get(startIdx).text)) return findMatchingBrace(toks, startIdx) + 1;
        return Math.min(toks.size(), findStatementSemicolon(toks, startIdx) + 1);
    }

    private static List<Token> stripOuterBraces(List<Token> toks) {
        if (toks.size() >= 2 && "{".equals(toks.get(0).text) && "}".equals(toks.get(toks.size() - 1).text)) {
            return toks.subList(1, toks.size() - 1);
        }
        return toks;
    }

    private static int findTopLevelToken(List<Token> toks, String target) {
        return findTopLevelTokenFrom(toks, target, 0);
    }

    private static int findTopLevelTokenFrom(List<Token> toks, String target, int from) {
        int p = 0, b = 0, s = 0;
        for (int i = from; i < toks.size(); i++) {
            String t = toks.get(i).text;
            if (p == 0 && b == 0 && s == 0 && target.equals(t)) return i;
            if ("(".equals(t)) p++;
            else if (")".equals(t)) p = Math.max(0, p - 1);
            else if ("{".equals(t)) b++;
            else if ("}".equals(t)) b = Math.max(0, b - 1);
            else if ("[".equals(t)) s++;
            else if ("]".equals(t)) s = Math.max(0, s - 1);
        }
        return -1;
    }

    private static int findTopLevelTokenAny(List<Token> toks, int from, String opt1, String opt2) {
        int p = 0, b = 0;
        for (int i = from; i < toks.size(); i++) {
            String t = toks.get(i).text;
            if (p == 0 && b == 0 && (opt1.equals(t) || opt2.equals(t))) return i;
            if ("(".equals(t)) p++;
            else if (")".equals(t)) p = Math.max(0, p - 1);
            else if ("{".equals(t)) b++;
            else if ("}".equals(t)) b = Math.max(0, b - 1);
        }
        return -1;
    }

    private static List<List<Token>> splitTopLevelComma(List<Token> toks) {
        List<List<Token>> out = new ArrayList<>();
        int p = 0, b = 0, start = 0;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i).text;
            if ("(".equals(t) || "<".equals(t) || "[".equals(t)) p++;
            else if (")".equals(t) || ">".equals(t) || "]".equals(t)) p = Math.max(0, p - 1);
            else if ("{".equals(t)) b++;
            else if ("}".equals(t)) b = Math.max(0, b - 1);
            else if (",".equals(t) && p == 0 && b == 0) {
                out.add(toks.subList(start, i));
                start = i + 1;
            }
        }
        if (start < toks.size()) out.add(toks.subList(start, toks.size()));
        return out;
    }

    private static String joinTokens(List<Token> toks) {
        StringBuilder sb = new StringBuilder();
        for (Token t : toks) sb.append(t.text);
        return sb.toString();
    }

    // --- Value & Type Helpers ---

    private static boolean isPrimitive(String t) {
        return "int".equals(t) || "long".equals(t) || "double".equals(t) || "float".equals(t)
                || "boolean".equals(t) || "char".equals(t) || "byte".equals(t) || "short".equals(t);
    }

    private static Object defaultValueForType(String type) {
        if ("int".equals(type) || "short".equals(type) || "byte".equals(type)) return 0;
        if ("long".equals(type)) return 0L;
        if ("double".equals(type)) return 0.0d;
        if ("float".equals(type)) return 0.0f;
        if ("boolean".equals(type)) return Boolean.FALSE;
        if ("char".equals(type)) return '\0';
        return null;
    }

    private static Object parseNumberLiteral(String text) {
        String clean = text.replace("_", "");
        try {
            if (clean.endsWith("L") || clean.endsWith("l")) {
                return Long.decode(clean.substring(0, clean.length() - 1));
            }
            if (clean.endsWith("F") || clean.endsWith("f")) {
                return Float.parseFloat(clean.substring(0, clean.length() - 1));
            }
            if (clean.endsWith("D") || clean.endsWith("d") || clean.contains(".")) {
                return Double.parseDouble(clean.replaceAll("[dD]$", ""));
            }
            return Integer.decode(clean);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String unescapeStringLiteral(String literal) {
        if (literal.startsWith("\"\"\"") && literal.endsWith("\"\"\"") && literal.length() >= 6) {
            return literal.substring(3, literal.length() - 3);
        }
        if (literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"")) {
            return literal.substring(1, literal.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return literal;
    }

    private static Object applyCompound(String op, Object cur, Object rhs) {
        if ("=".equals(op)) return rhs;
        char arith = op.charAt(0);
        if (arith == '+' && (cur instanceof String || rhs instanceof String)) {
            return String.valueOf(cur) + String.valueOf(rhs);
        }
        return numericBinary(cur, rhs, arith);
    }

    private static Object numericBinary(Object a, Object b, char op) {
        if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
            double x = toDouble(a);
            double y = toDouble(b);
            switch (op) {
                case '+': return x + y;
                case '-': return x - y;
                case '*': return x * y;
                case '/': return y != 0 ? x / y : 0.0;
                case '%': return y != 0 ? x % y : 0.0;
            }
        }
        if (a instanceof Long || b instanceof Long) {
            long x = toLong(a);
            long y = toLong(b);
            switch (op) {
                case '+': return x + y;
                case '-': return x - y;
                case '*': return x * y;
                case '/': return y != 0 ? x / y : 0L;
                case '%': return y != 0 ? x % y : 0L;
            }
        }
        int x = (int) toLong(a);
        int y = (int) toLong(b);
        switch (op) {
            case '+': return x + y;
            case '-': return x - y;
            case '*': return x * y;
            case '/': return y != 0 ? x / y : 0;
            case '%': return y != 0 ? x % y : 0;
            default: return 0;
        }
    }

    private static Object negateNumber(Object a) {
        if (a instanceof Double) return -((Double) a);
        if (a instanceof Float) return -((Float) a);
        if (a instanceof Long) return -((Long) a);
        return -((int) toLong(a));
    }

    private static Object castValue(Object val, String type) {
        if ("int".equals(type) || "Integer".equals(type)) return (int) toLong(val);
        if ("long".equals(type) || "Long".equals(type)) return toLong(val);
        if ("double".equals(type) || "Double".equals(type)) return toDouble(val);
        if ("float".equals(type) || "Float".equals(type)) return (float) toDouble(val);
        if ("String".equals(type)) return val == null ? null : String.valueOf(val);
        return val;
    }

    private static Object evalMath(String method, List<Object> args) {
        double a = args.isEmpty() ? 0 : toDouble(args.get(0));
        double b = args.size() > 1 ? toDouble(args.get(1)) : 0;
        switch (method) {
            case "max": return Math.max(a, b);
            case "min": return Math.min(a, b);
            case "abs": return Math.abs(a);
            case "sqrt": return Math.sqrt(a);
            case "pow": return Math.pow(a, b);
            case "round": return Math.round(a);
            default: return 0;
        }
    }

    private static boolean isInstanceOf(Object val, String typeName) {
        if (val == null) return false;
        if ("String".equals(typeName)) return val instanceof String;
        if ("Integer".equals(typeName)) return val instanceof Integer;
        if ("Double".equals(typeName)) return val instanceof Double;
        if ("Number".equals(typeName)) return val instanceof Number;
        if ("List".equals(typeName)) return val instanceof List;
        if ("Map".equals(typeName)) return val instanceof Map;
        if (val instanceof ObjectInstance) {
            return typeName.equals(((ObjectInstance) val).parsedClass.simpleName);
        }
        return false;
    }

    private static boolean equalsValue(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(toDouble(a), toDouble(b)) == 0;
        }
        return a.equals(b);
    }

    private static boolean toBoolean(Object val) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0.0;
        return val != null;
    }

    private static long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof Character) return (Character) val;
        return 0L;
    }

    private static double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof Character) return (Character) val;
        return 0.0;
    }

    private static Iterable<?> toIterable(Object val) {
        if (val instanceof Iterable<?>) return (Iterable<?>) val;
        if (val instanceof Object[]) return Arrays.asList((Object[]) val);
        return Collections.emptyList();
    }

    private static String inferType(Object val) {
        if (val == null) return "null";
        if (val instanceof Integer) return "int";
        if (val instanceof Long) return "long";
        if (val instanceof Double) return "double";
        if (val instanceof Float) return "float";
        if (val instanceof Boolean) return "boolean";
        if (val instanceof String) return "String";
        if (val instanceof List<?>) return "List(" + ((List<?>) val).size() + ")";
        if (val instanceof Map<?, ?>) return "Map(" + ((Map<?, ?>) val).size() + ")";
        if (val instanceof ObjectInstance) return ((ObjectInstance) val).parsedClass.simpleName;
        if (val instanceof WidgetInstance) return ((WidgetInstance) val).typeName;
        return val.getClass().getSimpleName();
    }

    private static String formatValue(Object val) {
        if (val == null) return "null";
        if (val instanceof String) return "\"" + val + "\"";
        return String.valueOf(val);
    }

    private static List<File> collectJavaFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory() && !"build".equals(f.getName()) && !".git".equals(f.getName())) {
                out.addAll(collectJavaFiles(f));
            } else if (f.isFile() && f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
        return out;
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
