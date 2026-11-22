package com.example.sandrix.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.sandrix.engine.BuildEngineTask;

import java.io.File;
import java.util.Collections;
import java.util.List;

public final class ProblemsAdapter extends RecyclerView.Adapter<ProblemsAdapter.ProblemViewHolder> {

    public interface Listener {
        void onDiagnosticClicked(BuildEngineTask.BuildDiagnostic diagnostic);
    }

    private final Listener listener;
    private List<BuildEngineTask.BuildDiagnostic> items = Collections.emptyList();

    public ProblemsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<BuildEngineTask.BuildDiagnostic> diagnostics) {
        List<BuildEngineTask.BuildDiagnostic> next = diagnostics != null ? diagnostics : Collections.emptyList();
        if (ListDiff.sameItems(this.items, next)) return;
        this.items = next;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ProblemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_problem_diagnostic, parent, false);
        return new ProblemViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProblemViewHolder holder, int position) {
        BuildEngineTask.BuildDiagnostic diag = items.get(position);
        Context ctx = holder.itemView.getContext();
        boolean isErr = diag.severity == BuildEngineTask.BuildDiagnostic.Severity.ERROR;
        int accent = ContextCompat.getColor(ctx, isErr ? R.color.sandrix_coral : R.color.sandrix_amber);

        holder.ivSeverity.setImageResource(isErr ? R.drawable.ic_error : R.drawable.ic_warning);
        holder.ivSeverity.setColorFilter(accent);
        holder.tvLocation.setText(new File(diag.filePath).getName()
                + ":" + diag.lineNumber + "  •  " + diag.sourceTool);
        holder.tvLocation.setTextColor(accent);
        holder.tvMessage.setText(diag.message);

        holder.root.setOnClickListener(v -> listener.onDiagnosticClicked(diag));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ProblemViewHolder extends RecyclerView.ViewHolder {
        final View root;
        final ImageView ivSeverity;
        final TextView tvLocation;
        final TextView tvMessage;

        ProblemViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.problem_root);
            ivSeverity = itemView.findViewById(R.id.iv_problem_severity);
            tvLocation = itemView.findViewById(R.id.tv_problem_location);
            tvMessage = itemView.findViewById(R.id.tv_problem_message);
        }
    }
}
