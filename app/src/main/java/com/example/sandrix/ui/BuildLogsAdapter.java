package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.sandrix.ide.BuildLogLine;

import java.util.Collections;
import java.util.List;

public final class BuildLogsAdapter extends RecyclerView.Adapter<BuildLogsAdapter.LogViewHolder> {

    public interface OnLogClickListener {
        void onLogClicked(BuildLogLine line);
    }

    @Nullable
    private final OnLogClickListener listener;
    private List<BuildLogLine> lines = Collections.emptyList();

    public BuildLogsAdapter() {
        this(null);
    }

    public BuildLogsAdapter(@Nullable OnLogClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<BuildLogLine> newLines) {
        List<BuildLogLine> next = newLines != null ? newLines : Collections.emptyList();
        if (ListDiff.sameItems(this.lines, next)) return;
        this.lines = next;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_line, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        BuildLogLine line = lines.get(position);
        Context ctx = holder.tvLine.getContext();
        holder.tvLine.setText(line.text);

        int colorRes;
        if (line.isError) {
            colorRes = R.color.sandrix_coral;
        } else if (line.text.contains("successfully") || line.text.contains("passed")) {
            colorRes = R.color.sandrix_emerald;
        } else if (line.text.startsWith("[CONFIG]")
                || line.text.startsWith("[PATCHER]")
                || line.text.startsWith("[MANIFEST]")
                || line.text.startsWith("[D8-CACHE]")) {
            colorRes = R.color.sandrix_cyan;
        } else {
            colorRes = R.color.sandrix_text_primary;
        }
        holder.tvLine.setTextColor(ContextCompat.getColor(ctx, colorRes));

        boolean jumpable = line.hasLocation() && listener != null;
        if (jumpable) {
            // Selection intercepts touch events if enabled on clickable rows
            holder.tvLine.setTextIsSelectable(false);
            holder.tvLine.setPaintFlags(holder.tvLine.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            holder.ivJump.setVisibility(View.VISIBLE);
            holder.itemView.setClickable(true);
            holder.itemView.setOnClickListener(v -> listener.onLogClicked(line));
            holder.tvLine.setOnClickListener(v -> listener.onLogClicked(line));
        } else {
            holder.tvLine.setTextIsSelectable(true);
            holder.tvLine.setPaintFlags(holder.tvLine.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            holder.ivJump.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(null);
            holder.tvLine.setOnClickListener(null);
            holder.itemView.setClickable(false);
        }
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    static final class LogViewHolder extends RecyclerView.ViewHolder {
        final TextView tvLine;
        final ImageView ivJump;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLine = itemView.findViewById(R.id.tv_log_line);
            ivJump = itemView.findViewById(R.id.iv_log_jump);
        }
    }
}
