package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;
import com.example.sandrix.xml.XmlPatcher;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure Java Material 3 Visual AndroidManifest.xml Editor.
 */
public final class ManifestEditorDialog {

    public interface OnSaveManifestListener {
        void onSaveManifest(String updatedManifestXml);
    }

    private static final String[] COMMON_PERMISSIONS = {
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.VIBRATE",
            "android.permission.RECORD_AUDIO"
    };

    private ManifestEditorDialog() {}

    public static void show(
            Context context,
            String manifestXml,
            OnSaveManifestListener listener) {
        XmlPatcher.ManifestModel model = XmlPatcher.parseManifestModel(manifestXml);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        Typeface mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono);

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(8 * density), pad, pad);
        scroll.addView(root);

        TextView tvAppSec = sectionHeader(context, "APPLICATION & SDK", density);
        root.addView(tvAppSec);

        TextInputEditText etPackage = addLabeledInput(context, root, mono, "Package Name", model.packageName, density);
        TextInputEditText etLabel = addLabeledInput(context, root, mono, "android:label", model.appLabel, density);
        TextInputEditText etIcon = addLabeledInput(context, root, mono, "android:icon (optional)", model.appIcon, density);
        TextInputEditText etTheme = addLabeledInput(context, root, mono, "android:theme (optional)", model.appTheme, density);

        LinearLayout sdkRow = new LinearLayout(context);
        sdkRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sdkRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sdkRowLp.topMargin = Math.round(8 * density);
        root.addView(sdkRow, sdkRowLp);

        TextInputEditText etMinSdk = addHalfInput(context, sdkRow, mono, "minSdkVersion", model.minSdk, true, density);
        TextInputEditText etTargetSdk = addHalfInput(context, sdkRow, mono, "targetSdkVersion", model.targetSdk, false, density);

        MaterialSwitch swBackup = new MaterialSwitch(context);
        swBackup.setText("android:allowBackup");
        swBackup.setChecked(model.allowBackup);
        root.addView(swBackup);

        MaterialSwitch swRtl = new MaterialSwitch(context);
        swRtl.setText("android:supportsRtl");
        swRtl.setChecked(model.supportsRtl);
        root.addView(swRtl);

        root.addView(sectionHeader(context, "PERMISSIONS (<uses-permission>)", density));

        Set<String> allPerms = new LinkedHashSet<>(Arrays.asList(COMMON_PERMISSIONS));
        allPerms.addAll(model.permissions);
        LinearLayout permContainer = new LinearLayout(context);
        permContainer.setOrientation(LinearLayout.VERTICAL);
        permContainer.setBackgroundResource(R.drawable.bg_card_panel);
        permContainer.setPadding(Math.round(8 * density), Math.round(6 * density),
                Math.round(8 * density), Math.round(6 * density));
        root.addView(permContainer);

        List<CheckBox> permCheckBoxes = new ArrayList<>();
        for (String perm : allPerms) {
            CheckBox cb = new CheckBox(context);
            cb.setTag(perm);
            cb.setText(perm.replace("android.permission.", ""));
            cb.setTypeface(mono);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            cb.setChecked(model.permissions.contains(perm));
            permCheckBoxes.add(cb);
            permContainer.addView(cb);
        }

        LinearLayout addPermRow = new LinearLayout(context);
        addPermRow.setOrientation(LinearLayout.HORIZONTAL);
        addPermRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apLp.topMargin = Math.round(6 * density);
        root.addView(addPermRow, apLp);

        TextInputLayout tilCustomPerm = new TextInputLayout(context);
        tilCustomPerm.setHint("Add custom permission");
        TextInputEditText etCustomPerm = new TextInputEditText(context);
        etCustomPerm.setTypeface(mono);
        etCustomPerm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tilCustomPerm.addView(etCustomPerm);
        addPermRow.addView(tilCustomPerm, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton btnAddPerm = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAddPerm.setText("Add");
        LinearLayout.LayoutParams bApLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bApLp.setMarginStart(Math.round(6 * density));
        addPermRow.addView(btnAddPerm, bApLp);

        btnAddPerm.setOnClickListener(v -> {
            String raw = etCustomPerm.getText() != null ? etCustomPerm.getText().toString().trim() : "";
            if (raw.isEmpty()) return;
            String fullPerm = raw.contains(".") ? raw : "android.permission." + raw.toUpperCase();
            CheckBox cb = new CheckBox(context);
            cb.setTag(fullPerm);
            cb.setText(fullPerm.replace("android.permission.", ""));
            cb.setTypeface(mono);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            cb.setChecked(true);
            permCheckBoxes.add(cb);
            permContainer.addView(cb);
            etCustomPerm.setText("");
        });

        root.addView(sectionHeader(context, "COMPONENTS (EXPORTED)", density));
        LinearLayout compContainer = new LinearLayout(context);
        compContainer.setOrientation(LinearLayout.VERTICAL);
        compContainer.setBackgroundResource(R.drawable.bg_card_panel);
        compContainer.setPadding(Math.round(8 * density), Math.round(6 * density),
                Math.round(8 * density), Math.round(6 * density));
        root.addView(compContainer);

        List<MaterialSwitch> compSwitches = new ArrayList<>();
        if (model.components.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No <activity>, <service>, <receiver>, or <provider> declared yet.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            empty.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
            compContainer.addView(empty);
        } else {
            for (XmlPatcher.ManifestComponent comp : model.components) {
                MaterialSwitch sw = new MaterialSwitch(context);
                sw.setText("<" + comp.tag + "> " + comp.name
                        + (comp.hasLauncherIntent ? " [LAUNCHER]" : ""));
                sw.setTypeface(mono);
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                sw.setChecked(comp.exported);
                sw.setTag(comp);
                compSwitches.add(sw);
                compContainer.addView(sw);
            }
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_sync)
                .setTitle("Visual Manifest Editor")
                .setView(scroll)
                .setPositiveButton("Save Manifest", (d, w) -> {
                    model.packageName = valOf(etPackage, model.packageName);
                    model.appLabel = valOf(etLabel, "@string/app_name");
                    model.appIcon = valOf(etIcon, "");
                    model.appTheme = valOf(etTheme, "");
                    model.minSdk = valOf(etMinSdk, "24");
                    model.targetSdk = valOf(etTargetSdk, "34");
                    model.allowBackup = swBackup.isChecked();
                    model.supportsRtl = swRtl.isChecked();

                    model.permissions.clear();
                    for (CheckBox cb : permCheckBoxes) {
                        if (cb.isChecked() && cb.getTag() instanceof String) {
                            model.permissions.add((String) cb.getTag());
                        }
                    }
                    for (MaterialSwitch sw : compSwitches) {
                        if (sw.getTag() instanceof XmlPatcher.ManifestComponent) {
                            ((XmlPatcher.ManifestComponent) sw.getTag()).exported = sw.isChecked();
                        }
                    }
                    String updatedXml = XmlPatcher.applyManifestModel(manifestXml, model);
                    if (listener != null) {
                        listener.onSaveManifest(updatedXml);
                    }
                    Toast.makeText(context, "Saved AndroidManifest.xml", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    private static TextView sectionHeader(Context context, String title, float density) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ContextCompat.getColor(context, R.color.sandrix_cyan));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * density);
        lp.bottomMargin = Math.round(4 * density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static TextInputEditText addLabeledInput(
            Context context,
            LinearLayout parent,
            Typeface mono,
            String hint,
            String value,
            float density) {
        TextInputLayout til = new TextInputLayout(context);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(context);
        et.setTypeface(mono);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        et.setSingleLine(true);
        et.setText(value != null ? value : "");
        til.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * density);
        parent.addView(til, lp);
        return et;
    }

    private static TextInputEditText addHalfInput(
            Context context,
            LinearLayout row,
            Typeface mono,
            String hint,
            String value,
            boolean marginEnd,
            float density) {
        TextInputLayout til = new TextInputLayout(context);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(context);
        et.setTypeface(mono);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        et.setSingleLine(true);
        et.setText(value != null ? value : "");
        til.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (marginEnd) lp.setMarginEnd(Math.round(6 * density));
        row.addView(til, lp);
        return et;
    }

    private static String valOf(TextInputEditText et, String fallback) {
        String s = et.getText() != null ? et.getText().toString().trim() : "";
        return s.isEmpty() ? fallback : s;
    }
}
