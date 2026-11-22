package com.example.sandrix.debug;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates Sandrix Studio's Advanced Debugger across both the Local Step VM backend
 * and the external JDWP TCP client backend, managing breakpoints, watches, call stack,
 * variable updates, and UI state snapshots.
 */
public final class DebugSessionController {

    public interface StateListener {
        void onStateChanged(DebugModels.DebugSessionState state);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService jdwpExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "SandrixJdwpExec"));
    private final LocalJavaDebugEngine localEngine = new LocalJavaDebugEngine();
    private final Object lock = new Object();

    private final Map<String, Map<Integer, DebugModels.BreakpointSpec>> breakpointsByFile = new LinkedHashMap<>();
    private final List<String> watchExpressions = new ArrayList<>();
    private final List<String> consoleLines = new ArrayList<>();

    private JdwpClient jdwpClient;
    private long activeJdwpThreadId = -1L;
    private List<JdwpClient.VariableSlot> cachedJdwpSlots = Collections.emptyList();

    private DebugModels.DebugSessionState currentState = DebugModels.DebugSessionState.idle();
    private StateListener stateListener;

    public DebugSessionController() {
        watchExpressions.add("count");
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
        emitState();
    }

    public DebugModels.DebugSessionState getState() {
        synchronized (lock) {
            return currentState;
        }
    }

    // --- Breakpoint Management (unconditional + conditional + enable/disable) ---

    public boolean toggleBreakpoint(File file, int lineNumber) {
        if (file == null || lineNumber <= 0) return false;
        String path = file.getAbsolutePath();
        boolean added;
        synchronized (lock) {
            Map<Integer, DebugModels.BreakpointSpec> fileBps =
                    breakpointsByFile.computeIfAbsent(path, k -> new LinkedHashMap<>());
            if (fileBps.containsKey(lineNumber)) {
                fileBps.remove(lineNumber);
                if (fileBps.isEmpty()) breakpointsByFile.remove(path);
                added = false;
            } else {
                fileBps.put(lineNumber, new DebugModels.BreakpointSpec(
                        path, file.getName(), lineNumber, "", true, 0));
                added = true;
            }
        }
        return added;
    }

    public void setBreakpointCondition(String filePath, String fileName, int lineNumber, String condition) {
        if (filePath == null || lineNumber <= 0) return;
        synchronized (lock) {
            Map<Integer, DebugModels.BreakpointSpec> fileBps =
                    breakpointsByFile.computeIfAbsent(filePath, k -> new LinkedHashMap<>());
            DebugModels.BreakpointSpec existing = fileBps.get(lineNumber);
            if (existing == null) {
                fileBps.put(lineNumber, new DebugModels.BreakpointSpec(
                        filePath, fileName != null ? fileName : new File(filePath).getName(),
                        lineNumber, condition, true, 0));
            } else {
                fileBps.put(lineNumber, existing.withCondition(condition));
            }
        }
        emitState();
    }

    public void setBreakpointEnabled(String filePath, int lineNumber, boolean enabled) {
        if (filePath == null) return;
        synchronized (lock) {
            Map<Integer, DebugModels.BreakpointSpec> fileBps = breakpointsByFile.get(filePath);
            if (fileBps != null && fileBps.containsKey(lineNumber)) {
                fileBps.put(lineNumber, fileBps.get(lineNumber).withEnabled(enabled));
            }
        }
        emitState();
    }

    public void removeBreakpoint(String filePath, int lineNumber) {
        if (filePath == null) return;
        synchronized (lock) {
            Map<Integer, DebugModels.BreakpointSpec> fileBps = breakpointsByFile.get(filePath);
            if (fileBps != null) {
                fileBps.remove(lineNumber);
                if (fileBps.isEmpty()) breakpointsByFile.remove(filePath);
            }
        }
        emitState();
    }

    public List<DebugModels.BreakpointSpec> getAllBreakpoints() {
        synchronized (lock) {
            List<DebugModels.BreakpointSpec> all = new ArrayList<>();
            for (Map<Integer, DebugModels.BreakpointSpec> map : breakpointsByFile.values()) {
                all.addAll(map.values());
            }
            return Collections.unmodifiableList(all);
        }
    }

    public DebugModels.BreakpointSpec findBreakpoint(String filePath, String fileName, int lineNumber) {
        synchronized (lock) {
            if (filePath != null) {
                Map<Integer, DebugModels.BreakpointSpec> exact = breakpointsByFile.get(filePath);
                if (exact != null && exact.containsKey(lineNumber)) {
                    return exact.get(lineNumber);
                }
            }
            if (fileName != null) {
                for (Map<Integer, DebugModels.BreakpointSpec> map : breakpointsByFile.values()) {
                    DebugModels.BreakpointSpec candidate = map.get(lineNumber);
                    if (candidate != null && fileName.equals(candidate.fileName)) {
                        return candidate;
                    }
                }
            }
            return null;
        }
    }

    // --- Watch & Expression Evaluation ---

    public void addWatch(String expression) {
        if (expression == null || expression.trim().isEmpty()) return;
        String clean = expression.trim();
        synchronized (lock) {
            if (!watchExpressions.contains(clean)) {
                watchExpressions.add(clean);
            }
        }
        refreshPausedState();
    }

    public void removeWatch(String expression) {
        synchronized (lock) {
            watchExpressions.remove(expression);
        }
        refreshPausedState();
    }

    public DebugModels.WatchItem evaluateExpression(String expression) {
        int frameIdx;
        DebugModels.Backend backend;
        synchronized (lock) {
            frameIdx = currentState.selectedFrameIndex;
            backend = currentState.backend;
        }
        if (backend == DebugModels.Backend.LOCAL_STEP_VM && localEngine.isPaused()) {
            return localEngine.evaluateWatch(frameIdx, expression);
        }
        return new DebugModels.WatchItem(expression, "<Pause local VM to evaluate>", "", true);
    }

    public boolean setVariableValue(String varName, String valueExpr) {
        int frameIdx;
        DebugModels.Backend backend;
        synchronized (lock) {
            frameIdx = currentState.selectedFrameIndex;
            backend = currentState.backend;
        }
        if (backend == DebugModels.Backend.LOCAL_STEP_VM && localEngine.isPaused()) {
            boolean ok = localEngine.setVariableValue(frameIdx, varName, valueExpr);
            if (ok) refreshPausedState();
            return ok;
        }
        return false;
    }

    // --- Session Lifecycle ---

    public void startLocalDebug(File projectDir, File activeFile) {
        disconnectJdwpOnly();
        synchronized (lock) {
            consoleLines.clear();
            currentState = new DebugModels.DebugSessionState(
                    DebugModels.Mode.RUNNING,
                    DebugModels.Backend.LOCAL_STEP_VM,
                    activeFile != null ? activeFile.getAbsolutePath() : "",
                    activeFile != null ? activeFile.getName() : "",
                    -1,
                    "Running...",
                    Collections.emptyList(),
                    0,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.singletonList("[Debug VM] Launching step session..."),
                    null,
                    currentState.jdwpHost,
                    currentState.jdwpPort
            );
        }
        emitState();

        localEngine.start(projectDir, activeFile, this::findBreakpoint, new LocalJavaDebugEngine.SessionListener() {
            @Override
            public void onPaused(
                    String reason,
                    String filePath,
                    String fileName,
                    int lineNumber,
                    List<DebugModels.StackFrameInfo> frames,
                    int preferredFrameIndex) {
                List<DebugModels.DebugVariable> vars = localEngine.inspectVariables(preferredFrameIndex);
                List<DebugModels.WatchItem> watches = evaluateAllWatches(preferredFrameIndex);
                synchronized (lock) {
                    currentState = new DebugModels.DebugSessionState(
                            DebugModels.Mode.PAUSED,
                            DebugModels.Backend.LOCAL_STEP_VM,
                            filePath,
                            fileName,
                            lineNumber,
                            reason,
                            frames,
                            preferredFrameIndex,
                            vars,
                            watches,
                            new ArrayList<>(consoleLines),
                            null,
                            currentState.jdwpHost,
                            currentState.jdwpPort
                    );
                }
                emitState();
            }

            @Override
            public void onResumed() {
                synchronized (lock) {
                    if (currentState.mode != DebugModels.Mode.IDLE) {
                        currentState = new DebugModels.DebugSessionState(
                                DebugModels.Mode.RUNNING,
                                currentState.backend,
                                currentState.activeFilePath,
                                currentState.activeFileName,
                                -1,
                                "Running...",
                                Collections.emptyList(),
                                0,
                                Collections.emptyList(),
                                currentState.watches,
                                new ArrayList<>(consoleLines),
                                null,
                                currentState.jdwpHost,
                                currentState.jdwpPort
                        );
                    }
                }
                emitState();
            }

            @Override
            public void onConsoleOutput(String line) {
                synchronized (lock) {
                    consoleLines.add(line);
                    if (consoleLines.size() > 250) {
                        consoleLines.remove(0);
                    }
                }
            }

            @Override
            public void onBreakpointHitUpdated(String filePath, int lineNumber) {
                synchronized (lock) {
                    Map<Integer, DebugModels.BreakpointSpec> bps = breakpointsByFile.get(filePath);
                    if (bps != null && bps.containsKey(lineNumber)) {
                        bps.put(lineNumber, bps.get(lineNumber).incrementHit());
                    }
                }
            }

            @Override
            public void onTerminated(String reason) {
                synchronized (lock) {
                    consoleLines.add("[Debug VM] " + reason);
                    currentState = new DebugModels.DebugSessionState(
                            DebugModels.Mode.IDLE,
                            DebugModels.Backend.LOCAL_STEP_VM,
                            "",
                            "",
                            -1,
                            reason,
                            Collections.emptyList(),
                            0,
                            Collections.emptyList(),
                            currentState.watches,
                            new ArrayList<>(consoleLines),
                            null,
                            currentState.jdwpHost,
                            currentState.jdwpPort
                    );
                }
                emitState();
            }
        });
    }

    public void connectJdwp(String host, int port) {
        stop();
        String safeHost = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host.trim();
        int safePort = port > 0 ? port : 8700;
        synchronized (lock) {
            consoleLines.clear();
            consoleLines.add("[JDWP] Connecting to " + safeHost + ":" + safePort + "...");
            currentState = new DebugModels.DebugSessionState(
                    DebugModels.Mode.RUNNING,
                    DebugModels.Backend.JDWP,
                    "",
                    "",
                    -1,
                    "Connecting JDWP...",
                    Collections.emptyList(),
                    0,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    new ArrayList<>(consoleLines),
                    null,
                    safeHost,
                    safePort
            );
        }
        emitState();

        jdwpExecutor.execute(() -> {
            JdwpClient client = new JdwpClient();
            client.setEventListener(new JdwpClient.EventListener() {
                @Override
                public void onBreakpointOrStep(byte eventKind, int requestId, long threadId, long classId, long methodId, long codeIndex) {
                    activeJdwpThreadId = threadId;
                    jdwpExecutor.execute(() -> refreshJdwpFrames(client, threadId,
                            eventKind == JdwpClient.EVENT_BREAKPOINT ? "JDWP Breakpoint" : "JDWP Step"));
                }

                @Override
                public void onDisconnected(String reason) {
                    synchronized (lock) {
                        consoleLines.add("[JDWP] " + reason);
                        currentState = new DebugModels.DebugSessionState(
                                DebugModels.Mode.IDLE,
                                DebugModels.Backend.JDWP,
                                "",
                                "",
                                -1,
                                reason,
                                Collections.emptyList(),
                                0,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                new ArrayList<>(consoleLines),
                                reason,
                                safeHost,
                                safePort
                        );
                    }
                    emitState();
                }
            });

            try {
                client.connect(safeHost, safePort, 5000);
                this.jdwpClient = client;
                String vmVer = client.fetchVmVersion();
                List<Long> threads = client.fetchAllThreads();
                synchronized (lock) {
                    consoleLines.add("[JDWP] Attached: " + vmVer);
                    consoleLines.add("[JDWP] Active VM threads: " + threads.size());
                }
                if (!threads.isEmpty()) {
                    activeJdwpThreadId = threads.get(0);
                    refreshJdwpFrames(client, activeJdwpThreadId, "JDWP Attached");
                } else {
                    synchronized (lock) {
                        currentState = new DebugModels.DebugSessionState(
                                DebugModels.Mode.RUNNING,
                                DebugModels.Backend.JDWP,
                                "",
                                "",
                                -1,
                                "JDWP Attached",
                                Collections.emptyList(),
                                0,
                                Collections.emptyList(),
                                Collections.emptyList(),
                                new ArrayList<>(consoleLines),
                                null,
                                safeHost,
                                safePort
                        );
                    }
                    emitState();
                }
            } catch (Exception e) {
                client.close();
                synchronized (lock) {
                    String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    consoleLines.add("[JDWP ERROR] " + err);
                    currentState = new DebugModels.DebugSessionState(
                            DebugModels.Mode.IDLE,
                            DebugModels.Backend.JDWP,
                            "",
                            "",
                            -1,
                            "JDWP failed",
                            Collections.emptyList(),
                            0,
                            Collections.emptyList(),
                            Collections.emptyList(),
                            new ArrayList<>(consoleLines),
                            err,
                            safeHost,
                            safePort
                    );
                }
                emitState();
            }
        });
    }

    private void refreshJdwpFrames(JdwpClient client, long threadId, String reason) {
        try {
            List<JdwpClient.RemoteFrame> rFrames = client.fetchThreadFrames(threadId, 0, 12);
            List<DebugModels.StackFrameInfo> frames = new ArrayList<>();
            List<DebugModels.DebugVariable> vars = new ArrayList<>();
            for (int i = 0; i < rFrames.size(); i++) {
                JdwpClient.RemoteFrame rf = rFrames.get(i);
                int line = -1;
                List<JdwpClient.LineEntry> table = client.fetchLineTable(rf.classId, rf.methodId);
                for (JdwpClient.LineEntry le : table) {
                    if (le.codeIndex <= rf.codeIndex) {
                        line = le.lineNumber;
                    }
                }
                if (i == 0) {
                    cachedJdwpSlots = client.fetchVariableTable(rf.classId, rf.methodId);
                    List<String> vals = client.fetchFrameValues(threadId, rf.frameId, cachedJdwpSlots);
                    for (int j = 0; j < cachedJdwpSlots.size() && j < vals.size(); j++) {
                        JdwpClient.VariableSlot slot = cachedJdwpSlots.get(j);
                        vars.add(new DebugModels.DebugVariable(slot.name, slot.signature, vals.get(j), "JDWP", false, null));
                    }
                }
                frames.add(new DebugModels.StackFrameInfo(rf.frameId, "Class#" + rf.classId,
                        "method#" + rf.methodId, "", "", line));
            }
            synchronized (lock) {
                currentState = new DebugModels.DebugSessionState(
                        DebugModels.Mode.PAUSED,
                        DebugModels.Backend.JDWP,
                        "",
                        "",
                        frames.isEmpty() ? -1 : frames.get(0).lineNumber,
                        reason,
                        frames,
                        0,
                        vars,
                        Collections.emptyList(),
                        new ArrayList<>(consoleLines),
                        null,
                        currentState.jdwpHost,
                        currentState.jdwpPort
                );
            }
            emitState();
        } catch (Exception e) {
            synchronized (lock) {
                consoleLines.add("[JDWP Frame Error] " + e.getMessage());
            }
            emitState();
        }
    }

    public void stepOver() {
        if (currentState.backend == DebugModels.Backend.JDWP && jdwpClient != null && activeJdwpThreadId >= 0) {
            jdwpStep(JdwpClient.STEP_DEPTH_OVER);
        } else {
            localEngine.sendCommand(LocalJavaDebugEngine.StepCommand.STEP_OVER);
        }
    }

    public void stepInto() {
        if (currentState.backend == DebugModels.Backend.JDWP && jdwpClient != null && activeJdwpThreadId >= 0) {
            jdwpStep(JdwpClient.STEP_DEPTH_INTO);
        } else {
            localEngine.sendCommand(LocalJavaDebugEngine.StepCommand.STEP_INTO);
        }
    }

    public void stepOut() {
        if (currentState.backend == DebugModels.Backend.JDWP && jdwpClient != null && activeJdwpThreadId >= 0) {
            jdwpStep(JdwpClient.STEP_DEPTH_OUT);
        } else {
            localEngine.sendCommand(LocalJavaDebugEngine.StepCommand.STEP_OUT);
        }
    }

    public void resume() {
        if (currentState.backend == DebugModels.Backend.JDWP && jdwpClient != null) {
            jdwpExecutor.execute(() -> {
                try {
                    jdwpClient.resumeVm();
                } catch (Exception ignored) {
                }
            });
        } else {
            localEngine.sendCommand(LocalJavaDebugEngine.StepCommand.RESUME);
        }
    }

    public void pause() {
        if (currentState.backend == DebugModels.Backend.JDWP && jdwpClient != null && activeJdwpThreadId >= 0) {
            jdwpExecutor.execute(() -> {
                try {
                    jdwpClient.suspendThread(activeJdwpThreadId);
                    refreshJdwpFrames(jdwpClient, activeJdwpThreadId, "JDWP Suspended");
                } catch (Exception ignored) {
                }
            });
        } else {
            localEngine.requestPause();
        }
    }

    public void stop() {
        localEngine.stop();
        disconnectJdwpOnly();
        synchronized (lock) {
            currentState = DebugModels.DebugSessionState.idle();
        }
        emitState();
    }

    public void selectStackFrame(int frameIndex) {
        synchronized (lock) {
            if (currentState.mode != DebugModels.Mode.PAUSED || currentState.frames.isEmpty()) return;
            int idx = Math.max(0, Math.min(frameIndex, currentState.frames.size() - 1));
            DebugModels.StackFrameInfo selected = currentState.frames.get(idx);
            List<DebugModels.DebugVariable> vars = currentState.backend == DebugModels.Backend.LOCAL_STEP_VM
                    ? localEngine.inspectVariables(idx)
                    : currentState.variables;
            List<DebugModels.WatchItem> watches = currentState.backend == DebugModels.Backend.LOCAL_STEP_VM
                    ? evaluateAllWatches(idx)
                    : currentState.watches;
            currentState = new DebugModels.DebugSessionState(
                    currentState.mode,
                    currentState.backend,
                    selected.filePath,
                    selected.fileName,
                    selected.lineNumber,
                    currentState.suspendReason,
                    currentState.frames,
                    idx,
                    vars,
                    watches,
                    currentState.consoleOutput,
                    null,
                    currentState.jdwpHost,
                    currentState.jdwpPort
            );
        }
        emitState();
    }

    private void jdwpStep(int depth) {
        JdwpClient c = jdwpClient;
        long tid = activeJdwpThreadId;
        if (c == null || tid < 0) return;
        jdwpExecutor.execute(() -> {
            try {
                c.setStepRequest(tid, depth);
                c.resumeVm();
            } catch (Exception e) {
                synchronized (lock) {
                    consoleLines.add("[JDWP Step Error] " + e.getMessage());
                }
                emitState();
            }
        });
    }

    private void refreshPausedState() {
        synchronized (lock) {
            if (currentState.mode != DebugModels.Mode.PAUSED
                    || currentState.backend != DebugModels.Backend.LOCAL_STEP_VM) {
                return;
            }
            int idx = currentState.selectedFrameIndex;
            List<DebugModels.DebugVariable> vars = localEngine.inspectVariables(idx);
            List<DebugModels.WatchItem> watches = evaluateAllWatches(idx);
            currentState = new DebugModels.DebugSessionState(
                    currentState.mode,
                    currentState.backend,
                    currentState.activeFilePath,
                    currentState.activeFileName,
                    currentState.activeLine,
                    currentState.suspendReason,
                    currentState.frames,
                    idx,
                    vars,
                    watches,
                    new ArrayList<>(consoleLines),
                    currentState.lastError,
                    currentState.jdwpHost,
                    currentState.jdwpPort
            );
        }
        emitState();
    }

    private List<DebugModels.WatchItem> evaluateAllWatches(int frameIdx) {
        List<String> exprs;
        synchronized (lock) {
            exprs = new ArrayList<>(watchExpressions);
        }
        List<DebugModels.WatchItem> out = new ArrayList<>(exprs.size());
        for (String expr : exprs) {
            out.add(localEngine.evaluateWatch(frameIdx, expr));
        }
        return out;
    }

    private void disconnectJdwpOnly() {
        if (jdwpClient != null) {
            jdwpClient.close();
            jdwpClient = null;
        }
        activeJdwpThreadId = -1L;
    }

    private void emitState() {
        StateListener l = this.stateListener;
        if (l == null) return;
        DebugModels.DebugSessionState snapshot;
        synchronized (lock) {
            snapshot = currentState;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            l.onStateChanged(snapshot);
        } else {
            mainHandler.post(() -> l.onStateChanged(snapshot));
        }
    }

    public void shutdown() {
        stop();
        jdwpExecutor.shutdownNow();
    }
}
