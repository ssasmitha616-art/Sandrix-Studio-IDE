package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Debug;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;
import com.example.sandrix.debug.DebugModels;
import com.example.sandrix.ide.IdeUiState;
import com.example.sandrix.logcat.LogcatStreamReader;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java Material 3 Advanced Debugger Dialog supporting:
 * <ul>
 *     <li>Local Step VM launch &amp; JDWP TCP attach ({@code host:port})</li>
 *     <li>Step Over, Step Into, Step Out, Resume, Pause, Stop</li>
 *     <li>Live variable inspection, nested field expansion, &amp; variable value modification</li>
 *     <li>Watches &amp; interactive expression evaluation</li>
 *     <li>Interactive Call Stack frame navigation</li>
 *     <li>Conditional breakpoints with hit counts &amp; enable/disable</li>
 *     <li>VM Heap, Threads &amp; Logcat snapshot</li>
 * </ul>
 */
public final class DebugInspectorDialog {

    public interface Listener {
        void onStartLocalDebug();
        void onConnectJdwp(String host, int port);
        void onStepOver();
        void onStepInto();
        void onStepOut();
        void onResume();
        void onPause();
        void onStop();
        void onSelectStackFrame(int frameIndex);
        void onAddWatch(String expression);
        void onRemoveWatch(String expression);
        DebugModels.WatchItem onEvaluateExpression(String expression);
        boolean onSetVariableValue(String varName, String valueExpr);
        void onJumpToBreakpoint(File file, int lineNumber);
        void onSetBreakpointCondition(String filePath, String fileName, int lineNumber, String condition);
        void onToggleBreakpointEnabled(String filePath, int lineNumber, boolean enabled);
        void onRemoveBreakpoint(String filePath, int lineNumber);
        void onClearBreakpoints();
        void onJumpToLogcatTab();
    }

    public static final class Controller {
        private final AlertDialog dialog;
        private final Runnable renderRunnable;
        private IdeUiState currentState;

        private Controller(AlertDialog dialog, IdeUiState initialState, Runnable renderRunnable) {
            this.dialog = dialog;
            this.currentState = initialState;
            this.renderRunnable = renderRunnable;
        }

        public boolean isShowing() {
            return dialog != null && dialog.isShowing();
        }

        public void update(IdeUiState nextState) {
            if (!isShowing() || nextState == null) return;
            this.currentState = nextState;
            renderRunnable.run();
        }

        public IdeUiState getState() {
            return currentState;
        }
    }

    private DebugInspectorDialog() {}

    public static Controller show(
            Context context,
            IdeUiState initialState,
            Listener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);
        Typeface mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono);

        LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(pad, Math.round(6 * density), pad, pad);

        // Top Session Banner & Step Controls
        LinearLayout controlCard = createCard(context, density, "DEBUGGER SESSION");
        TextView tvSessionSummary = monoLine(context, mono, "", R.color.sandrix_cyan);
        controlCard.addView(tvSessionSummary);

        android.widget.HorizontalScrollView transportScroll = new android.widget.HorizontalScrollView(context);
        transportScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout transportRow = new LinearLayout(context);
        transportRow.setOrientation(LinearLayout.HORIZONTAL);
        transportRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = Math.round(6 * density);
        transportScroll.addView(transportRow);
        controlCard.addView(transportScroll, tLp);

        MaterialButton btnStartLocal = compactButton(context, "Step Debug", density);
        MaterialButton btnResumePause = compactButton(context, "Resume", density);
        MaterialButton btnStepOver = compactButton(context, "Over", density);
        MaterialButton btnStepInto = compactButton(context, "Into", density);
        MaterialButton btnStepOut = compactButton(context, "Out", density);
        MaterialButton btnStop = compactButton(context, "Stop", density);

        transportRow.addView(btnStartLocal);
        transportRow.addView(btnResumePause);
        transportRow.addView(btnStepOver);
        transportRow.addView(btnStepInto);
        transportRow.addView(btnStepOut);
        transportRow.addView(btnStop);
        outer.addView(controlCard);

        android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        ChipGroup tabGroup = new ChipGroup(context);
        tabGroup.setSingleSelection(true);
        tabGroup.setSelectionRequired(true);
        tabGroup.setSingleLine(true);
        Chip chipVars = filterChip(context, "Variables", true);
        Chip chipStack = filterChip(context, "Call Stack", false);
        Chip chipBps = filterChip(context, "Breakpoints", false);
        Chip chipJdwp = filterChip(context, "JDWP & VM", false);
        tabGroup.addView(chipVars);
        tabGroup.addView(chipStack);
        tabGroup.addView(chipBps);
        tabGroup.addView(chipJdwp);
        tabScroll.addView(tabGroup);
        outer.addView(tabScroll);

        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(370 * density));
        scrollLp.topMargin = Math.round(6 * density);
        outer.addView(scroll, scrollLp);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_bug)
                .setTitle("Advanced Debugger & Inspector")
                .setView(outer)
                .setNegativeButton("Close", null)
                .create();

        final int[] selectedTab = {0};
        final DebugModels.WatchItem[] lastEvalResult = {null};
        final Controller[] holder = new Controller[1];

        Runnable render = () -> {
            IdeUiState state = holder[0].getState();
            if (state == null) return;
            DebugModels.DebugSessionState dbg = state.debugSessionState;
            boolean active = dbg != null && dbg.isActive();
            boolean paused = dbg != null && dbg.mode == DebugModels.Mode.PAUSED;

            String statusText;
            if (!active) {
                statusText = "IDLE • Tap 'Step Debug' to launch interactive Step VM or attach JDWP";
            } else if (paused) {
                statusText = "PAUSED (" + dbg.backend.name() + ") at "
                        + dbg.activeFileName + ":" + dbg.activeLine + " • " + dbg.suspendReason;
            } else {
                statusText = "RUNNING (" + dbg.backend.name() + ") • " + dbg.suspendReason;
            }
            tvSessionSummary.setText(statusText);
            tvSessionSummary.setTextColor(ContextCompat.getColor(context,
                    paused ? R.color.sandrix_amber : (active ? R.color.sandrix_emerald : R.color.sandrix_text_secondary)));

            btnStartLocal.setVisibility(active ? View.GONE : View.VISIBLE);
            btnResumePause.setVisibility(active ? View.VISIBLE : View.GONE);
            btnResumePause.setText(paused ? "Resume" : "Pause");
            btnStepOver.setEnabled(paused);
            btnStepInto.setEnabled(paused);
            btnStepOut.setEnabled(paused);
            btnStop.setEnabled(active);

            int bpCount = state.breakpointSpecs != null ? state.breakpointSpecs.size() : 0;
            chipBps.setText("Breakpoints (" + bpCount + ")");
            int stackCount = (dbg != null && dbg.frames != null) ? dbg.frames.size() : 0;
            chipStack.setText("Stack (" + stackCount + ")");

            if (selectedTab[0] == 0) {
                renderVariablesAndWatchesTab(context, state, content, mono, density, listener, lastEvalResult);
            } else if (selectedTab[0] == 1) {
                renderCallStackTab(context, state, content, mono, density, dialog, listener);
            } else if (selectedTab[0] == 2) {
                renderBreakpointsTab(context, state, content, mono, density, dialog, listener);
            } else {
                renderJdwpAndVmTab(context, state, content, mono, density, dialog, listener, holder[0]);
            }
        };

        holder[0] = new Controller(dialog, initialState, render);

        btnStartLocal.setOnClickListener(v -> {
            if (listener != null) listener.onStartLocalDebug();
        });
        btnResumePause.setOnClickListener(v -> {
            if (listener == null) return;
            DebugModels.DebugSessionState dbg = holder[0].getState().debugSessionState;
            if (dbg != null && dbg.mode == DebugModels.Mode.PAUSED) {
                listener.onResume();
            } else {
                listener.onPause();
            }
        });
        btnStepOver.setOnClickListener(v -> { if (listener != null) listener.onStepOver(); });
        btnStepInto.setOnClickListener(v -> { if (listener != null) listener.onStepInto(); });
        btnStepOut.setOnClickListener(v -> { if (listener != null) listener.onStepOut(); });
        btnStop.setOnClickListener(v -> { if (listener != null) listener.onStop(); });

        tabGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == chipVars.getId()) selectedTab[0] = 0;
            else if (id == chipStack.getId()) selectedTab[0] = 1;
            else if (id == chipBps.getId()) selectedTab[0] = 2;
            else selectedTab[0] = 3;
            render.run();
        });

        render.run();
        dialog.show();
        return holder[0];
    }

    private static void renderVariablesAndWatchesTab(
            Context context,
            IdeUiState state,
            LinearLayout content,
            Typeface mono,
            float density,
            Listener listener,
            DebugModels.WatchItem[] lastEvalResult) {
        content.removeAllViews();
        DebugModels.DebugSessionState dbg = state.debugSessionState;

        // 1. Expression Evaluator
        LinearLayout evalCard = createCard(context, density, "EVALUATE EXPRESSION");
        LinearLayout evalRow = new LinearLayout(context);
        evalRow.setOrientation(LinearLayout.HORIZONTAL);
        evalRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText etExpr = new EditText(context);
        etExpr.setHint("e.g. count + 1 or count = 10");
        etExpr.setTypeface(mono);
        etExpr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        etExpr.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_primary));
        etExpr.setHintTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
        etExpr.setMinHeight(Math.round(48 * density));
        evalRow.addView(etExpr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton btnEval = compactButton(context, "Eval", density);
        MaterialButton btnAddWatch = compactButton(context, "+Watch", density);
        evalRow.addView(btnEval);
        evalRow.addView(btnAddWatch);
        evalCard.addView(evalRow);

        TextView tvEvalOut = monoLine(context, mono,
                lastEvalResult[0] != null
                        ? (lastEvalResult[0].expression + " => " + lastEvalResult[0].value
                        + (lastEvalResult[0].type.isEmpty() ? "" : " (" + lastEvalResult[0].type + ")"))
                        : "Pause execution on a breakpoint or step line to evaluate expressions.",
                lastEvalResult[0] != null && lastEvalResult[0].error
                        ? R.color.sandrix_coral : R.color.sandrix_emerald);
        evalCard.addView(tvEvalOut);

        btnEval.setOnClickListener(v -> {
            String expr = etExpr.getText() != null ? etExpr.getText().toString().trim() : "";
            if (expr.isEmpty() || listener == null) return;
            DebugModels.WatchItem res = listener.onEvaluateExpression(expr);
            lastEvalResult[0] = res;
            tvEvalOut.setText(res.expression + " => " + res.value
                    + (res.type.isEmpty() ? "" : " (" + res.type + ")"));
            tvEvalOut.setTextColor(ContextCompat.getColor(context,
                    res.error ? R.color.sandrix_coral : R.color.sandrix_emerald));
        });
        btnAddWatch.setOnClickListener(v -> {
            String expr = etExpr.getText() != null ? etExpr.getText().toString().trim() : "";
            if (!expr.isEmpty() && listener != null) {
                listener.onAddWatch(expr);
                etExpr.setText("");
            }
        });
        content.addView(evalCard);

        // 2. Watch Expressions
        LinearLayout watchCard = createCard(context, density,
                "WATCHES (" + (dbg != null ? dbg.watches.size() : 0) + ")");
        if (dbg == null || dbg.watches.isEmpty()) {
            watchCard.addView(monoLine(context, mono, "No active watches. Add one above.", R.color.sandrix_text_secondary));
        } else {
            for (DebugModels.WatchItem w : dbg.watches) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView tv = monoLine(context, mono,
                        w.expression + " = " + w.value + (w.type.isEmpty() ? "" : " : " + w.type),
                        w.error ? R.color.sandrix_coral : R.color.sandrix_cyan);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                MaterialButton btnRm = compactButton(context, "×", density);
                btnRm.setContentDescription("Remove watch " + w.expression);
                btnRm.setOnClickListener(v -> {
                    if (listener != null) listener.onRemoveWatch(w.expression);
                });
                row.addView(btnRm);
                watchCard.addView(row);
            }
        }
        content.addView(watchCard);

        // 3. Variables in Current Stack Frame
        List<DebugModels.DebugVariable> vars = dbg != null ? dbg.variables : java.util.Collections.emptyList();
        LinearLayout varCard = createCard(context, density, "VARIABLES (" + vars.size() + ")");
        if (vars.isEmpty()) {
            varCard.addView(monoLine(context, mono,
                    "No frame variables captured yet. Start Step Debug and step into a method.",
                    R.color.sandrix_text_secondary));
        } else {
            for (DebugModels.DebugVariable var : vars) {
                addVariableRow(context, varCard, var, 0, mono, density, listener);
            }
        }
        content.addView(varCard);
    }

    private static void addVariableRow(
            Context context,
            LinearLayout parent,
            DebugModels.DebugVariable var,
            int depth,
            Typeface mono,
            float density,
            Listener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(depth * 14 * density), Math.round(4 * density), 0, Math.round(4 * density));
        row.setMinimumHeight(Math.round(40 * density));

        String prefix = var.children.isEmpty() ? "• " : "▸ ";
        TextView tv = monoLine(context, mono,
                prefix + "[" + var.scope + "] " + var.name + " : " + var.type + " = " + var.value,
                var.editable ? R.color.sandrix_text_primary : R.color.sandrix_cyan);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (var.editable && listener != null) {
            row.setOnClickListener(v -> showEditVariableDialog(context, var, mono, listener));
        }
        parent.addView(row);

        for (DebugModels.DebugVariable child : var.children) {
            addVariableRow(context, parent, child, depth + 1, mono, density, listener);
        }
    }

    private static void showEditVariableDialog(
            Context context,
            DebugModels.DebugVariable var,
            Typeface mono,
            Listener listener) {
        EditText etVal = new EditText(context);
        etVal.setTypeface(mono);
        etVal.setText(var.value);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(context);
        box.setPadding(pad, Math.round(8 * context.getResources().getDisplayMetrics().density), pad, 0);
        box.addView(etVal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(context)
                .setTitle("Edit Variable: " + var.name)
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    String newExpr = etVal.getText() != null ? etVal.getText().toString().trim() : "";
                    boolean ok = listener.onSetVariableValue(var.name, newExpr);
                    if (!ok) {
                        Toast.makeText(context, "Unable to update " + var.name, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void renderCallStackTab(
            Context context,
            IdeUiState state,
            LinearLayout content,
            Typeface mono,
            float density,
            AlertDialog dialog,
            Listener listener) {
        content.removeAllViews();
        DebugModels.DebugSessionState dbg = state.debugSessionState;
        List<DebugModels.StackFrameInfo> frames = dbg != null ? dbg.frames : java.util.Collections.emptyList();

        LinearLayout stackCard = createCard(context, density, "CALL STACK (" + frames.size() + " frames)");
        if (frames.isEmpty()) {
            stackCard.addView(monoLine(context, mono,
                    "Call stack is empty while debugger is not paused.",
                    R.color.sandrix_text_secondary));
        } else {
            for (int i = 0; i < frames.size(); i++) {
                final int idx = i;
                DebugModels.StackFrameInfo f = frames.get(i);
                boolean selected = (idx == dbg.selectedFrameIndex);
                TextView row = monoLine(context, mono,
                        (selected ? "▶ #" : "  #") + i + "  " + f.toDisplayLabel(),
                        selected ? R.color.sandrix_emerald : R.color.sandrix_text_primary);
                row.setPadding(Math.round(6 * density), Math.round(10 * density),
                        Math.round(6 * density), Math.round(10 * density));
                row.setMinimumHeight(Math.round(48 * density));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setOnClickListener(v -> {
                    if (listener != null) listener.onSelectStackFrame(idx);
                    if (!f.filePath.isEmpty() && listener != null) {
                        listener.onJumpToBreakpoint(new File(f.filePath), f.lineNumber);
                        dialog.dismiss();
                    }
                });
                stackCard.addView(row);
            }
        }
        content.addView(stackCard);
    }

    private static void renderBreakpointsTab(
            Context context,
            IdeUiState state,
            LinearLayout content,
            Typeface mono,
            float density,
            AlertDialog dialog,
            Listener listener) {
        content.removeAllViews();
        List<DebugModels.BreakpointSpec> specs = state.breakpointSpecs;
        LinearLayout bpCard = createCard(context, density, "BREAKPOINTS (" + specs.size() + ")");
        if (specs.isEmpty()) {
            bpCard.addView(monoLine(context, mono,
                    "Tap any line number in the editor gutter to toggle a breakpoint. Long-press gutter for conditional breakpoints.",
                    R.color.sandrix_text_secondary));
        } else {
            for (DebugModels.BreakpointSpec bp : specs) {
                LinearLayout item = new LinearLayout(context);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(Math.round(4 * density), Math.round(6 * density),
                        Math.round(4 * density), Math.round(6 * density));

                String condBadge = bp.hasCondition() ? " [if " + bp.condition + "]" : "";
                String stateBadge = bp.enabled ? "● " : "○ ";
                TextView tvLoc = monoLine(context, mono,
                        stateBadge + bp.fileName + ":" + bp.lineNumber + condBadge + "  (hits: " + bp.hitCount + ")",
                        bp.enabled
                                ? (bp.hasCondition() ? R.color.sandrix_amber : R.color.sandrix_coral)
                                : R.color.sandrix_text_secondary);
                tvLoc.setMinHeight(Math.round(44 * density));
                tvLoc.setGravity(Gravity.CENTER_VERTICAL);
                tvLoc.setOnClickListener(v -> {
                    if (listener != null) listener.onJumpToBreakpoint(new File(bp.filePath), bp.lineNumber);
                    dialog.dismiss();
                });
                item.addView(tvLoc);

                LinearLayout actions = new LinearLayout(context);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                MaterialButton btnCond = compactButton(context, bp.hasCondition() ? "Edit Cond" : "Condition", density);
                MaterialButton btnToggle = compactButton(context, bp.enabled ? "Disable" : "Enable", density);
                MaterialButton btnRemove = compactButton(context, "Remove", density);

                btnCond.setOnClickListener(v -> IdeDialogs.showBreakpointConditionDialog(
                        context, bp.lineNumber, bp.condition, cond -> {
                            if (listener != null) {
                                listener.onSetBreakpointCondition(bp.filePath, bp.fileName, bp.lineNumber, cond);
                            }
                        }));
                btnToggle.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onToggleBreakpointEnabled(bp.filePath, bp.lineNumber, !bp.enabled);
                    }
                });
                btnRemove.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onRemoveBreakpoint(bp.filePath, bp.lineNumber);
                    }
                });

                actions.addView(btnCond);
                actions.addView(btnToggle);
                actions.addView(btnRemove);
                item.addView(actions);
                bpCard.addView(item);
            }

            MaterialButton btnClearBp = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnClearBp.setText("Clear All Breakpoints");
            btnClearBp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            btnClearBp.setOnClickListener(v -> {
                if (listener != null) listener.onClearBreakpoints();
                Toast.makeText(context, "Cleared breakpoints", Toast.LENGTH_SHORT).show();
            });
            bpCard.addView(btnClearBp);
        }
        content.addView(bpCard);
    }

    private static void renderJdwpAndVmTab(
            Context context,
            IdeUiState state,
            LinearLayout content,
            Typeface mono,
            float density,
            AlertDialog dialog,
            Listener listener,
            Controller controller) {
        content.removeAllViews();
        DebugModels.DebugSessionState dbg = state.debugSessionState;

        // 1. JDWP Remote Attach Card
        LinearLayout jdwpCard = createCard(context, density, "JDWP REMOTE ATTACH (TCP)");
        LinearLayout hostPortRow = new LinearLayout(context);
        hostPortRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText etHost = new EditText(context);
        etHost.setHint("Host");
        etHost.setText(dbg != null ? dbg.jdwpHost : "127.0.0.1");
        etHost.setTypeface(mono);
        etHost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        etHost.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_primary));
        etHost.setMinHeight(Math.round(48 * density));

        EditText etPort = new EditText(context);
        etPort.setHint("Port");
        etPort.setText(String.valueOf(dbg != null ? dbg.jdwpPort : 8700));
        etPort.setTypeface(mono);
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        etPort.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_primary));
        etPort.setMinHeight(Math.round(48 * density));

        hostPortRow.addView(etHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        hostPortRow.addView(etPort, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
        jdwpCard.addView(hostPortRow);

        MaterialButton btnAttachJdwp = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAttachJdwp.setText("Attach JDWP Over TCP");
        btnAttachJdwp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnAttachJdwp.setOnClickListener(v -> {
            String host = etHost.getText() != null ? etHost.getText().toString().trim() : "127.0.0.1";
            int port = 8700;
            try {
                port = Integer.parseInt(etPort.getText() != null ? etPort.getText().toString().trim() : "8700");
            } catch (NumberFormatException ignored) {}
            if (listener != null) listener.onConnectJdwp(host, port);
        });
        jdwpCard.addView(btnAttachJdwp);

        if (dbg != null && !dbg.consoleOutput.isEmpty()) {
            StringBuilder outSb = new StringBuilder();
            int start = Math.max(0, dbg.consoleOutput.size() - 10);
            for (int i = start; i < dbg.consoleOutput.size(); i++) {
                outSb.append(dbg.consoleOutput.get(i)).append('\n');
            }
            jdwpCard.addView(monoLine(context, mono, outSb.toString().trim(), R.color.sandrix_text_secondary));
        }
        content.addView(jdwpCard);

        // 2. Runtime & Memory Heap
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
        LinearLayout memCard = createCard(context, density, "VM MEMORY & DEVICE");
        memCard.addView(monoLine(context, mono,
                String.format(Locale.US,
                        "Java Heap: %d MB used / %d MB alloc (max %d MB)\n"
                                + "Native Heap: %d MB • CPUs: %d\n"
                                + "Android %s (API %d) • ABI: %s",
                        usedMb, totalMb, maxMb, nativeMb, rt.availableProcessors(),
                        Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                        Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown"),
                R.color.sandrix_text_primary));

        MaterialButton btnGc = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnGc.setText("Run GC & Refresh");
        btnGc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnGc.setOnClickListener(v -> {
            System.gc();
            controller.update(controller.getState());
        });
        memCard.addView(btnGc);
        content.addView(memCard);

        // 3. Active JVM Threads Snapshot
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        LinearLayout threadCard = createCard(context, density, "ACTIVE THREADS (" + traces.size() + ")");
        int shown = 0;
        for (Map.Entry<Thread, StackTraceElement[]> e : traces.entrySet()) {
            Thread t = e.getKey();
            StackTraceElement[] frames = e.getValue();
            String topFrame = frames != null && frames.length > 0
                    ? frames[0].getClassName() + "." + frames[0].getMethodName() + ":" + frames[0].getLineNumber()
                    : "(native/idle)";
            TextView row = monoLine(context, mono,
                    "[" + t.getState().name() + "] " + t.getName() + "\n   at " + topFrame,
                    t.getName().equals("main") ? R.color.sandrix_cyan : R.color.sandrix_text_primary);
            row.setPadding(0, Math.round(4 * density), 0, Math.round(4 * density));
            row.setOnClickListener(v -> showThreadStackDialog(context, t.getName(), frames, mono));
            threadCard.addView(row);
            shown++;
            if (shown >= 12) break;
        }
        content.addView(threadCard);

        // 4. Recent Logcat Errors
        List<LogcatStreamReader.LogEntry> errors = new ArrayList<>();
        for (int i = state.logcatEntries.size() - 1; i >= 0 && errors.size() < 8; i--) {
            LogcatStreamReader.LogEntry e = state.logcatEntries.get(i);
            if ("E".equals(e.level) || "F".equals(e.level) || "W".equals(e.level)) {
                errors.add(e);
            }
        }
        LinearLayout errCard = createCard(context, density, "RECENT LOGCAT E/W (" + errors.size() + ")");
        if (errors.isEmpty()) {
            errCard.addView(monoLine(context, mono, "No E/W events in Logcat buffer.", R.color.sandrix_emerald));
        } else {
            for (LogcatStreamReader.LogEntry e : errors) {
                errCard.addView(monoLine(context, mono,
                        e.timestamp + " " + e.level + "/" + e.tag + ": " + e.message,
                        "E".equals(e.level) ? R.color.sandrix_coral : R.color.sandrix_amber));
            }
        }
        MaterialButton btnOpenLogcat = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnOpenLogcat.setText("Open Live Logcat Console");
        btnOpenLogcat.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btnOpenLogcat.setOnClickListener(v -> {
            if (listener != null) listener.onJumpToLogcatTab();
            dialog.dismiss();
        });
        errCard.addView(btnOpenLogcat);
        content.addView(errCard);
    }

    private static void showThreadStackDialog(
            Context context,
            String threadName,
            StackTraceElement[] frames,
            Typeface mono) {
        StringBuilder sb = new StringBuilder();
        if (frames != null) {
            for (StackTraceElement f : frames) {
                sb.append("at ").append(f.toString()).append('\n');
            }
        }
        TextView tv = new TextView(context);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTypeface(mono);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setText(sb.length() == 0 ? "(no Java frames)" : sb.toString());
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(context);
        sv.addView(tv);
        new MaterialAlertDialogBuilder(context)
                .setTitle("Thread: " + threadName)
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    private static Chip filterChip(Context context, String label, boolean checked) {
        Chip chip = new Chip(context);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(checked);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        return chip;
    }

    private static MaterialButton compactButton(Context context, String text, float density) {
        MaterialButton btn = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btn.setMinWidth(Math.round(48 * density));
        btn.setMinimumWidth(Math.round(48 * density));
        btn.setMinHeight(Math.round(42 * density));
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        int h = Math.round(8 * density);
        btn.setPadding(h, 0, h, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(4 * density));
        btn.setLayoutParams(lp);
        return btn;
    }

    private static LinearLayout createCard(Context context, float density, String title) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_panel);
        int p = Math.round(10 * density);
        card.setPadding(p, p, p, p);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(ContextCompat.getColor(context, R.color.sandrix_amber));
        card.addView(tvTitle);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(8 * density);
        card.setLayoutParams(lp);
        return card;
    }

    private static TextView monoLine(Context context, Typeface mono, String text, int colorRes) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTypeface(mono);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(ContextCompat.getColor(context, colorRes));
        return tv;
    }
}
