package com.example.sandrix.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.example.R;
import com.example.sandrix.git.SandrixGitManager;
import com.example.sandrix.ide.IdeUiState;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure Java Material 3 dialogs for built-in Git Source Control, Branching, Remotes, Diffs, and Cloning.
 */
public final class GitDialogs {

    public interface GitActionsListener {
        void onInit(String authorName, String authorEmail);
        void onCommitAll(String message, String authorName, String authorEmail);
        void onCommitStaged(String message, String authorName, String authorEmail);
        void onStageFile(String relativePath);
        void onUnstageFile(String relativePath);
        void onDiscardFile(String relativePath);
        void onResolveConflict(String relativePath, boolean keepOurs);
        void onCreateBranch(String branchName, boolean checkoutAfter);
        void onCheckoutBranch(String branchName);
        void onMergeBranch(String sourceBranch);
        void onStashSave(String message);
        void onStashApplyOrPop(int index, boolean dropAfter);
        void onCreateTag(String tagName, String message);
        void onSetRemote(String remoteUrl);
        void onPull(String username, String token, boolean rememberToken);
        void onPush(String username, String token, boolean rememberToken);
        void onClone(String remoteUrl, String folderName, String username, String token, boolean rememberToken);
        void onRequestDiff(String relativePath);
        void onRequestCommitDiff(String shortHash);
        void onClearDiff();
        String getSavedToken();
    }

    private GitDialogs() {}

    public static GitPanelController showGitPanel(
            Context context,
            IdeUiState initialState,
            GitActionsListener listener) {
        GitPanelController controller = new GitPanelController(context, listener);
        controller.show(initialState);
        return controller;
    }

    public static void showCloneDialog(
            Context context,
            String savedUsername,
            String savedToken,
            GitActionsListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_git_clone, null);
        TextInputEditText etUrl = view.findViewById(R.id.et_clone_url);
        TextInputEditText etFolder = view.findViewById(R.id.et_clone_folder);
        TextInputEditText etUser = view.findViewById(R.id.et_clone_user);
        TextInputEditText etToken = view.findViewById(R.id.et_clone_token);
        MaterialCheckBox cbRemember = view.findViewById(R.id.cb_clone_remember);

        if (savedUsername != null) etUser.setText(savedUsername);
        if (savedToken != null) etToken.setText(savedToken);

        final boolean[] userEditedFolder = {false};
        etFolder.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (etFolder.hasFocus()) {
                    userEditedFolder[0] = s != null && s.length() > 0;
                }
            }
        });
        etUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!userEditedFolder[0] && s != null && s.length() > 8) {
                    etFolder.setText(SandrixGitManager.suggestFolderNameFromUrl(s.toString()));
                }
            }
        });

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_git_branch)
                .setTitle("Clone Git Repository")
                .setView(view)
                .setPositiveButton("Clone", (d, w) -> {
                    String url = textOf(etUrl);
                    if (url.isEmpty()) {
                        Toast.makeText(context, "Enter an HTTPS Git URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    listener.onClone(
                            url,
                            textOf(etFolder),
                            textOf(etUser),
                            textOf(etToken),
                            cbRemember.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showDiffDialog(
            Context context,
            String relativePath,
            String diffText,
            Runnable onDismiss) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_git_diff, null);
        TextView tvDiff = view.findViewById(R.id.tv_git_diff_body);
        String body = (diffText == null || diffText.trim().isEmpty())
                ? "No textual differences found."
                : diffText;
        tvDiff.setText(highlightDiff(context, body));

        new MaterialAlertDialogBuilder(context)
                .setTitle("Diff • " + relativePath)
                .setView(view)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .show();
    }

    private static CharSequence highlightDiff(Context context, String diff) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(diff);
        int emerald = ContextCompat.getColor(context, R.color.sandrix_emerald);
        int coral = ContextCompat.getColor(context, R.color.sandrix_coral);
        int cyan = ContextCompat.getColor(context, R.color.sandrix_cyan);
        int muted = ContextCompat.getColor(context, R.color.sandrix_text_secondary);
        int addBg = Color.argb(38, 52, 211, 153);
        int delBg = Color.argb(38, 248, 113, 113);

        int idx = 0;
        String[] lines = diff.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = idx;
            int end = idx + line.length();
            if (end > ssb.length()) end = ssb.length();
            if (start < end) {
                if (line.startsWith("+++") || line.startsWith("---")) {
                    ssb.setSpan(new ForegroundColorSpan(muted), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (line.startsWith("+")) {
                    ssb.setSpan(new ForegroundColorSpan(emerald), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new BackgroundColorSpan(addBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (line.startsWith("-")) {
                    ssb.setSpan(new ForegroundColorSpan(coral), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new BackgroundColorSpan(delBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (line.startsWith("@@") || line.startsWith("diff ")) {
                    ssb.setSpan(new ForegroundColorSpan(cyan), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            idx += line.length() + 1;
        }
        return ssb;
    }

    private static void showSetRemoteDialog(
            Context context,
            String currentUrl,
            GitActionsListener listener) {
        TextInputLayout til = new TextInputLayout(context);
        til.setHint("HTTPS Remote URL (origin)");
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        til.setPadding(pad, pad / 2, pad, 0);
        TextInputEditText et = new TextInputEditText(context);
        et.setSingleLine(true);
        et.setText(currentUrl != null && !currentUrl.isEmpty()
                ? currentUrl
                : "https://github.com/username/repo.git");
        til.addView(et);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Configure Remote (origin)")
                .setView(til)
                .setPositiveButton("Save Remote", (d, w) -> listener.onSetRemote(textOf(et)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showBranchManagerDialog(
            Context context,
            SandrixGitManager.GitRepoStatus status,
            GitActionsListener listener) {
        List<String> branches = status.branches;
        CharSequence[] items = new CharSequence[branches.size() + 2];
        for (int i = 0; i < branches.size(); i++) {
            String b = branches.get(i);
            items[i] = b.equals(status.currentBranch) ? b + "  (current)" : b;
        }
        items[branches.size()] = "+ Create New Branch...";
        items[branches.size() + 1] = "⇄ Merge Another Branch into " + status.currentBranch + "...";

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_git_branch)
                .setTitle("Branches (" + status.currentBranch + ")")
                .setItems(items, (dialog, which) -> {
                    if (which == branches.size()) {
                        showCreateBranchDialog(context, listener);
                    } else if (which == branches.size() + 1) {
                        showMergeBranchPicker(context, status, listener);
                    } else {
                        String selected = branches.get(which);
                        if (!selected.equals(status.currentBranch)) {
                            listener.onCheckoutBranch(selected);
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void showMergeBranchPicker(
            Context context,
            SandrixGitManager.GitRepoStatus status,
            GitActionsListener listener) {
        java.util.List<String> others = new java.util.ArrayList<>();
        for (String b : status.branches) {
            if (!b.equals(status.currentBranch)) {
                others.add(b);
            }
        }
        if (others.isEmpty()) {
            Toast.makeText(context, "Create another branch before merging.", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] arr = others.toArray(new CharSequence[0]);
        new MaterialAlertDialogBuilder(context)
                .setTitle("Merge into " + status.currentBranch)
                .setItems(arr, (d, which) -> listener.onMergeBranch(others.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showStashDialog(
            Context context,
            SandrixGitManager.GitRepoStatus status,
            GitActionsListener listener) {
        java.util.List<String> stashes = status.stashes;
        CharSequence[] items = new CharSequence[stashes.size() + 1];
        items[0] = "+ Stash Current Working Changes...";
        for (int i = 0; i < stashes.size(); i++) {
            items[i + 1] = stashes.get(i);
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle("Git Stash (" + stashes.size() + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        TextInputLayout til = new TextInputLayout(context);
                        til.setHint("Optional stash label");
                        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
                        til.setPadding(pad, pad / 2, pad, 0);
                        TextInputEditText et = new TextInputEditText(context);
                        et.setSingleLine(true);
                        til.addView(et);
                        new MaterialAlertDialogBuilder(context)
                                .setTitle("Stash Working Tree")
                                .setView(til)
                                .setPositiveButton("Stash", (d2, w2) -> listener.onStashSave(textOf(et)))
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        int idx = which - 1;
                        new MaterialAlertDialogBuilder(context)
                                .setTitle(stashes.get(idx))
                                .setPositiveButton("Pop (Apply & Drop)", (d2, w2) -> listener.onStashApplyOrPop(idx, true))
                                .setNeutralButton("Apply (Keep)", (d2, w2) -> listener.onStashApplyOrPop(idx, false))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void showTagsDialog(
            Context context,
            SandrixGitManager.GitRepoStatus status,
            GitActionsListener listener) {
        java.util.List<String> tags = status.tags;
        CharSequence[] items = new CharSequence[tags.size() + 1];
        items[0] = "+ Create Release Tag...";
        for (int i = 0; i < tags.size(); i++) {
            items[i + 1] = "🏷  " + tags.get(i);
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle("Git Tags (" + tags.size() + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        TextInputLayout til = new TextInputLayout(context);
                        til.setHint("Tag Name (e.g. v1.0.0)");
                        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
                        til.setPadding(pad, pad / 2, pad, 0);
                        TextInputEditText et = new TextInputEditText(context);
                        et.setSingleLine(true);
                        til.addView(et);
                        new MaterialAlertDialogBuilder(context)
                                .setTitle("Tag Current HEAD")
                                .setView(til)
                                .setPositiveButton("Create Tag", (d2, w2) -> listener.onCreateTag(textOf(et), "Release " + textOf(et)))
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void showCreateBranchDialog(Context context, GitActionsListener listener) {
        TextInputLayout til = new TextInputLayout(context);
        til.setHint("New Branch Name (e.g. feature/ui)");
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        til.setPadding(pad, pad / 2, pad, 0);
        TextInputEditText et = new TextInputEditText(context);
        et.setSingleLine(true);
        til.addView(et);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Create & Checkout Branch")
                .setView(til)
                .setPositiveButton("Create", (d, w) -> listener.onCreateBranch(textOf(et), true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showCredentialsPrompt(
            Context context,
            boolean isPush,
            String savedUsername,
            String savedToken,
            GitActionsListener listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_git_credentials, null);
        TextInputEditText etUser = view.findViewById(R.id.et_creds_user);
        TextInputEditText etToken = view.findViewById(R.id.et_creds_token);
        MaterialCheckBox cbRemember = view.findViewById(R.id.cb_creds_remember);

        if (savedUsername != null) etUser.setText(savedUsername);
        if (savedToken != null) etToken.setText(savedToken);

        new MaterialAlertDialogBuilder(context)
                .setIcon(isPush ? R.drawable.ic_upload : R.drawable.ic_download)
                .setTitle(isPush ? "Push to origin" : "Pull from origin")
                .setView(view)
                .setPositiveButton(isPush ? "Push" : "Pull", (d, w) -> {
                    String u = textOf(etUser);
                    String t = textOf(etToken);
                    boolean remember = cbRemember.isChecked();
                    if (isPush) {
                        listener.onPush(u, t, remember);
                    } else {
                        listener.onPull(u, t, remember);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String textOf(TextInputEditText et) {
        return et != null && et.getText() != null ? et.getText().toString().trim() : "";
    }

    /**
     * Controller that keeps the Git Control Panel live-synchronized with IdeUiState updates.
     */
    public static final class GitPanelController {
        private final Context context;
        private final GitActionsListener listener;
        private final SimpleDateFormat commitDateFmt =
                new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());

        private AlertDialog dialog;
        private IdeUiState latestState;
        private boolean isShowingDiffDialog = false;

        private LinearProgressIndicator progressBar;
        private TextView tvProjectTitle;
        private TextView tvStatusBadge;
        private View branchRemoteRow;
        private TextView tvBranchLabel;
        private TextView tvRemoteLabel;
        private MaterialButton btnBranches;
        private MaterialButton btnRemote;

        private View notInitGroup;
        private TextInputEditText etInitName;
        private TextInputEditText etInitEmail;
        private MaterialButton btnInitRepo;
        private MaterialButton btnCloneUninit;

        private View initGroup;
        private MaterialButton btnPull;
        private MaterialButton btnPush;
        private MaterialButton btnCloneInit;
        private MaterialButton btnStash;
        private MaterialButton btnTags;
        private TextInputEditText etCommitMsg;
        private TextInputEditText etAuthorName;
        private TextInputEditText etAuthorEmail;
        private MaterialButton btnCommitStaged;
        private MaterialButton btnCommitAll;
        private TextView tvChangesHeader;
        private TextView tvCleanTree;
        private LinearLayout changesContainer;
        private TextView tvNoCommits;
        private LinearLayout commitsContainer;

        GitPanelController(Context context, GitActionsListener listener) {
            this.context = context;
            this.listener = listener;
        }

        void show(IdeUiState state) {
            this.latestState = state;
            View view = LayoutInflater.from(context).inflate(R.layout.dialog_git_panel, null);
            bind(view);

            dialog = new MaterialAlertDialogBuilder(context)
                    .setIcon(R.drawable.ic_git_branch)
                    .setTitle("Git Source Control")
                    .setView(view)
                    .setNegativeButton("Close", null)
                    .create();

            bindState(state, true);
            dialog.show();
        }

        public boolean isShowing() {
            return dialog != null && dialog.isShowing();
        }

        public void update(IdeUiState state) {
            if (!isShowing() || state == null) return;
            boolean hadChangesBefore = latestState != null && !latestState.gitStatus.changes.isEmpty();
            boolean hasChangesNow = !state.gitStatus.changes.isEmpty();
            boolean clearCommitMsg = hadChangesBefore && !hasChangesNow && !state.isGitBusy;
            this.latestState = state;
            bindState(state, false);
            if (clearCommitMsg && etCommitMsg != null) {
                etCommitMsg.setText("");
            }

            if (state.gitDiffTitle != null && state.gitDiffContent != null && !isShowingDiffDialog) {
                isShowingDiffDialog = true;
                showDiffDialog(context, state.gitDiffTitle, state.gitDiffContent, () -> {
                    isShowingDiffDialog = false;
                    listener.onClearDiff();
                });
            }
        }

        private void bind(View v) {
            progressBar = v.findViewById(R.id.git_progress_bar);
            tvProjectTitle = v.findViewById(R.id.tv_git_project_title);
            tvStatusBadge = v.findViewById(R.id.tv_git_status_badge);
            branchRemoteRow = v.findViewById(R.id.git_branch_remote_row);
            tvBranchLabel = v.findViewById(R.id.tv_git_branch_label);
            tvRemoteLabel = v.findViewById(R.id.tv_git_remote_label);
            btnBranches = v.findViewById(R.id.btn_git_branches);
            btnRemote = v.findViewById(R.id.btn_git_remote);

            notInitGroup = v.findViewById(R.id.git_not_init_group);
            etInitName = v.findViewById(R.id.et_git_init_name);
            etInitEmail = v.findViewById(R.id.et_git_init_email);
            btnInitRepo = v.findViewById(R.id.btn_git_init_repo);
            btnCloneUninit = v.findViewById(R.id.btn_git_clone_uninit);

            initGroup = v.findViewById(R.id.git_init_group);
            btnPull = v.findViewById(R.id.btn_git_pull);
            btnPush = v.findViewById(R.id.btn_git_push);
            btnCloneInit = v.findViewById(R.id.btn_git_clone_init);
            btnStash = v.findViewById(R.id.btn_git_stash);
            btnTags = v.findViewById(R.id.btn_git_tags);
            etCommitMsg = v.findViewById(R.id.et_git_commit_message);
            etAuthorName = v.findViewById(R.id.et_git_author_name);
            etAuthorEmail = v.findViewById(R.id.et_git_author_email);
            btnCommitStaged = v.findViewById(R.id.btn_git_commit_staged);
            btnCommitAll = v.findViewById(R.id.btn_git_commit_all);
            tvChangesHeader = v.findViewById(R.id.tv_git_changes_header);
            tvCleanTree = v.findViewById(R.id.tv_git_clean_tree);
            changesContainer = v.findViewById(R.id.git_changes_container);
            tvNoCommits = v.findViewById(R.id.tv_git_no_commits);
            commitsContainer = v.findViewById(R.id.git_commits_container);

            btnInitRepo.setOnClickListener(click ->
                    listener.onInit(textOf(etInitName), textOf(etInitEmail)));

            View.OnClickListener openClone = click -> {
                if (dialog != null) dialog.dismiss();
                showCloneDialog(
                        context,
                        latestState != null ? latestState.gitUsername : "",
                        listener.getSavedToken(),
                        listener);
            };
            btnCloneUninit.setOnClickListener(openClone);
            btnCloneInit.setOnClickListener(openClone);

            btnStash.setOnClickListener(click -> {
                if (latestState != null && latestState.gitStatus.isRepo) {
                    showStashDialog(context, latestState.gitStatus, listener);
                }
            });
            btnTags.setOnClickListener(click -> {
                if (latestState != null && latestState.gitStatus.isRepo) {
                    showTagsDialog(context, latestState.gitStatus, listener);
                }
            });

            btnBranches.setOnClickListener(click -> {
                if (latestState != null && latestState.gitStatus.isRepo) {
                    showBranchManagerDialog(context, latestState.gitStatus, listener);
                }
            });

            btnRemote.setOnClickListener(click -> {
                if (latestState != null && latestState.gitStatus.isRepo) {
                    showSetRemoteDialog(context, latestState.gitStatus.remoteUrl, listener);
                }
            });

            btnPull.setOnClickListener(click -> {
                if (latestState == null) return;
                if (latestState.gitStatus.remoteUrl.isEmpty()) {
                    showSetRemoteDialog(context, "", listener);
                    return;
                }
                showCredentialsPrompt(context, false, latestState.gitUsername, listener.getSavedToken(), listener);
            });

            btnPush.setOnClickListener(click -> {
                if (latestState == null) return;
                if (latestState.gitStatus.remoteUrl.isEmpty()) {
                    showSetRemoteDialog(context, "", listener);
                    return;
                }
                showCredentialsPrompt(context, true, latestState.gitUsername, listener.getSavedToken(), listener);
            });

            btnCommitStaged.setOnClickListener(click -> {
                String msg = textOf(etCommitMsg);
                if (msg.isEmpty()) {
                    Toast.makeText(context, "Enter a commit message", Toast.LENGTH_SHORT).show();
                    return;
                }
                listener.onCommitStaged(msg, textOf(etAuthorName), textOf(etAuthorEmail));
            });

            btnCommitAll.setOnClickListener(click -> {
                String msg = textOf(etCommitMsg);
                if (msg.isEmpty()) {
                    Toast.makeText(context, "Enter a commit message", Toast.LENGTH_SHORT).show();
                    return;
                }
                listener.onCommitAll(msg, textOf(etAuthorName), textOf(etAuthorEmail));
            });
        }

        private void bindState(IdeUiState state, boolean seedFields) {
            progressBar.setVisibility(state.isGitBusy ? View.VISIBLE : View.GONE);
            String projName = state.activeProject != null ? state.activeProject.getName() : "Workspace";
            tvProjectTitle.setText(projName);

            SandrixGitManager.GitRepoStatus status = state.gitStatus;
            boolean hasProject = state.activeProject != null;

            if (seedFields) {
                etInitName.setText(state.gitAuthorName);
                etInitEmail.setText(state.gitAuthorEmail);
                etAuthorName.setText(state.gitAuthorName);
                etAuthorEmail.setText(state.gitAuthorEmail);
            }

            boolean busy = state.isGitBusy;
            int stagedCount = status.getStagedCount();
            btnInitRepo.setEnabled(!busy && hasProject);
            btnCommitAll.setEnabled(!busy && status.isRepo && !status.changes.isEmpty());
            btnCommitStaged.setEnabled(!busy && status.isRepo && stagedCount > 0);
            btnCommitStaged.setText(stagedCount > 0 ? "Commit Staged (" + stagedCount + ")" : "Commit Staged");
            btnStash.setEnabled(!busy && status.isRepo);
            btnStash.setText("Stash (" + status.stashes.size() + ")");
            btnTags.setEnabled(!busy && status.isRepo);
            btnTags.setText("Tags (" + status.tags.size() + ")");
            btnPull.setEnabled(!busy && status.isRepo);
            btnPush.setEnabled(!busy && status.isRepo);
            btnBranches.setEnabled(!busy && status.isRepo);
            btnRemote.setEnabled(!busy && status.isRepo);

            if (!status.isRepo) {
                tvStatusBadge.setText("NO REPO");
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.sandrix_amber));
                branchRemoteRow.setVisibility(View.GONE);
                notInitGroup.setVisibility(View.VISIBLE);
                initGroup.setVisibility(View.GONE);
                return;
            }

            branchRemoteRow.setVisibility(View.VISIBLE);
            notInitGroup.setVisibility(View.GONE);
            initGroup.setVisibility(View.VISIBLE);

            int changeCount = status.changes.size();
            if (changeCount == 0) {
                tvStatusBadge.setText("CLEAN");
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.sandrix_emerald));
            } else {
                tvStatusBadge.setText(changeCount + " CHANGED");
                tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.sandrix_amber));
            }

            tvBranchLabel.setText("branch: " + status.currentBranch);
            tvRemoteLabel.setText(status.remoteUrl.isEmpty()
                    ? "origin: not configured"
                    : "origin: " + status.remoteUrl);

            // Populate changed files
            tvChangesHeader.setText("Working Tree Changes (" + changeCount + ")");
            changesContainer.removeAllViews();
            if (status.changes.isEmpty()) {
                tvCleanTree.setVisibility(View.VISIBLE);
            } else {
                tvCleanTree.setVisibility(View.GONE);
                LayoutInflater inflater = LayoutInflater.from(context);
                for (SandrixGitManager.GitFileChange change : status.changes) {
                    View row = inflater.inflate(R.layout.item_git_change, changesContainer, false);
                    TextView tvBadge = row.findViewById(R.id.tv_git_change_badge);
                    TextView tvPath = row.findViewById(R.id.tv_git_change_path);
                    TextView tvStage = row.findViewById(R.id.tv_git_change_stage);
                    MaterialButton btnStageToggle = row.findViewById(R.id.btn_git_stage_toggle);
                    MaterialButton btnMore = row.findViewById(R.id.btn_git_file_more);

                    tvBadge.setText(change.type.badge);
                    tvBadge.setTextColor(colorForChange(change.type));
                    tvPath.setText(change.path);
                    if (change.type == SandrixGitManager.ChangeType.CONFLICTING) {
                        tvStage.setText("CONFLICT");
                        tvStage.setTextColor(ContextCompat.getColor(context, R.color.sandrix_coral));
                        btnStageToggle.setText("Stage");
                    } else if (change.staged) {
                        tvStage.setText("STAGED");
                        tvStage.setTextColor(ContextCompat.getColor(context, R.color.sandrix_emerald));
                        btnStageToggle.setText("Unstage");
                    } else {
                        tvStage.setText("UNSTAGED");
                        tvStage.setTextColor(ContextCompat.getColor(context, R.color.sandrix_text_secondary));
                        btnStageToggle.setText("Stage");
                    }

                    row.setOnClickListener(v -> listener.onRequestDiff(change.path));
                    btnStageToggle.setOnClickListener(v -> {
                        if (change.staged) {
                            listener.onUnstageFile(change.path);
                        } else {
                            listener.onStageFile(change.path);
                        }
                    });
                    btnMore.setOnClickListener(v -> {
                        PopupMenu pm = new PopupMenu(context, btnMore);
                        pm.getMenu().add(0, 1, 0, "View Diff");
                        pm.getMenu().add(0, 2, 1, change.staged ? "Unstage File" : "Stage File");
                        if (change.type == SandrixGitManager.ChangeType.CONFLICTING) {
                            pm.getMenu().add(0, 4, 2, "Resolve: Accept Ours (HEAD)");
                            pm.getMenu().add(0, 5, 3, "Resolve: Accept Theirs");
                        } else {
                            pm.getMenu().add(0, 3, 2, "Discard Local Changes");
                        }
                        pm.setOnMenuItemClickListener(item -> {
                            int id = item.getItemId();
                            if (id == 1) listener.onRequestDiff(change.path);
                            else if (id == 2 && change.staged) listener.onUnstageFile(change.path);
                            else if (id == 2) listener.onStageFile(change.path);
                            else if (id == 3) listener.onDiscardFile(change.path);
                            else if (id == 4) listener.onResolveConflict(change.path, true);
                            else if (id == 5) listener.onResolveConflict(change.path, false);
                            return true;
                        });
                        pm.show();
                    });
                    changesContainer.addView(row);
                }
            }

            // Populate commits (clickable to inspect commit diff)
            commitsContainer.removeAllViews();
            if (status.recentCommits.isEmpty()) {
                tvNoCommits.setVisibility(View.VISIBLE);
            } else {
                tvNoCommits.setVisibility(View.GONE);
                LayoutInflater inflater = LayoutInflater.from(context);
                for (SandrixGitManager.GitCommitInfo commit : status.recentCommits) {
                    View row = inflater.inflate(R.layout.item_git_commit, commitsContainer, false);
                    TextView tvMsg = row.findViewById(R.id.tv_git_commit_msg);
                    TextView tvMeta = row.findViewById(R.id.tv_git_commit_meta);
                    tvMsg.setText(commit.message);
                    String when = commitDateFmt.format(new Date(commit.commitTimeSeconds * 1000L));
                    tvMeta.setText(commit.shortHash + " • " + commit.authorName + " • " + when + " (tap for diff)");
                    row.setOnClickListener(v -> listener.onRequestCommitDiff(commit.shortHash));
                    commitsContainer.addView(row);
                }
            }
        }

        private int colorForChange(SandrixGitManager.ChangeType type) {
            switch (type) {
                case ADDED:
                case UNTRACKED:
                    return ContextCompat.getColor(context, R.color.sandrix_emerald);
                case REMOVED:
                case CONFLICTING:
                    return ContextCompat.getColor(context, R.color.sandrix_coral);
                case MODIFIED:
                default:
                    return ContextCompat.getColor(context, R.color.sandrix_amber);
            }
        }
    }
}
