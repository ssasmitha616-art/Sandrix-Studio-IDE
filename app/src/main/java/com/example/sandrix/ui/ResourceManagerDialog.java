package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.sandrix.ide.IdeUiState;
import com.example.sandrix.workspace.WorkspaceManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java Material 3 Visual Resource &amp; Asset Manager dialog.
 */
public final class ResourceManagerDialog {

    public interface Listener {
        void onSaveString(String key, String value);
        void onDeleteString(String key);
        void onSaveColor(String key, String hexColor);
        void onDeleteColor(String key);
        void onSaveDimen(String key, String value);
        void onDeleteDimen(String key);
        void onOpenOrCreateStylesXml();
        void onFindUsages(String resourceSymbol);
        void onCreateXmlDrawable(String name, String templateType, boolean openInEditor);
        void onRequestImportDrawableImage();
        void onRequestImportAssetFile();
        void onOpenFile(File file);
        void onDeleteFile(File file);
    }

    public enum Category {
        STRINGS,
        COLORS,
        DIMENS,
        STYLES,
        DRAWABLES,
        ASSETS
    }

    private static final String[] COLOR_PRESETS = {
            "#38BDF8", "#34D399", "#F59E0B", "#F87171", "#A78BFA", "#0F172A", "#FFFFFF"
    };

    private ResourceManagerDialog() {}

    public static Controller show(
            Context context,
            IdeUiState initialState,
            Listener listener) {
        Controller controller = new Controller(context, listener);
        controller.show(initialState);
        return controller;
    }

    public static final class Controller {
        private final Context context;
        private final Listener listener;

        private AlertDialog dialog;
        private Category activeCategory = Category.STRINGS;
        private IdeUiState lastState;

        private View sectionStrings;
        private View sectionColors;
        private View sectionDimens;
        private View sectionStyles;
        private View sectionDrawables;
        private View sectionAssets;

        private TextInputEditText etStringKey;
        private TextInputEditText etStringValue;
        private TextInputEditText etColorKey;
        private TextInputEditText etColorHex;
        private TextInputEditText etDimenKey;
        private TextInputEditText etDimenValue;
        private View viewColorLiveSwatch;
        private TextInputEditText etDrawableName;
        private ChipGroup cgDrawableTemplates;
        private TextView tvSectionCount;
        private TextView tvEmpty;
        private ResourceEntryAdapter adapter;

        Controller(Context context, Listener listener) {
            this.context = context;
            this.listener = listener;
        }

        void show(IdeUiState initialState) {
            View root = LayoutInflater.from(context).inflate(R.layout.dialog_resource_manager, null);

            ChipGroup cgTabs = root.findViewById(R.id.cg_resource_tabs);
            sectionStrings = root.findViewById(R.id.section_res_strings);
            sectionColors = root.findViewById(R.id.section_res_colors);
            sectionDimens = root.findViewById(R.id.section_res_dimens);
            sectionStyles = root.findViewById(R.id.section_res_styles);
            sectionDrawables = root.findViewById(R.id.section_res_drawables);
            sectionAssets = root.findViewById(R.id.section_res_assets);

            etStringKey = root.findViewById(R.id.et_string_key);
            etStringValue = root.findViewById(R.id.et_string_value);
            MaterialButton btnSaveString = root.findViewById(R.id.btn_save_string);

            etColorKey = root.findViewById(R.id.et_color_key);
            etColorHex = root.findViewById(R.id.et_color_hex);
            viewColorLiveSwatch = root.findViewById(R.id.view_color_live_swatch);
            LinearLayout llColorPresets = root.findViewById(R.id.ll_color_presets);
            MaterialButton btnSaveColor = root.findViewById(R.id.btn_save_color);

            etDimenKey = root.findViewById(R.id.et_dimen_key);
            etDimenValue = root.findViewById(R.id.et_dimen_value);
            MaterialButton btnSaveDimen = root.findViewById(R.id.btn_save_dimen);
            MaterialButton btnOpenStyles = root.findViewById(R.id.btn_open_styles_xml);

            etDrawableName = root.findViewById(R.id.et_drawable_name);
            cgDrawableTemplates = root.findViewById(R.id.cg_drawable_templates);
            MaterialButton btnCreateDrawable = root.findViewById(R.id.btn_create_drawable_xml);
            MaterialButton btnImportImage = root.findViewById(R.id.btn_import_drawable_image);
            MaterialButton btnImportAsset = root.findViewById(R.id.btn_import_asset_file);

            tvSectionCount = root.findViewById(R.id.tv_res_section_count);
            tvEmpty = root.findViewById(R.id.tv_res_empty);
            RecyclerView rvEntries = root.findViewById(R.id.rv_resource_entries);
            rvEntries.setLayoutManager(new LinearLayoutManager(context));

            adapter = new ResourceEntryAdapter(new ResourceEntryAdapter.Callback() {
                @Override
                public void onItemClicked(ResourceEntry entry) {
                    if (entry.category == Category.STRINGS) {
                        etStringKey.setText(entry.key);
                        etStringValue.setText(entry.value);
                    } else if (entry.category == Category.COLORS) {
                        etColorKey.setText(entry.key);
                        etColorHex.setText(entry.value);
                        updateSwatchPreview(entry.value);
                    } else if (entry.category == Category.DIMENS) {
                        etDimenKey.setText(entry.key);
                        etDimenValue.setText(entry.value);
                    } else if (entry.category == Category.DRAWABLES && entry.file != null) {
                        if (entry.file.getName().endsWith(".xml")) {
                            listener.onOpenFile(entry.file);
                            dismissSafe();
                        } else {
                            Toast.makeText(context,
                                    "Use @drawable/" + stripExt(entry.file.getName()) + " in XML",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else if (entry.category == Category.ASSETS && entry.file != null) {
                        String n = entry.file.getName().toLowerCase(Locale.ROOT);
                        if (n.endsWith(".json") || n.endsWith(".txt") || n.endsWith(".xml") || n.endsWith(".md")) {
                            listener.onOpenFile(entry.file);
                            dismissSafe();
                        }
                    }
                }

                @Override
                public void onFindUsagesClicked(ResourceEntry entry) {
                    String query = entry.category == Category.DRAWABLES
                            ? stripExt(entry.key)
                            : entry.key;
                    listener.onFindUsages(query);
                }

                @Override
                public void onDeleteClicked(ResourceEntry entry) {
                    if (entry.category == Category.STRINGS) {
                        listener.onDeleteString(entry.key);
                    } else if (entry.category == Category.COLORS) {
                        listener.onDeleteColor(entry.key);
                    } else if (entry.category == Category.DIMENS) {
                        listener.onDeleteDimen(entry.key);
                    } else if (entry.file != null) {
                        listener.onDeleteFile(entry.file);
                    }
                }
            });
            rvEntries.setAdapter(adapter);

            populateColorPresets(llColorPresets);
            updateSwatchPreview(textOf(etColorHex));

            etColorHex.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    updateSwatchPreview(s != null ? s.toString() : "");
                }
            });

            cgTabs.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                if (id == R.id.chip_res_colors) {
                    activeCategory = Category.COLORS;
                } else if (id == R.id.chip_res_dimens) {
                    activeCategory = Category.DIMENS;
                } else if (id == R.id.chip_res_styles) {
                    activeCategory = Category.STYLES;
                } else if (id == R.id.chip_res_drawables) {
                    activeCategory = Category.DRAWABLES;
                } else if (id == R.id.chip_res_assets) {
                    activeCategory = Category.ASSETS;
                } else {
                    activeCategory = Category.STRINGS;
                }
                applyCategoryVisibility();
                if (lastState != null) {
                    renderEntries(lastState);
                }
            });

            btnSaveString.setOnClickListener(v -> {
                String key = textOf(etStringKey);
                if (key.isEmpty()) {
                    Toast.makeText(context, "Enter a string resource name", Toast.LENGTH_SHORT).show();
                    return;
                }
                listener.onSaveString(key, textOf(etStringValue));
                etStringKey.setText("");
                etStringValue.setText("");
            });

            btnSaveColor.setOnClickListener(v -> {
                String key = textOf(etColorKey);
                String hex = textOf(etColorHex);
                if (key.isEmpty()) {
                    Toast.makeText(context, "Enter a color resource name", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (WorkspaceManager.normalizeHexColor(hex) == null) {
                    Toast.makeText(context, "Invalid hex color (use #RRGGBB)", Toast.LENGTH_SHORT).show();
                    return;
                }
                listener.onSaveColor(key, hex);
                etColorKey.setText("");
            });

            btnSaveDimen.setOnClickListener(v -> {
                String key = textOf(etDimenKey);
                String val = textOf(etDimenValue);
                if (key.isEmpty()) {
                    Toast.makeText(context, "Enter a dimen resource name", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (val.isEmpty()) val = "16dp";
                listener.onSaveDimen(key, val);
                etDimenKey.setText("");
            });

            btnOpenStyles.setOnClickListener(v -> {
                listener.onOpenOrCreateStylesXml();
                dismissSafe();
            });

            btnCreateDrawable.setOnClickListener(v -> {
                String name = textOf(etDrawableName);
                String tpl = selectedDrawableTemplate();
                listener.onCreateXmlDrawable(name, tpl, true);
                dismissSafe();
            });

            btnImportImage.setOnClickListener(v -> listener.onRequestImportDrawableImage());
            btnImportAsset.setOnClickListener(v -> listener.onRequestImportAssetFile());

            applyCategoryVisibility();

            dialog = new MaterialAlertDialogBuilder(context)
                    .setIcon(R.drawable.ic_palette)
                    .setTitle(R.string.resource_manager_title)
                    .setView(root)
                    .setNegativeButton("Close", null)
                    .create();
            dialog.show();

            update(initialState);
        }

        public boolean isShowing() {
            return dialog != null && dialog.isShowing();
        }

        public void update(@Nullable IdeUiState state) {
            if (state == null) return;
            this.lastState = state;
            renderEntries(state);
        }

        public void dismissSafe() {
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        }

        private void applyCategoryVisibility() {
            sectionStrings.setVisibility(activeCategory == Category.STRINGS ? View.VISIBLE : View.GONE);
            sectionColors.setVisibility(activeCategory == Category.COLORS ? View.VISIBLE : View.GONE);
            sectionDimens.setVisibility(activeCategory == Category.DIMENS ? View.VISIBLE : View.GONE);
            sectionStyles.setVisibility(activeCategory == Category.STYLES ? View.VISIBLE : View.GONE);
            sectionDrawables.setVisibility(activeCategory == Category.DRAWABLES ? View.VISIBLE : View.GONE);
            sectionAssets.setVisibility(activeCategory == Category.ASSETS ? View.VISIBLE : View.GONE);
        }

        private void renderEntries(IdeUiState state) {
            List<ResourceEntry> entries = new ArrayList<>();
            if (activeCategory == Category.STRINGS) {
                for (Map.Entry<String, String> e : state.stringResources.entrySet()) {
                    entries.add(new ResourceEntry(Category.STRINGS, e.getKey(), e.getValue(), null));
                }
                tvSectionCount.setText("res/values/strings.xml (" + entries.size() + ")");
            } else if (activeCategory == Category.COLORS) {
                for (Map.Entry<String, String> e : state.colorResources.entrySet()) {
                    entries.add(new ResourceEntry(Category.COLORS, e.getKey(), e.getValue(), null));
                }
                tvSectionCount.setText("res/values/colors.xml (" + entries.size() + ")");
            } else if (activeCategory == Category.DIMENS) {
                for (Map.Entry<String, String> e : state.dimenResources.entrySet()) {
                    entries.add(new ResourceEntry(Category.DIMENS, e.getKey(), e.getValue(), null));
                }
                tvSectionCount.setText("res/values/dimens.xml (" + entries.size() + ")");
            } else if (activeCategory == Category.STYLES) {
                for (String id : state.xmlResourceIds) {
                    entries.add(new ResourceEntry(Category.STYLES, id, "@+id/" + id + " • tap search to find usages", null));
                }
                tvSectionCount.setText("res/values/styles.xml & XML IDs (" + entries.size() + ")");
            } else if (activeCategory == Category.DRAWABLES) {
                for (File f : state.drawableFiles) {
                    String tag = f.getName().endsWith(".xml") ? "XML Drawable" : "Image Asset";
                    entries.add(new ResourceEntry(
                            Category.DRAWABLES,
                            f.getName(),
                            "@drawable/" + stripExt(f.getName()) + " • " + tag + " • " + formatBytes(f.length()),
                            f));
                }
                tvSectionCount.setText("res/drawable/ (" + entries.size() + ")");
            } else {
                for (File f : state.assetFiles) {
                    entries.add(new ResourceEntry(
                            Category.ASSETS,
                            f.getName(),
                            "assets/" + f.getName() + " • " + formatBytes(f.length()),
                            f));
                }
                tvSectionCount.setText("assets/ (" + entries.size() + ")");
            }

            tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.submit(entries);
        }

        private void populateColorPresets(LinearLayout container) {
            float density = context.getResources().getDisplayMetrics().density;
            int size = Math.round(32 * density);
            int margin = Math.round(8 * density);
            for (String hex : COLOR_PRESETS) {
                View chip = new View(context);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(Color.parseColor(hex));
                bg.setStroke(Math.round(1.5f * density), ContextCompat.getColor(context, R.color.sandrix_border));
                chip.setBackground(bg);
                chip.setContentDescription("Preset color " + hex);
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> {
                    etColorHex.setText(hex);
                    updateSwatchPreview(hex);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMarginEnd(margin);
                container.addView(chip, lp);
            }
        }

        private void updateSwatchPreview(String rawHex) {
            String normalized = WorkspaceManager.normalizeHexColor(rawHex);
            int parsed = Color.TRANSPARENT;
            if (normalized != null) {
                try {
                    parsed = Color.parseColor(normalized);
                } catch (IllegalArgumentException ignored) {
                }
            }
            float density = context.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(8f * density);
            bg.setColor(parsed);
            bg.setStroke(Math.round(1.5f * density), ContextCompat.getColor(context, R.color.sandrix_border));
            viewColorLiveSwatch.setBackground(bg);
        }

        private String selectedDrawableTemplate() {
            int checked = cgDrawableTemplates.getCheckedChipId();
            if (checked == R.id.chip_tpl_gradient) return "GRADIENT";
            if (checked == R.id.chip_tpl_circle) return "CIRCLE_BADGE";
            if (checked == R.id.chip_tpl_star) return "STAR_VECTOR";
            return "ROUNDED_CARD";
        }
    }

    private static final class ResourceEntry {
        final Category category;
        final String key;
        final String value;
        @Nullable final File file;

        ResourceEntry(Category category, String key, String value, @Nullable File file) {
            this.category = category;
            this.key = key != null ? key : "";
            this.value = value != null ? value : "";
            this.file = file;
        }
    }

    private static final class ResourceEntryAdapter extends RecyclerView.Adapter<ResourceEntryAdapter.VH> {
        interface Callback {
            void onItemClicked(ResourceEntry entry);
            void onFindUsagesClicked(ResourceEntry entry);
            void onDeleteClicked(ResourceEntry entry);
        }

        private final Callback callback;
        private List<ResourceEntry> items = new ArrayList<>();

        ResourceEntryAdapter(Callback callback) {
            this.callback = callback;
        }

        void submit(List<ResourceEntry> next) {
            this.items = next != null ? next : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_resource_entry, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ResourceEntry entry = items.get(position);
            Context ctx = holder.itemView.getContext();
            holder.tvKey.setText(entry.key);
            holder.tvValue.setText(entry.value);

            if (entry.category == Category.COLORS) {
                holder.viewSwatch.setVisibility(View.VISIBLE);
                holder.ivIcon.setVisibility(View.GONE);
                int color = Color.GRAY;
                String normalized = WorkspaceManager.normalizeHexColor(entry.value);
                if (normalized != null) {
                    try {
                        color = Color.parseColor(normalized);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                float density = ctx.getResources().getDisplayMetrics().density;
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(color);
                bg.setStroke(Math.round(1 * density), ContextCompat.getColor(ctx, R.color.sandrix_border));
                holder.viewSwatch.setBackground(bg);
            } else {
                holder.viewSwatch.setVisibility(View.GONE);
                holder.ivIcon.setVisibility(View.VISIBLE);
                int iconRes = R.drawable.ic_code;
                if (entry.category == Category.DRAWABLES) {
                    iconRes = (entry.file != null && entry.file.getName().endsWith(".xml"))
                            ? R.drawable.ic_palette
                            : R.drawable.ic_image;
                } else if (entry.category == Category.ASSETS) {
                    iconRes = R.drawable.ic_storage;
                }
                holder.ivIcon.setImageResource(iconRes);
            }

            holder.btnUsages.setVisibility(entry.category == Category.ASSETS ? View.GONE : View.VISIBLE);
            holder.btnDelete.setVisibility(entry.category == Category.STYLES ? View.GONE : View.VISIBLE);

            holder.itemView.setOnClickListener(v -> callback.onItemClicked(entry));
            holder.btnUsages.setOnClickListener(v -> callback.onFindUsagesClicked(entry));
            holder.btnDelete.setOnClickListener(v -> callback.onDeleteClicked(entry));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final View viewSwatch;
            final ImageView ivIcon;
            final TextView tvKey;
            final TextView tvValue;
            final ImageButton btnUsages;
            final ImageButton btnDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                viewSwatch = itemView.findViewById(R.id.view_res_swatch);
                ivIcon = itemView.findViewById(R.id.iv_res_icon);
                tvKey = itemView.findViewById(R.id.tv_res_key);
                tvValue = itemView.findViewById(R.id.tv_res_value);
                btnUsages = itemView.findViewById(R.id.btn_res_usages);
                btnDelete = itemView.findViewById(R.id.btn_res_delete);
            }
        }
    }

    private static String textOf(@Nullable TextInputEditText et) {
        return et != null && et.getText() != null ? et.getText().toString().trim() : "";
    }

    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
