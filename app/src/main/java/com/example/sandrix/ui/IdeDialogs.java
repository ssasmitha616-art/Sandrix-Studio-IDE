package com.example.sandrix.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.R;
import com.example.sandrix.engine.SandrixToolchain;
import com.example.sandrix.ide.IdeUiState;
import com.example.sandrix.workspace.WorkspaceManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Pure Java Material 3 dialogs for New Project, New File, and Toolchain Status.
 */
public final class IdeDialogs {

    public interface OnCreateProjectListener {
        void onCreateProject(String name, String packageName, WorkspaceManager.ProjectTemplate template);
    }

    public interface OnCreateFileListener {
        void onCreateFile(String relativePath);
    }

    private IdeDialogs() {}

    public static void showNewProjectDialog(Context context, OnCreateProjectListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_new_project, null);
        TextInputEditText etName = view.findViewById(R.id.et_project_name);
        TextInputEditText etPkg = view.findViewById(R.id.et_package_name);
        ChipGroup cgTemplate = view.findViewById(R.id.cg_project_template);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Create Sandrix Project")
                .setView(view)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = etName.getText() != null ? etName.getText().toString() : "MySandrixApp";
                    String pkg = etPkg.getText() != null ? etPkg.getText().toString() : "com.sandrix.myapp";
                    int checkedId = cgTemplate.getCheckedChipId();
                    WorkspaceManager.ProjectTemplate template;
                    if (checkedId == R.id.chip_template_counter) {
                        template = WorkspaceManager.ProjectTemplate.COUNTER;
                    } else {
                        template = WorkspaceManager.ProjectTemplate.HELLO_WORLD;
                    }
                    listener.onCreateProject(name, pkg, template);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showNewFileDialog(Context context, OnCreateFileListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_new_file, null);
        TextInputEditText etPath = view.findViewById(R.id.et_file_path);

        view.findViewById(R.id.chip_preset_xml).setOnClickListener(v ->
                etPath.setText("res/layout/detail_screen.xml"));
        view.findViewById(R.id.chip_preset_java).setOnClickListener(v ->
                etPath.setText("src/com/sandrix/app/Helper.java"));
        view.findViewById(R.id.chip_preset_sd).setOnClickListener(v ->
                etPath.setText("build.sd"));

        new MaterialAlertDialogBuilder(context)
                .setTitle("New Project File")
                .setView(view)
                .setPositiveButton("Create", (dialog, which) -> {
                    String path = etPath.getText() != null ? etPath.getText().toString() : "";
                    listener.onCreateFile(path);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showToolchainStatusDialog(
            Context context,
            IdeUiState state,
            Runnable onImportZipClick,
            Runnable onInstallLastApk) {
        showToolchainStatusDialog(context, state, onImportZipClick, onInstallLastApk, null, null);
    }

    public static void showToolchainStatusDialog(
            Context context,
            IdeUiState state,
            Runnable onImportZipClick,
            Runnable onInstallLastApk,
            Runnable onClearDexCache,
            Runnable onCleanProjectCache) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.dialog_toolchain_status, null);

        View cardSummary = view.findViewById(R.id.card_build_sd_summary);
        TextView tvSummary = view.findViewById(R.id.tv_dialog_build_sd_summary);
        if (state.parsedConfig != null) {
            cardSummary.setVisibility(View.VISIBLE);
            tvSummary.setText(state.parsedConfig.toSummary());
        } else {
            cardSummary.setVisibility(View.GONE);
        }

        LinearLayout listContainer = view.findViewById(R.id.toolchain_list_container);
        for (SandrixToolchain.ToolStatus tool : state.toolchainStatuses) {
            View row = inflater.inflate(R.layout.item_toolchain_status, listContainer, false);
            ImageView ivStatus = row.findViewById(R.id.iv_tool_status);
            TextView tvName = row.findViewById(R.id.tv_tool_name);
            TextView tvPath = row.findViewById(R.id.tv_tool_path);
            TextView tvBadge = row.findViewById(R.id.tv_tool_badge);

            int accent = ContextCompat.getColor(context,
                    tool.available ? R.color.sandrix_emerald : R.color.sandrix_amber);
            ivStatus.setImageResource(tool.available ? R.drawable.ic_check_circle : R.drawable.ic_warning);
            ivStatus.setColorFilter(accent);
            tvName.setText(tool.name);
            tvPath.setText(tool.path);
            tvBadge.setText(tool.available ? "READY" : "MISSING");
            tvBadge.setTextColor(accent);
            listContainer.addView(row);
        }

        TextView tvVariantCache = view.findViewById(R.id.tv_dialog_variant_cache_summary);
        MaterialButton btnClearDex = view.findViewById(R.id.btn_dialog_clear_dex_cache);
        MaterialButton btnCleanProject = view.findViewById(R.id.btn_dialog_clean_project_cache);

        SandrixToolchain toolchain = SandrixToolchain.inspect(context);
        com.example.sandrix.engine.DexCacheManager.CacheStats stats =
                com.example.sandrix.engine.DexCacheManager.inspectEngineCaches(toolchain.engineRoot);
        com.example.sandrix.engine.KeystoreGenerator.SigningConfigModel signCfg =
                com.example.sandrix.engine.KeystoreGenerator.loadSigningConfig(state.activeProject);

        StringBuilder vcSb = new StringBuilder();
        if (state.parsedConfig != null) {
            vcSb.append("Debug AppId: ")
                    .append(state.parsedConfig.effectiveApplicationId(state.parsedConfig.namespace, false))
                    .append(" (").append(state.parsedConfig.effectiveVersionName(false)).append(")\n");
            vcSb.append("Release AppId: ")
                    .append(state.parsedConfig.effectiveApplicationId(state.parsedConfig.namespace, true))
                    .append(" (").append(state.parsedConfig.effectiveVersionName(true)).append(")\n");
        }
        vcSb.append("Release Signing: ").append(signCfg.configured
                ? signCfg.storeFilePath + " [" + signCfg.keyAlias + "]"
                : "Not configured").append("\n");
        vcSb.append("Pre-Dex Cache: ").append(stats.dexEntryCount).append(" JAR(s) • ")
                .append(formatBytes(stats.dexCacheBytes)).append("\n");
        vcSb.append("Maven Cache: ").append(formatBytes(stats.mavenCacheBytes));
        if (state.activeProject != null) {
            long projBuildBytes = folderSize(new java.io.File(
                    com.example.sandrix.engine.BuildEngineTask.resolveEffectiveProjectRoot(state.activeProject),
                    "build"));
            vcSb.append("\nProject build/: ").append(formatBytes(projBuildBytes));
        }
        tvVariantCache.setText(vcSb.toString());

        MaterialButton btnInstall = view.findViewById(R.id.btn_dialog_install_apk);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_build)
                .setTitle("Sandrix Build Engine")
                .setView(view)
                .setPositiveButton("Import Engine .zip", (d, w) -> onImportZipClick.run())
                .setNegativeButton("Close", null)
                .create();

        btnClearDex.setOnClickListener(v -> {
            if (onClearDexCache != null) onClearDexCache.run();
            dialog.dismiss();
        });
        btnCleanProject.setEnabled(state.activeProject != null);
        btnCleanProject.setOnClickListener(v -> {
            if (onCleanProjectCache != null) onCleanProjectCache.run();
            dialog.dismiss();
        });

        if (state.lastBuiltApk != null && state.lastBuiltApk.exists()) {
            btnInstall.setVisibility(View.VISIBLE);
            btnInstall.setText("Install " + state.lastBuiltApk.getName());
            btnInstall.setOnClickListener(v -> {
                dialog.dismiss();
                onInstallLastApk.run();
            });
        } else {
            btnInstall.setVisibility(View.GONE);
        }

        dialog.show();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static long folderSize(java.io.File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long sum = 0L;
        java.io.File[] children = dir.listFiles();
        if (children != null) {
            for (java.io.File c : children) sum += folderSize(c);
        }
        return sum;
    }

    public interface OnReferenceSelectedListener {
        void onSelectReference(com.example.sandrix.intel.JavaProjectIndexer.ReferenceLocation ref);
    }

    public interface OnRenameSymbolListener {
        void onRename(String oldName, String newName);
    }

    public static void showFindReferencesDialog(
            Context context,
            String symbol,
            java.util.List<com.example.sandrix.intel.JavaProjectIndexer.ReferenceLocation> refs,
            OnReferenceSelectedListener listener) {
        if (refs == null || refs.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("References: " + symbol)
                    .setMessage("No references found in this project.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String[] items = new String[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            com.example.sandrix.intel.JavaProjectIndexer.ReferenceLocation r = refs.get(i);
            String kind = r.isDeclaration ? "[DEF] " : "";
            items[i] = kind + r.file.getName() + ":" + r.lineNumber + "  " + r.linePreview;
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle("References (" + refs.size() + ") • " + symbol)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < refs.size() && listener != null) {
                        listener.onSelectReference(refs.get(which));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    public static void showRenameSymbolDialog(
            Context context,
            String initialSymbol,
            OnRenameSymbolListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, Math.round(8 * density), pad, 0);

        com.google.android.material.textfield.TextInputLayout oldLayout =
                new com.google.android.material.textfield.TextInputLayout(context);
        oldLayout.setHint("Current symbol name");
        com.google.android.material.textfield.TextInputEditText etOld =
                new com.google.android.material.textfield.TextInputEditText(context);
        etOld.setText(initialSymbol != null ? initialSymbol : "");
        oldLayout.addView(etOld);

        com.google.android.material.textfield.TextInputLayout newLayout =
                new com.google.android.material.textfield.TextInputLayout(context);
        newLayout.setHint("New symbol name");
        com.google.android.material.textfield.TextInputEditText etNew =
                new com.google.android.material.textfield.TextInputEditText(context);
        etNew.setText(initialSymbol != null ? initialSymbol : "");
        newLayout.addView(etNew);

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Math.round(10 * density);

        container.addView(oldLayout);
        container.addView(newLayout, lp2);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Rename Symbol Across Project")
                .setView(container)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String oldName = etOld.getText() != null ? etOld.getText().toString().trim() : "";
                    String newName = etNew.getText() != null ? etNew.getText().toString().trim() : "";
                    if (listener != null) {
                        listener.onRename(oldName, newName);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public interface OnBreakpointConditionListener {
        void onSaveCondition(String condition);
    }

    public static void showBreakpointConditionDialog(
            Context context,
            int lineNumber,
            String existingCondition,
            OnBreakpointConditionListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, Math.round(8 * density), pad, 0);

        TextView hint = new TextView(context);
        hint.setText("Pause execution at line " + lineNumber + " only when expression evaluates to true (leave empty for unconditional):");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
        container.addView(hint);

        com.google.android.material.textfield.TextInputLayout layout =
                new com.google.android.material.textfield.TextInputLayout(context);
        layout.setHint("Condition (e.g. count == 3 || name.equals(\"ok\"))");
        com.google.android.material.textfield.TextInputEditText etCond =
                new com.google.android.material.textfield.TextInputEditText(context);
        etCond.setText(existingCondition != null ? existingCondition : "");
        layout.addView(etCond);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * density);
        container.addView(layout, lp);

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_bug)
                .setTitle("Conditional Breakpoint • Line " + lineNumber)
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    String cond = etCond.getText() != null ? etCond.getText().toString().trim() : "";
                    if (listener != null) listener.onSaveCondition(cond);
                })
                .setNeutralButton("Clear Condition", (d, w) -> {
                    if (listener != null) listener.onSaveCondition("");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showMappingFileDialog(Context context, java.io.File mappingFile) {
        String content = com.example.sandrix.engine.R8Optimizer.summarizeMappingFile(mappingFile, 250);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        TextView tv = new TextView(context);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jetbrains_mono));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_primary));
        tv.setText(content);
        tv.setTextIsSelectable(true);

        android.widget.ScrollView sv = new android.widget.ScrollView(context);
        sv.addView(tv);

        String title = mappingFile != null ? "R8 Mapping (" + mappingFile.getName() + ")" : "R8 Mapping";
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_build)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }
}
