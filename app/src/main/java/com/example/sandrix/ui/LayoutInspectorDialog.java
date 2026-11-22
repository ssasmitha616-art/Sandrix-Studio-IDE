package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.example.R;
import com.example.sandrix.preview.LayoutPreviewer;
import com.example.sandrix.xml.XmlPatcher;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive Visual Layout Inspector allowing hierarchy browsing, live preview highlight,
 * and bidirectional XML attribute editing.
 */
public final class LayoutInspectorDialog {

    public interface Listener {
        void onSelectNodeInPreview(int nodeIndex);
        void onJumpToXmlLine(int lineNumber);
        void onApplyUpdatedLayoutXml(String updatedXml);
    }

    private LayoutInspectorDialog() {}

    public static void show(
            Context context,
            String layoutName,
            String layoutXmlSource,
            List<LayoutPreviewer.InspectedNode> nodes,
            int initialSelectedIndex,
            Listener listener) {
        if (nodes == null || nodes.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("Layout Inspector")
                    .setMessage("No rendered XML view hierarchy found for " + (layoutName != null ? layoutName : "layout") + ".")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final int[] selectedIdx = {initialSelectedIndex >= 0 && initialSelectedIndex < nodes.size() ? initialSelectedIndex : 0};
        final String[] workingXml = {layoutXmlSource != null ? layoutXmlSource : ""};

        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        Typeface mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Math.round(6 * density), pad, pad);

        TextView tvSummary = new TextView(context);
        tvSummary.setText(layoutName + " • " + nodes.size() + " view nodes");
        tvSummary.setTypeface(mono);
        tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvSummary.setTextColor(ContextCompat.getColor(context, R.color.sandrix_cyan));
        root.addView(tvSummary);

        TextView tvTreeHdr = new TextView(context);
        tvTreeHdr.setText("COMPONENT TREE");
        tvTreeHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvTreeHdr.setTypeface(Typeface.DEFAULT_BOLD);
        tvTreeHdr.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
        LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hdrLp.topMargin = Math.round(8 * density);
        root.addView(tvTreeHdr, hdrLp);

        ScrollView treeScroll = new ScrollView(context);
        treeScroll.setBackgroundResource(R.drawable.bg_card_panel);
        LinearLayout treeContainer = new LinearLayout(context);
        treeContainer.setOrientation(LinearLayout.VERTICAL);
        treeContainer.setPadding(Math.round(8 * density), Math.round(6 * density),
                Math.round(8 * density), Math.round(6 * density));
        treeScroll.addView(treeContainer);
        LinearLayout.LayoutParams treeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(165 * density));
        treeLp.topMargin = Math.round(4 * density);
        root.addView(treeScroll, treeLp);

        TextView tvPropsHdr = new TextView(context);
        tvPropsHdr.setText("DECLARED ATTRIBUTES & LIVE BOUNDS");
        tvPropsHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvPropsHdr.setTypeface(Typeface.DEFAULT_BOLD);
        tvPropsHdr.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
        LinearLayout.LayoutParams pHdrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pHdrLp.topMargin = Math.round(10 * density);
        root.addView(tvPropsHdr, pHdrLp);

        ScrollView propsScroll = new ScrollView(context);
        propsScroll.setBackgroundResource(R.drawable.bg_card_panel);
        LinearLayout propsForm = new LinearLayout(context);
        propsForm.setOrientation(LinearLayout.VERTICAL);
        propsForm.setPadding(Math.round(10 * density), Math.round(8 * density),
                Math.round(10 * density), Math.round(8 * density));
        propsScroll.addView(propsForm);
        LinearLayout.LayoutParams propsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(250 * density));
        propsLp.topMargin = Math.round(4 * density);
        root.addView(propsScroll, propsLp);

        final TextInputEditText etId = new TextInputEditText(context);
        final TextInputEditText etWidth = new TextInputEditText(context);
        final TextInputEditText etHeight = new TextInputEditText(context);
        final TextInputEditText etText = new TextInputEditText(context);
        final TextInputEditText etTextSize = new TextInputEditText(context);
        final TextInputEditText etTextColor = new TextInputEditText(context);
        final TextInputEditText etBg = new TextInputEditText(context);
        final TextInputEditText etPadding = new TextInputEditText(context);
        final TextInputEditText etMargin = new TextInputEditText(context);
        final TextInputEditText etVisibility = new TextInputEditText(context);
        final TextInputEditText etCustomAttr = new TextInputEditText(context);
        final TextInputEditText etCustomVal = new TextInputEditText(context);
        final TextView tvBoundsBadge = new TextView(context);

        tvBoundsBadge.setTypeface(mono);
        tvBoundsBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvBoundsBadge.setTextColor(ContextCompat.getColor(context, R.color.sandrix_emerald));
        propsForm.addView(tvBoundsBadge);

        Map<String, TextInputEditText> fieldMap = new LinkedHashMap<>();
        fieldMap.put("android:id", etId);
        fieldMap.put("android:layout_width", etWidth);
        fieldMap.put("android:layout_height", etHeight);
        fieldMap.put("android:text", etText);
        fieldMap.put("android:textSize", etTextSize);
        fieldMap.put("android:textColor", etTextColor);
        fieldMap.put("android:background", etBg);
        fieldMap.put("android:padding", etPadding);
        fieldMap.put("android:layout_margin", etMargin);
        fieldMap.put("android:visibility", etVisibility);

        for (Map.Entry<String, TextInputEditText> entry : fieldMap.entrySet()) {
            TextInputLayout til = new TextInputLayout(context);
            til.setHint(entry.getKey());
            entry.getValue().setTypeface(mono);
            entry.getValue().setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            entry.getValue().setSingleLine(true);
            til.addView(entry.getValue());
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = Math.round(6 * density);
            propsForm.addView(til, flp);
        }

        TextInputLayout tilCustomName = new TextInputLayout(context);
        tilCustomName.setHint("Extra Attribute (e.g. android:gravity)");
        etCustomAttr.setTypeface(mono);
        etCustomAttr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        etCustomAttr.setSingleLine(true);
        tilCustomName.addView(etCustomAttr);

        TextInputLayout tilCustomVal = new TextInputLayout(context);
        tilCustomVal.setHint("Extra Attribute Value");
        etCustomVal.setTypeface(mono);
        etCustomVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        etCustomVal.setSingleLine(true);
        tilCustomVal.addView(etCustomVal);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Math.round(8 * density);
        propsForm.addView(tilCustomName, clp);
        propsForm.addView(tilCustomVal, clp);

        final Runnable[] renderAll = new Runnable[1];
        renderAll[0] = () -> {
            treeContainer.removeAllViews();
            LayoutPreviewer.InspectedNode active = nodes.get(selectedIdx[0]);
            for (LayoutPreviewer.InspectedNode node : nodes) {
                TextView row = new TextView(context);
                StringBuilder indent = new StringBuilder();
                for (int d = 0; d < node.depth; d++) indent.append("  ");
                String label = indent + (node.depth > 0 ? "└ " : "") + "<" + node.tagName + ">"
                        + (node.idName.isEmpty() ? "" : "  " + node.idName)
                        + "  :L" + node.lineNumber;
                row.setText(label);
                row.setTypeface(mono, node.index == active.index ? Typeface.BOLD : Typeface.NORMAL);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setMaxLines(1);
                row.setPadding(Math.round(8 * density), Math.round(6 * density),
                        Math.round(8 * density), Math.round(6 * density));
                boolean isSel = node.index == active.index;
                row.setBackgroundResource(isSel ? R.drawable.bg_pill_selected : android.R.color.transparent);
                row.setTextColor(ContextCompat.getColor(context,
                        isSel ? R.color.sandrix_cyan : R.color.sandrix_text_primary));
                row.setOnClickListener(v -> {
                    selectedIdx[0] = node.index;
                    if (listener != null) listener.onSelectNodeInPreview(node.index);
                    renderAll[0].run();
                });
                treeContainer.addView(row);
            }

            // Populate bounds & attribute fields
            String dims = "Not laid out yet";
            if (active.renderedView != null && active.renderedView.getWidth() > 0) {
                int wDp = Math.round(active.renderedView.getWidth() / density);
                int hDp = Math.round(active.renderedView.getHeight() / density);
                dims = wDp + "dp × " + hDp + "dp • visibility=" + visLabel(active.renderedView.getVisibility());
            }
            tvBoundsBadge.setText("<" + active.tagName + "> Line " + active.lineNumber + " • " + dims);

            for (Map.Entry<String, TextInputEditText> entry : fieldMap.entrySet()) {
                String v = active.attributes.get(entry.getKey());
                entry.getValue().setText(v != null ? v : "");
            }
            etCustomAttr.setText("");
            etCustomVal.setText("");
        };

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = Math.round(10 * density);

        MaterialButton btnJumpXml = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnJumpXml.setText("Jump to XML");
        btnJumpXml.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);

        MaterialButton btnApply = new MaterialButton(context);
        btnApply.setText("Apply to XML");
        btnApply.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyLp.setMarginStart(Math.round(8 * density));

        actionRow.addView(btnJumpXml);
        actionRow.addView(btnApply, applyLp);
        root.addView(actionRow, actLp);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_preview)
                .setTitle("Layout Inspector")
                .setView(root)
                .setNegativeButton("Close", null)
                .create();

        btnJumpXml.setOnClickListener(v -> {
            LayoutPreviewer.InspectedNode node = nodes.get(selectedIdx[0]);
            if (listener != null) {
                listener.onSelectNodeInPreview(node.index);
                listener.onJumpToXmlLine(node.lineNumber);
            }
            dialog.dismiss();
        });

        btnApply.setOnClickListener(v -> {
            LayoutPreviewer.InspectedNode node = nodes.get(selectedIdx[0]);
            String patched = workingXml[0];
            for (Map.Entry<String, TextInputEditText> entry : fieldMap.entrySet()) {
                String attrName = entry.getKey();
                String original = node.attributes.get(attrName);
                String edited = entry.getValue().getText() != null
                        ? entry.getValue().getText().toString().trim() : "";
                String origNorm = original != null ? original.trim() : "";
                if (!edited.equals(origNorm)) {
                    patched = XmlPatcher.patchElementAttributeByOccurrence(patched, node.index, attrName, edited);
                    if (edited.isEmpty()) node.attributes.remove(attrName);
                    else node.attributes.put(attrName, edited);
                }
            }
            String extraAttr = etCustomAttr.getText() != null ? etCustomAttr.getText().toString().trim() : "";
            String extraVal = etCustomVal.getText() != null ? etCustomVal.getText().toString().trim() : "";
            if (!extraAttr.isEmpty()) {
                patched = XmlPatcher.patchElementAttributeByOccurrence(patched, node.index, extraAttr, extraVal);
                if (extraVal.isEmpty()) node.attributes.remove(extraAttr);
                else node.attributes.put(extraAttr, extraVal);
            }
            workingXml[0] = patched;
            if (listener != null) {
                listener.onApplyUpdatedLayoutXml(patched);
            }
            Toast.makeText(context, "Updated <" + node.tagName + "> in XML", Toast.LENGTH_SHORT).show();
            renderAll[0].run();
        });

        if (listener != null) {
            listener.onSelectNodeInPreview(selectedIdx[0]);
        }
        renderAll[0].run();
        dialog.show();
    }

    private static String visLabel(int visibility) {
        if (visibility == View.GONE) return "gone";
        if (visibility == View.INVISIBLE) return "invisible";
        return "visible";
    }
}
