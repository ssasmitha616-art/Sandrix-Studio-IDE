package com.example.sandrix.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.sandrix.logcat.LogcatStreamReader;

import java.util.Collections;
import java.util.List;

public final class LogcatAdapter extends RecyclerView.Adapter<LogcatAdapter.EntryViewHolder> {

    private List<LogcatStreamReader.LogEntry> entries = Collections.emptyList();

    public void submit(List<LogcatStreamReader.LogEntry> newEntries) {
        List<LogcatStreamReader.LogEntry> next = newEntries != null ? newEntries : Collections.emptyList();
        if (ListDiff.sameItems(this.entries, next)) return;
        this.entries = next;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EntryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_line, parent, false);
        return new EntryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryViewHolder holder, int position) {
        LogcatStreamReader.LogEntry entry = entries.get(position);
        Context ctx = holder.tvLine.getContext();
        holder.tvLine.setText(entry.timestamp + " " + entry.level + "/" + entry.tag + ": " + entry.message);

        int colorRes;
        if ("E".equals(entry.level) || "F".equals(entry.level)) {
            colorRes = R.color.sandrix_coral;
        } else if ("W".equals(entry.level)) {
            colorRes = R.color.sandrix_amber;
        } else if ("I".equals(entry.level)) {
            colorRes = R.color.sandrix_cyan;
        } else {
            colorRes = R.color.sandrix_text_secondary;
        }
        holder.tvLine.setTextColor(ContextCompat.getColor(ctx, colorRes));
        holder.tvLine.setTextSize(11f);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class EntryViewHolder extends RecyclerView.ViewHolder {
        final TextView tvLine;

        EntryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLine = itemView.findViewById(R.id.tv_log_line);
        }
    }
}
