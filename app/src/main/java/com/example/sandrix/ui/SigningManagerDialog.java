package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;
import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.engine.KeystoreGenerator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.concurrent.Executors;

/**
 * Pure Java Material 3 Release Signing &amp; Keystore Manager dialog.
 * Allows generating PKCS12 keystores and configuring {@code release-signing.properties}.
 */
public final class SigningManagerDialog {

    public interface Listener {
        void onSigningConfigUpdated(String bannerMessage, boolean triggerReleaseBuildNow);
    }

    private SigningManagerDialog() {}

    public static void show(Context context, File projectDir, Listener listener) {
        if (projectDir == null || !projectDir.exists()) {
            Toast.makeText(context, "Open a project first.", Toast.LENGTH_SHORT).show();
            return;
        }
        File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(projectDir);
        KeystoreGenerator.SigningConfigModel current = KeystoreGenerator.loadSigningConfig(projectDir);

        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        Typeface mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono);

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(8 * density), pad, pad);
        scroll.addView(root);

        // Status Card
        LinearLayout statusCard = new LinearLayout(context);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setBackgroundResource(R.drawable.bg_card_panel);
        statusCard.setPadding(pad, pad, pad, pad);

        TextView tvStatusTitle = new TextView(context);
        tvStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvStatusTitle.setTypeface(null, Typeface.BOLD);
        statusCard.addView(tvStatusTitle);

        TextView tvStatusBody = new TextView(context);
        tvStatusBody.setTypeface(mono);
        tvStatusBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvStatusBody.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
        statusCard.addView(tvStatusBody);
        root.addView(statusCard);

        // Existing / Active Config Inputs
        addSectionTitle(context, root, "Release Signing Config (release-signing.properties)", density);
        TextInputEditText etStoreFile = addInput(context, root, "Keystore file path (relative or absolute)",
                current.storeFilePath.isEmpty() ? "release-key.p12" : current.storeFilePath, false, density);
        TextInputEditText etStoreType = addInput(context, root, "Store Type (PKCS12 / JKS)",
                current.storeType, false, density);
        TextInputEditText etAlias = addInput(context, root, "Key Alias",
                current.keyAlias.isEmpty() ? "release" : current.keyAlias, false, density);
        TextInputEditText etStorePass = addInput(context, root, "Store Password",
                current.storePassword, true, density);
        TextInputEditText etKeyPass = addInput(context, root, "Key Password (blank = same as store)",
                current.keyPassword, true, density);

        LinearLayout existingActions = new LinearLayout(context);
        existingActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = Math.round(8 * density);

        MaterialButton btnSaveCfg = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnSaveCfg.setText("Verify & Save");
        btnSaveCfg.setAllCaps(false);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.setMarginEnd(Math.round(6 * density));
        existingActions.addView(btnSaveCfg, b1);

        MaterialButton btnClearCfg = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnClearCfg.setText("Clear");
        btnClearCfg.setAllCaps(false);
        btnClearCfg.setTextColor(ContextCompat.getColor(context, R.color.sandrix_coral));
        existingActions.addView(btnClearCfg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(existingActions, actLp);

        // New Keystore Generator Card
        addSectionTitle(context, root, "Generate New Release Keystore (.p12)", density);
        TextInputEditText etGenFile = addInput(context, root, "New Keystore filename", "release-key.p12", false, density);
        TextInputEditText etGenAlias = addInput(context, root, "New Key Alias", "release", false, density);
        TextInputEditText etGenPass = addInput(context, root, "New Password (min 6 chars)", "", true, density);
        TextInputEditText etGenCn = addInput(context, root, "Common Name (CN)", "Sandrix Developer", false, density);
        TextInputEditText etGenOrg = addInput(context, root, "Organization (O)", "Sandrix Studio", false, density);
        TextInputEditText etGenYears = addInput(context, root, "Validity (Years)", "25", false, density);
        etGenYears.setInputType(InputType.TYPE_CLASS_NUMBER);

        MaterialButton btnGenerate = new MaterialButton(context);
        btnGenerate.setText("Generate Keystore & Save Config");
        btnGenerate.setAllCaps(false);
        LinearLayout.LayoutParams genLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        genLp.topMargin = Math.round(10 * density);
        root.addView(btnGenerate, genLp);

        Runnable refreshStatus = () -> {
            KeystoreGenerator.SigningConfigModel cfg = KeystoreGenerator.loadSigningConfig(projectDir);
            if (!cfg.configured) {
                tvStatusTitle.setText("Release Signing Not Configured");
                tvStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.sandrix_amber));
                tvStatusBody.setText("Generate a .p12 keystore below or enter an existing keystore path & password.");
                return;
            }
            File ksFile = new File(cfg.storeFilePath);
            if (!ksFile.isAbsolute()) {
                ksFile = new File(effectiveRoot, cfg.storeFilePath);
            }
            KeystoreGenerator.KeystoreInspection insp = KeystoreGenerator.inspectKeystore(ksFile, cfg.storePassword);
            if (insp.valid) {
                tvStatusTitle.setText("Release Signing Ready (" + insp.storeType + ")");
                tvStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.sandrix_emerald));
                tvStatusBody.setText("File: " + cfg.storeFilePath
                        + "\nAlias: " + cfg.keyAlias
                        + "\nSubject: " + insp.subject
                        + "\nExpires: " + insp.validUntil
                        + "\nSHA-256: " + insp.sha256);
            } else {
                tvStatusTitle.setText("Config Found — Keystore Issue");
                tvStatusTitle.setTextColor(ContextCompat.getColor(context, R.color.sandrix_coral));
                tvStatusBody.setText("File: " + ksFile.getAbsolutePath() + "\n" + insp.errorMessage);
            }
        };
        refreshStatus.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_build)
                .setTitle("Release Signing Manager")
                .setView(scroll)
                .setPositiveButton("Build Signed Release APK", (d, w) -> {
                    if (listener != null) {
                        listener.onSigningConfigUpdated("Starting Release APK build...", true);
                    }
                })
                .setNegativeButton("Close", null)
                .create();

        btnSaveCfg.setOnClickListener(v -> {
            String path = textOf(etStoreFile);
            String type = textOf(etStoreType);
            String alias = textOf(etAlias);
            String storePass = textOf(etStorePass);
            String keyPass = textOf(etKeyPass);
            if (path.isEmpty() || alias.isEmpty() || storePass.isEmpty()) {
                Toast.makeText(context, "Store path, alias, and password are required.", Toast.LENGTH_SHORT).show();
                return;
            }
            File target = new File(path);
            if (!target.isAbsolute()) target = new File(effectiveRoot, path);
            KeystoreGenerator.KeystoreInspection insp = KeystoreGenerator.inspectKeystore(target, storePass);
            if (!insp.valid) {
                Toast.makeText(context, "Keystore check failed: " + insp.errorMessage, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                KeystoreGenerator.saveSigningConfig(projectDir,
                        new KeystoreGenerator.SigningConfigModel(
                                path,
                                type.isEmpty() ? insp.storeType : type,
                                storePass,
                                alias,
                                keyPass.isEmpty() ? storePass : keyPass,
                                true));
                refreshStatus.run();
                if (listener != null) {
                    listener.onSigningConfigUpdated("Saved release-signing.properties", false);
                }
            } catch (Exception e) {
                Toast.makeText(context, "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        btnClearCfg.setOnClickListener(v -> {
            KeystoreGenerator.clearSigningConfig(projectDir);
            refreshStatus.run();
            if (listener != null) {
                listener.onSigningConfigUpdated("Cleared release-signing.properties", false);
            }
        });

        btnGenerate.setOnClickListener(v -> {
            String filename = textOf(etGenFile);
            if (filename.isEmpty()) filename = "release-key.p12";
            if (!filename.endsWith(".p12") && !filename.endsWith(".keystore") && !filename.endsWith(".jks")) {
                filename += ".p12";
            }
            String alias = textOf(etGenAlias);
            if (alias.isEmpty()) alias = "release";
            String pass = textOf(etGenPass);
            if (pass.length() < 6) {
                Toast.makeText(context, "Enter a password of at least 6 characters.", Toast.LENGTH_SHORT).show();
                return;
            }
            String cn = textOf(etGenCn);
            String org = textOf(etGenOrg);
            int years = 25;
            try {
                years = Integer.parseInt(textOf(etGenYears));
            } catch (Exception ignored) {
            }
            final String fName = filename;
            final String fAlias = alias;
            final int fYears = years;
            btnGenerate.setEnabled(false);
            btnGenerate.setText("Generating RSA-2048 Keystore...");

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    File outKs = new File(effectiveRoot, fName);
                    KeystoreGenerator.generatePkcs12Keystore(
                            outKs, pass, fAlias, pass, cn, org, "US", fYears);
                    KeystoreGenerator.saveSigningConfig(projectDir,
                            new KeystoreGenerator.SigningConfigModel(
                                    fName, "PKCS12", pass, fAlias, pass, true));
                    root.post(() -> {
                        btnGenerate.setEnabled(true);
                        btnGenerate.setText("Generate Keystore & Save Config");
                        etStoreFile.setText(fName);
                        etStoreType.setText("PKCS12");
                        etAlias.setText(fAlias);
                        etStorePass.setText(pass);
                        etKeyPass.setText(pass);
                        refreshStatus.run();
                        if (listener != null) {
                            listener.onSigningConfigUpdated("Generated " + fName + " & saved config", false);
                        }
                    });
                } catch (Throwable t) {
                    root.post(() -> {
                        btnGenerate.setEnabled(true);
                        btnGenerate.setText("Generate Keystore & Save Config");
                        Toast.makeText(context, "Keystore generation failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
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
        lp.topMargin = Math.round(14 * density);
        lp.bottomMargin = Math.round(4 * density);
        parent.addView(tv, lp);
    }

    private static TextInputEditText addInput(
            Context context,
            LinearLayout parent,
            String hint,
            String value,
            boolean password,
            float density) {
        TextInputLayout til = new TextInputLayout(context);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(context);
        et.setSingleLine(true);
        et.setText(value != null ? value : "");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        til.addView(et);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * density);
        parent.addView(til, lp);
        return et;
    }

    private static String textOf(TextInputEditText et) {
        return et != null && et.getText() != null ? et.getText().toString().trim() : "";
    }
}
