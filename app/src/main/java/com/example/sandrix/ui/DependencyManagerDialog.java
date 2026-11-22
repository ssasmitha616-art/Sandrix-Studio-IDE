package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;
import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.ide.IdeUiState;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Pure Java Material 3 Dependency Manager dialog for {@code build.sd} / {@code build.gradle}.
 */
public final class DependencyManagerDialog {

    public interface Listener {
        void onAddOrUpdateDependency(String configuration, String coordinate);
        void onRemoveDependency(String coordinate);
    }

    private static final String[][] PRESET_DEPS = {
            {"AppCompat 1.7.0", "androidx.appcompat:appcompat:1.7.0"},
            {"Material 1.12.0", "com.google.android.material:material:1.12.0"},
            {"RecyclerView 1.3.2", "androidx.recyclerview:recyclerview:1.3.2"},
            {"ConstraintLayout 2.2.0", "androidx.constraintlayout:constraintlayout:2.2.0"},
            {"OkHttp 4.12.0", "com.squareup.okhttp3:okhttp:4.12.0"},
            {"Gson 2.11.0", "com.google.code.gson:gson:2.11.0"}
    };

    private DependencyManagerDialog() {}

    public static void show(Context context, IdeUiState state, Listener listener) {
        if (state == null || state.activeProject == null) {
            Toast.makeText(context, "Open a project first.", Toast.LENGTH_SHORT).show();
            return;
        }
        BuildSdParser.BuildSdConfig cfg = state.parsedConfig != null
                ? state.parsedConfig
                : BuildSdParser.parseString("");

        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        Typeface mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono);

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(8 * density), pad, pad);
        scroll.addView(root);

        // Active SDK & Repositories card
        LinearLayout headerCard = new LinearLayout(context);
        headerCard.setOrientation(LinearLayout.VERTICAL);
        headerCard.setBackgroundResource(R.drawable.bg_card_panel);
        headerCard.setPadding(pad, pad, pad, pad);

        TextView tvSummary = new TextView(context);
        tvSummary.setTypeface(mono, Typeface.BOLD);
        tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvSummary.setTextColor(ContextCompat.getColor(context, R.color.sandrix_amber));
        tvSummary.setText(cfg.toSummary());
        headerCard.addView(tvSummary);

        StringBuilder repoText = new StringBuilder("Repositories: google(), mavenCentral()");
        for (String r : cfg.repositories) {
            repoText.append(", ").append(r);
        }
        TextView tvRepos = new TextView(context);
        tvRepos.setTypeface(mono);
        tvRepos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvRepos.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
        tvRepos.setText(repoText.toString());
        headerCard.addView(tvRepos);
        root.addView(headerCard);

        // Add / Update form
        addSectionTitle(context, root, "Add or Update Maven Dependency", density);
        Spinner spConfig = new Spinner(context);
        String[] configs = {"implementation", "compileOnly", "api"};
        ArrayAdapter<String> spAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, configs);
        spAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spConfig.setAdapter(spAdapter);
        root.addView(spConfig);

        TextInputLayout coordTil = new TextInputLayout(context);
        coordTil.setHint("Coordinate (group:artifact:version)");
        TextInputEditText etCoord = new TextInputEditText(context);
        etCoord.setTypeface(mono);
        etCoord.setSingleLine(true);
        etCoord.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        coordTil.addView(etCoord);
        LinearLayout.LayoutParams tilLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tilLp.topMargin = Math.round(6 * density);
        root.addView(coordTil, tilLp);

        // Curated preset chips
        HorizontalScrollView presetScroll = new HorizontalScrollView(context);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presetRow = new LinearLayout(context);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] preset : PRESET_DEPS) {
            Chip chip = new Chip(context);
            chip.setText(preset[0]);
            chip.setOnClickListener(v -> etCoord.setText(preset[1]));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.setMarginEnd(Math.round(6 * density));
            presetRow.addView(chip, cLp);
        }
        presetScroll.addView(presetRow);
        root.addView(presetScroll);

        MaterialButton btnAdd = new MaterialButton(context);
        btnAdd.setText("Add / Update in build.sd");
        btnAdd.setAllCaps(false);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = Math.round(6 * density);
        root.addView(btnAdd, addLp);

        // Configured dependencies list
        addSectionTitle(context, root, "Configured Dependencies (" + cfg.dependencies.size() + ")", density);
        LinearLayout depsContainer = new LinearLayout(context);
        depsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(depsContainer);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_build)
                .setTitle("Dependency Manager")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        Runnable populateLocalList = () -> {
            depsContainer.removeAllViews();
            if (cfg.dependencies.isEmpty() && cfg.localFiles.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText("No dependencies configured yet.");
                empty.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                depsContainer.addView(empty);
                return;
            }
            for (BuildSdParser.DependencySpec dep : cfg.dependencies) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_card_panel);
                int rPad = Math.round(10 * density);
                row.setPadding(rPad, rPad, rPad, rPad);
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.bottomMargin = Math.round(6 * density);

                LinearLayout col = new LinearLayout(context);
                col.setOrientation(LinearLayout.VERTICAL);

                TextView tvCoord = new TextView(context);
                tvCoord.setTypeface(mono, Typeface.BOLD);
                tvCoord.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                tvCoord.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_primary));
                tvCoord.setText(dep.rawCoordinate);
                col.addView(tvCoord);

                TextView tvScope = new TextView(context);
                tvScope.setTypeface(mono);
                tvScope.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                tvScope.setTextColor(ContextCompat.getColor(context,
                        dep.isCompileOnly() ? R.color.sandrix_amber : R.color.sandrix_cyan));
                tvScope.setText(dep.configuration + " • line " + dep.lineNumber);
                col.addView(tvScope);

                col.setOnClickListener(v -> etCoord.setText(dep.rawCoordinate));
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                MaterialButton btnDel = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                btnDel.setText("Remove");
                btnDel.setAllCaps(false);
                btnDel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                btnDel.setTextColor(ContextCompat.getColor(context, R.color.sandrix_coral));
                btnDel.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onRemoveDependency(dep.rawCoordinate);
                    }
                    dialog.dismiss();
                });
                row.addView(btnDel);

                depsContainer.addView(row, rLp);
            }

            for (BuildSdParser.LocalFileSpec lf : cfg.localFiles) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackgroundResource(R.drawable.bg_pill_surface);
                int rPad = Math.round(8 * density);
                row.setPadding(rPad, rPad, rPad, rPad);
                LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.bottomMargin = Math.round(6 * density);

                TextView tvLocal = new TextView(context);
                tvLocal.setTypeface(mono);
                tvLocal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                tvLocal.setTextColor(ContextCompat.getColor(context, R.color.sandrix_emerald));
                tvLocal.setText((lf.isDirectoryTree ? "fileTree(" : "files(") + lf.path + ") [" + lf.configuration + "]");
                row.addView(tvLocal);
                depsContainer.addView(row, rLp);
            }
        };
        populateLocalList.run();

        btnAdd.setOnClickListener(v -> {
            String coord = etCoord.getText() != null ? etCoord.getText().toString().trim() : "";
            if (coord.isEmpty() || !coord.contains(":")) {
                Toast.makeText(context, "Enter a Maven coordinate (group:artifact:version).", Toast.LENGTH_SHORT).show();
                return;
            }
            String config = spConfig.getSelectedItem() != null ? spConfig.getSelectedItem().toString() : "implementation";
            if (listener != null) {
                listener.onAddOrUpdateDependency(config, coord);
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private static void addSectionTitle(Context context, LinearLayout parent, String text, float density) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(context, R.color.sandrix_cyan));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * density);
        lp.bottomMargin = Math.round(4 * density);
        parent.addView(tv, lp);
    }
}
