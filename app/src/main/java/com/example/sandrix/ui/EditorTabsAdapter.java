package com.example.sandrix.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.sandrix.ide.EditorTab;

import java.util.Collections;
import java.util.List;

public final class EditorTabsAdapter extends RecyclerView.Adapter<EditorTabsAdapter.TabViewHolder> {

    public interface Listener {
        void onSelectTab(int index);
        void onCloseTab(int index);
    }

    private final Listener listener;
    private List<EditorTab> tabs = Collections.emptyList();
    private int activeIndex = -1;

    public EditorTabsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<EditorTab> newTabs, int newActiveIndex) {
        this.tabs = newTabs != null ? newTabs : Collections.emptyList();
        this.activeIndex = newActiveIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_editor_tab, parent, false);
        return new TabViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
        EditorTab tab = tabs.get(position);
        boolean selected = position == activeIndex;
        Context ctx = holder.itemView.getContext();

        holder.tabRoot.setBackgroundResource(
                selected ? R.drawable.bg_pill_selected : R.drawable.bg_pill_surface);
        holder.dirtyDot.setVisibility(tab.isDirty ? View.VISIBLE : View.GONE);
        holder.tvName.setText(tab.file.getName());
        holder.tvName.setTextColor(ContextCompat.getColor(ctx,
                selected ? R.color.sandrix_cyan : R.color.sandrix_text_secondary));
        holder.btnClose.setContentDescription("Close " + tab.file.getName());

        holder.tabRoot.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onSelectTab(pos);
            }
        });
        holder.btnClose.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onCloseTab(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    static final class TabViewHolder extends RecyclerView.ViewHolder {
        final View tabRoot;
        final View dirtyDot;
        final TextView tvName;
        final ImageButton btnClose;

        TabViewHolder(@NonNull View itemView) {
            super(itemView);
            tabRoot = itemView.findViewById(R.id.tab_root);
            dirtyDot = itemView.findViewById(R.id.view_dirty_dot);
            tvName = itemView.findViewById(R.id.tv_tab_name);
            btnClose = itemView.findViewById(R.id.btn_close_tab);
        }
    }
}
