package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;
import com.example.sandrix.engine.ApkAnalyzer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Pure Java Material 3 APK Analyzer Dialog displaying size breakdown, DEX metrics,
 * binary manifest summary, signing verification, and largest archive entries.
 */
public final class ApkAnalyzerDialog {

    private ApkAnalyzerDialog() {}

    public static void show(
            Context context,
            File defaultApk,
            Runnable onPickExternalApk) {
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        Typeface mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(8 * density), pad, pad);

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvStatus = new TextView(context);
        tvStatus.setTypeface(mono, Typeface.BOLD);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvStatus.setTextColor(ContextCompat.getColor(context, R.color.sandrix_cyan));
        tvStatus.setText(defaultApk != null && defaultApk.exists()
                ? "Analyzing " + defaultApk.getName() + "..."
                : "No APK or AAB selected");
        headerRow.addView(tvStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (onPickExternalApk != null) {
            MaterialButton btnPick = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnPick.setText("Pick .apk/.aab");
            btnPick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            headerRow.addView(btnPick);
            AlertDialog[] holder = new AlertDialog[1];
            btnPick.setOnClickListener(v -> {
                if (holder[0] != null && holder[0].isShowing()) holder[0].dismiss();
                onPickExternalApk.run();
            });
            root.addView(headerRow);

            ScrollView scroll = new ScrollView(context);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(420 * density));
            sLp.topMargin = Math.round(8 * density);
            root.addView(scroll, sLp);

            AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                    .setIcon(R.drawable.ic_build)
                    .setTitle("APK / AAB Bundle Analyzer")
                    .setView(root)
                    .setNegativeButton("Close", null)
                    .create();
            holder[0] = dialog;
            dialog.show();

            if (defaultApk != null && defaultApk.exists()) {
                loadAsync(context, defaultApk, tvStatus, content, mono, density);
            } else {
                addInfoCard(context, content, mono, density, "No Artifact Built Yet",
                        "Tap Run to build an APK or AAB, or tap \"Pick .apk/.aab\" to inspect any archive.");
            }
            return;
        }

        root.addView(headerRow);
        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(420 * density)));

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_build)
                .setTitle("APK Analyzer")
                .setView(root)
                .setNegativeButton("Close", null)
                .show();
    }

    private static void loadAsync(
            Context context,
            File apkFile,
            TextView tvStatus,
            LinearLayout content,
            Typeface mono,
            float density) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ApkAnalyzer.ApkReport report = ApkAnalyzer.analyze(apkFile);
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> {
                    tvStatus.setText(report.fileName + " • " + formatBytes(report.fileSizeBytes)
                            + " (" + report.entryCount + " entries)");
                    renderReport(context, content, report, mono, density);
                });
            } catch (Exception e) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> {
                    tvStatus.setText("Analysis failed");
                    addInfoCard(context, content, mono, density, "Error",
                            e.getMessage() != null ? e.getMessage() : "Unable to inspect APK.");
                });
            }
        });
    }

    private static void renderReport(
            Context context,
            LinearLayout content,
            ApkAnalyzer.ApkReport r,
            Typeface mono,
            float density) {
        content.removeAllViews();

        // 1. Overview & Sizes
        StringBuilder sizeSb = new StringBuilder();
        sizeSb.append("Download/File Size: ").append(formatBytes(r.fileSizeBytes)).append('\n');
        sizeSb.append("Uncompressed Total: ").append(formatBytes(r.totalUncompressedBytes)).append('\n');
        for (Map.Entry<String, ApkAnalyzer.CategoryStats> e : r.breakdown.entrySet()) {
            ApkAnalyzer.CategoryStats cs = e.getValue();
            if (cs.fileCount == 0) continue;
            double pct = r.fileSizeBytes > 0 ? (cs.compressedBytes * 100.0 / r.fileSizeBytes) : 0;
            sizeSb.append(String.format(Locale.US, "  %-11s %3d files • %9s (%.1f%%)\n",
                    cs.category, cs.fileCount, formatBytes(cs.compressedBytes), pct));
        }
        addInfoCard(context, content, mono, density, "SIZE BREAKDOWN", sizeSb.toString().trim());

        // 2. DEX Summary
        StringBuilder dexSb = new StringBuilder();
        dexSb.append(String.format(Locale.US, "Totals: %d DEX files • %d classes • %d methods • %d fields • %d strings\n",
                r.dexFiles.size(), r.totalDexClasses, r.totalDexMethods, r.totalDexFields, r.totalDexStrings));
        for (ApkAnalyzer.DexSummary d : r.dexFiles) {
            dexSb.append(String.format(Locale.US, "  %s (v%s): %d classes, %d methods, %s\n",
                    d.entryName, d.version, d.classDefCount, d.methodCount, formatBytes(d.fileSize)));
        }
        addInfoCard(context, content, mono, density, "DEX BYTECODE", dexSb.toString().trim());

        // 3. Binary Manifest
        ApkAnalyzer.BinaryManifestSummary m = r.manifest;
        StringBuilder manSb = new StringBuilder();
        manSb.append("Package: ").append(nonEmpty(m.packageName, "(unknown)")).append('\n');
        manSb.append("SDK: min=").append(nonEmpty(m.minSdkVersion, "?"))
                .append(" • target=").append(nonEmpty(m.targetSdkVersion, "?")).append('\n');
        if (!m.versionName.isEmpty() || !m.versionCode.isEmpty()) {
            manSb.append("Version: ").append(m.versionName).append(" (").append(m.versionCode).append(")\n");
        }
        manSb.append("Components: ").append(m.componentCount)
                .append(" (exported=").append(m.exportedComponentCount).append(")\n");
        manSb.append("Permissions (").append(m.permissions.size()).append("):");
        for (String p : m.permissions) {
            manSb.append("\n  • ").append(p);
        }
        addInfoCard(context, content, mono, density, "BINARY MANIFEST (AXML)", manSb.toString().trim());

        // 4. Signatures
        ApkAnalyzer.SigningSummary s = r.signing;
        StringBuilder sigSb = new StringBuilder();
        sigSb.append("Verified: ").append(s.verified ? "YES" : "NO")
                .append("  [v1=").append(s.v1Verified)
                .append(" v2=").append(s.v2Verified)
                .append(" v3=").append(s.v3Verified).append("]\n");
        for (int i = 0; i < s.signerSubjects.size(); i++) {
            sigSb.append("Signer #").append(i + 1).append(": ").append(s.signerSubjects.get(i)).append('\n');
            if (i < s.signerSha256s.size()) {
                sigSb.append("SHA-256: ").append(s.signerSha256s.get(i)).append('\n');
            }
        }
        for (String err : s.errors) {
            sigSb.append("Error: ").append(err).append('\n');
        }
        addInfoCard(context, content, mono, density, "APK SIGNATURES", sigSb.toString().trim());

        // 5. Largest Entries
        StringBuilder topSb = new StringBuilder();
        for (ApkAnalyzer.ApkEntryInfo info : r.topEntries) {
            topSb.append(String.format(Locale.US, "%8s  %s\n",
                    formatBytes(info.compressedSize), info.path));
        }
        addInfoCard(context, content, mono, density, "LARGEST ENTRIES", topSb.toString().trim());
    }

    private static void addInfoCard(
            Context context,
            LinearLayout parent,
            Typeface mono,
            float density,
            String title,
            String body) {
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

        TextView tvBody = new TextView(context);
        tvBody.setText(body);
        tvBody.setTypeface(mono);
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvBody.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_primary));
        tvBody.setTextIsSelectable(true);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = Math.round(4 * density);
        card.addView(tvBody, bLp);

        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.bottomMargin = Math.round(8 * density);
        parent.addView(card, cLp);
    }

    private static String nonEmpty(String s, String fallback) {
        return s != null && !s.isEmpty() ? s : fallback;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
