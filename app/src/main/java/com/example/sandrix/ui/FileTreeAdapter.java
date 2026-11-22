package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.sandrix.ide.FileNode;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.NodeViewHolder> {

    public interface Listener {
        void onNodeClicked(FileNode node);
        void onDeleteClicked(File file);
    }

    private final Listener listener;
    private List<FileNode> nodes = Collections.emptyList();
    private String activeFilePath = null;

    public FileTreeAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<FileNode> newNodes, String activePath) {
        this.nodes = newNodes != null ? newNodes : Collections.emptyList();
        this.activeFilePath = activePath;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public NodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file_node, parent, false);
        return new NodeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull NodeViewHolder holder, int position) {
        FileNode node = nodes.get(position);
        Context ctx = holder.itemView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int startPad = Math.round((8 + node.depth * 14) * density);
        holder.root.setPaddingRelative(startPad,
                holder.root.getPaddingTop(),
                holder.root.getPaddingEnd(),
                holder.root.getPaddingBottom());

        boolean isActive = !node.isDirectory
                && activeFilePath != null
                && activeFilePath.equals(node.file.getAbsolutePath());

        if (isActive) {
            holder.root.setBackgroundResource(R.drawable.bg_pill_selected);
        } else {
            holder.root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }

        if (node.isDirectory) {
            holder.ivChevron.setVisibility(View.VISIBLE);
            holder.ivChevron.setImageResource(
                    node.isExpanded ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
            holder.ivFolder.setVisibility(View.VISIBLE);
            holder.ivFolder.setImageResource(
                    node.isExpanded ? R.drawable.ic_folder_open : R.drawable.ic_folder);
            holder.ivFolder.setContentDescription("Folder " + node.name);
            holder.tvBadge.setVisibility(View.GONE);
        } else {
            holder.ivChevron.setVisibility(View.INVISIBLE);
            holder.ivFolder.setVisibility(View.GONE);
            holder.tvBadge.setVisibility(View.VISIBLE);
            bindFileBadge(holder.tvBadge, ctx, node.name);
        }

        holder.tvName.setText(node.name);
        holder.tvName.setTypeface(holder.tvName.getTypeface(),
                isActive ? Typeface.BOLD : Typeface.NORMAL);
        holder.tvName.setTextColor(ContextCompat.getColor(ctx,
                isActive ? R.color.sandrix_cyan : R.color.sandrix_text_primary));

        boolean canDelete = !node.isDirectory
                && !"build.sd".equals(node.name)
                && !"AndroidManifest.xml".equals(node.name);
        holder.btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
        holder.btnDelete.setContentDescription("Delete " + node.name);

        holder.root.setOnClickListener(v -> listener.onNodeClicked(node));
        holder.btnDelete.setOnClickListener(v -> listener.onDeleteClicked(node.file));
    }

    private static void bindFileBadge(TextView badge, Context ctx, String name) {
        String label;
        int colorRes;
        if (name.endsWith(".java")) {
            label = "J";
            colorRes = R.color.sandrix_cyan;
        } else if (name.endsWith(".xml")) {
            label = "X";
            colorRes = R.color.sandrix_emerald;
        } else if (name.endsWith(".sd")) {
            label = "SD";
            colorRes = R.color.sandrix_amber;
        } else {
            label = "F";
            colorRes = R.color.sandrix_violet;
        }
        badge.setText(label);
        badge.setTextColor(ContextCompat.getColor(ctx, colorRes));
    }

    @Override
    public int getItemCount() {
        return nodes.size();
    }

    static final class NodeViewHolder extends RecyclerView.ViewHolder {
        final View root;
        final ImageView ivChevron;
        final ImageView ivFolder;
        final TextView tvBadge;
        final TextView tvName;
        final ImageButton btnDelete;

        NodeViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.node_root);
            ivChevron = itemView.findViewById(R.id.iv_expand_chevron);
            ivFolder = itemView.findViewById(R.id.iv_folder_icon);
            tvBadge = itemView.findViewById(R.id.tv_file_badge);
            tvName = itemView.findViewById(R.id.tv_node_name);
            btnDelete = itemView.findViewById(R.id.btn_delete_node);
        }
    }
}
