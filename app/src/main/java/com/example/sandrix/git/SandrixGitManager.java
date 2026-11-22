package com.example.sandrix.git;

import android.content.Context;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure Java Git engine powered by Eclipse JGit for Sandrix Studio IDE.
 * Supports init, status, add/commit, branch/checkout, diff, log, remote, pull, push, and clone.
 */
public final class SandrixGitManager {

    public enum ChangeType {
        MODIFIED("M"),
        ADDED("A"),
        UNTRACKED("?"),
        REMOVED("D"),
        CONFLICTING("!");

        public final String badge;

        ChangeType(String badge) {
            this.badge = badge;
        }
    }

    public static final class GitFileChange {
        public final String path;
        public final ChangeType type;
        public final boolean staged;

        public GitFileChange(String path, ChangeType type) {
            this(path, type, false);
        }

        public GitFileChange(String path, ChangeType type, boolean staged) {
            this.path = path;
            this.type = type;
            this.staged = staged;
        }
    }

    public static final class GitCommitInfo {
        public final String shortHash;
        public final String message;
        public final String authorName;
        public final long commitTimeSeconds;

        public GitCommitInfo(String shortHash, String message, String authorName, long commitTimeSeconds) {
            this.shortHash = shortHash;
            this.message = message;
            this.authorName = authorName;
            this.commitTimeSeconds = commitTimeSeconds;
        }
    }

    public static final class GitRepoStatus {
        public final boolean isRepo;
        public final String currentBranch;
        public final List<String> branches;
        public final List<GitFileChange> changes;
        public final List<GitCommitInfo> recentCommits;
        public final String remoteUrl;
        public final String errorMessage;
        public final List<String> tags;
        public final List<String> stashes;

        public GitRepoStatus(
                boolean isRepo,
                String currentBranch,
                List<String> branches,
                List<GitFileChange> changes,
                List<GitCommitInfo> recentCommits,
                String remoteUrl,
                String errorMessage) {
            this(isRepo, currentBranch, branches, changes, recentCommits, remoteUrl, errorMessage,
                    Collections.emptyList(), Collections.emptyList());
        }

        public GitRepoStatus(
                boolean isRepo,
                String currentBranch,
                List<String> branches,
                List<GitFileChange> changes,
                List<GitCommitInfo> recentCommits,
                String remoteUrl,
                String errorMessage,
                List<String> tags,
                List<String> stashes) {
            this.isRepo = isRepo;
            this.currentBranch = currentBranch != null ? currentBranch : "main";
            this.branches = branches != null ? Collections.unmodifiableList(new ArrayList<>(branches)) : Collections.emptyList();
            this.changes = changes != null ? Collections.unmodifiableList(new ArrayList<>(changes)) : Collections.emptyList();
            this.recentCommits = recentCommits != null ? Collections.unmodifiableList(new ArrayList<>(recentCommits)) : Collections.emptyList();
            this.remoteUrl = remoteUrl != null ? remoteUrl : "";
            this.errorMessage = errorMessage;
            this.tags = tags != null ? Collections.unmodifiableList(new ArrayList<>(tags)) : Collections.emptyList();
            this.stashes = stashes != null ? Collections.unmodifiableList(new ArrayList<>(stashes)) : Collections.emptyList();
        }

        public int getStagedCount() {
            int count = 0;
            for (GitFileChange c : changes) {
                if (c.staged) count++;
            }
            return count;
        }

        public static GitRepoStatus notARepo() {
            return new GitRepoStatus(false, "", Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), "", null);
        }

        public static GitRepoStatus error(String message) {
            return new GitRepoStatus(false, "", Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), "", message);
        }
    }

    public static final class GitOpResult {
        public final boolean success;
        public final String message;
        public final File targetDirectory;
        public final GitRepoStatus status;

        public GitOpResult(boolean success, String message, File targetDirectory, GitRepoStatus status) {
            this.success = success;
            this.message = message;
            this.targetDirectory = targetDirectory;
            this.status = status;
        }
    }

    private static volatile boolean systemReaderConfigured = false;
    private final File gitHomeDir;

    public SandrixGitManager(Context context) {
        File base = context != null ? context.getFilesDir() : new File(System.getProperty("java.io.tmpdir", "/tmp"));
        this.gitHomeDir = new File(base, "sandrix_git");
        if (!this.gitHomeDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.gitHomeDir.mkdirs();
        }
        ensureAndroidSafeSystemReader(this.gitHomeDir);
    }

    /**
     * Constructor for JVM unit tests with a custom writable home directory.
     */
    public SandrixGitManager(File gitHomeDir) {
        this.gitHomeDir = gitHomeDir != null ? gitHomeDir : new File(System.getProperty("java.io.tmpdir", "/tmp"), "sandrix_git");
        if (!this.gitHomeDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.gitHomeDir.mkdirs();
        }
        ensureAndroidSafeSystemReader(this.gitHomeDir);
    }

    private static synchronized void ensureAndroidSafeSystemReader(File homeDir) {
        if (systemReaderConfigured) return;
        SystemReader current = SystemReader.getInstance();
        if (!(current instanceof AndroidSafeSystemReader)) {
            SystemReader.setInstance(new AndroidSafeSystemReader(current, homeDir));
        }
        systemReaderConfigured = true;
    }

    private static final class AndroidSafeSystemReader extends SystemReader.Delegate {
        private final File homeDir;

        AndroidSafeSystemReader(SystemReader delegate, File homeDir) {
            super(delegate);
            this.homeDir = homeDir;
        }

        @Override
        public String getProperty(String key) {
            if ("user.home".equals(key)) {
                return homeDir.getAbsolutePath();
            }
            return super.getProperty(key);
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return new FileBasedConfig(parent, new File(homeDir, ".gitconfig"), fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return new FileBasedConfig(parent, new File(homeDir, ".gitconfig.system"), fs);
        }
    }

    public boolean isGitRepository(File projectDir) {
        if (projectDir == null || !projectDir.exists()) return false;
        File gitDir = new File(projectDir, Constants.DOT_GIT);
        return gitDir.exists() && gitDir.isDirectory();
    }

    public GitRepoStatus inspectStatus(File projectDir) {
        if (projectDir == null || !projectDir.exists()) {
            return GitRepoStatus.notARepo();
        }
        if (!isGitRepository(projectDir)) {
            return GitRepoStatus.notARepo();
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            String branch = repo.getBranch();
            if (branch == null || branch.trim().isEmpty()) {
                branch = "main";
            }

            Set<String> branchSet = new LinkedHashSet<>();
            branchSet.add(branch);
            for (Ref ref : git.branchList().call()) {
                branchSet.add(Repository.shortenRefName(ref.getName()));
            }

            Status status = git.status().call();
            List<GitFileChange> changes = new ArrayList<>();
            for (String p : sorted(status.getConflicting())) {
                changes.add(new GitFileChange(p, ChangeType.CONFLICTING, false));
            }
            for (String p : sorted(status.getAdded())) {
                if (!containsPath(changes, p)) {
                    changes.add(new GitFileChange(p, ChangeType.ADDED, true));
                }
            }
            for (String p : sorted(status.getChanged())) {
                if (!containsPath(changes, p)) {
                    changes.add(new GitFileChange(p, ChangeType.MODIFIED, true));
                }
            }
            for (String p : sorted(status.getRemoved())) {
                if (!containsPath(changes, p)) {
                    changes.add(new GitFileChange(p, ChangeType.REMOVED, true));
                }
            }
            for (String p : sorted(status.getModified())) {
                if (!containsPath(changes, p)) {
                    changes.add(new GitFileChange(p, ChangeType.MODIFIED, false));
                }
            }
            for (String p : sorted(status.getMissing())) {
                if (!containsPath(changes, p)) {
                    changes.add(new GitFileChange(p, ChangeType.REMOVED, false));
                }
            }
            for (String p : sorted(status.getUntracked())) {
                if (!containsPath(changes, p)) {
                    changes.add(new GitFileChange(p, ChangeType.UNTRACKED, false));
                }
            }

            List<GitCommitInfo> commits = new ArrayList<>();
            try {
                for (RevCommit rc : git.log().setMaxCount(25).call()) {
                    String author = rc.getAuthorIdent() != null ? rc.getAuthorIdent().getName() : "Developer";
                    commits.add(new GitCommitInfo(
                            rc.abbreviate(7).name(),
                            rc.getShortMessage(),
                            author,
                            rc.getCommitTime()));
                }
            } catch (NoHeadException ignored) {
                // Repository initialized with no commits yet
            }

            List<String> tags = new ArrayList<>();
            try {
                for (Ref ref : git.tagList().call()) {
                    tags.add(Repository.shortenRefName(ref.getName()));
                }
            } catch (Exception ignored) {
            }

            List<String> stashes = new ArrayList<>();
            try {
                int sIdx = 0;
                for (RevCommit sc : git.stashList().call()) {
                    stashes.add("stash@{" + (sIdx++) + "}: " + sc.getShortMessage());
                }
            } catch (Exception ignored) {
            }

            String remoteUrl = repo.getConfig().getString("remote", "origin", "url");
            return new GitRepoStatus(
                    true,
                    branch,
                    new ArrayList<>(branchSet),
                    changes,
                    commits,
                    remoteUrl,
                    null,
                    tags,
                    stashes);
        } catch (Exception e) {
            return GitRepoStatus.error("Git inspect failed: " + safeMessage(e));
        }
    }

    public GitOpResult initRepository(File projectDir, String authorName, String authorEmail) {
        if (projectDir == null || !projectDir.exists()) {
            return new GitOpResult(false, "No active project directory.", projectDir, GitRepoStatus.notARepo());
        }
        try {
            File gitignore = new File(projectDir, ".gitignore");
            if (!gitignore.exists()) {
                writeUtf8(gitignore, "build/\n*.apk\n.gradle/\nlocal.properties\n");
            }
            try (Git git = Git.init()
                    .setDirectory(projectDir)
                    .setInitialBranch("main")
                    .call()) {
                StoredConfig cfg = git.getRepository().getConfig();
                if (authorName != null && !authorName.trim().isEmpty()) {
                    cfg.setString("user", null, "name", authorName.trim());
                }
                if (authorEmail != null && !authorEmail.trim().isEmpty()) {
                    cfg.setString("user", null, "email", authorEmail.trim());
                }
                cfg.save();
            }
            GitRepoStatus status = inspectStatus(projectDir);
            return new GitOpResult(true, "Initialized Git repository on branch main.", projectDir, status);
        } catch (Exception e) {
            return new GitOpResult(false, "Git init failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult commitAll(File projectDir, String message, String authorName, String authorEmail) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String cleanMsg = message != null ? message.trim() : "";
        if (cleanMsg.isEmpty()) {
            return new GitOpResult(false, "Commit message cannot be empty.", projectDir, inspectStatus(projectDir));
        }
        String name = (authorName != null && !authorName.trim().isEmpty()) ? authorName.trim() : "Sandrix Developer";
        String email = (authorEmail != null && !authorEmail.trim().isEmpty()) ? authorEmail.trim() : "dev@sandrix.local";

        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();

            Status statusBefore = git.status().call();
            boolean mergingResolved = repo.getRepositoryState() == RepositoryState.MERGING_RESOLVED;
            if (!statusBefore.getConflicting().isEmpty()) {
                return new GitOpResult(false, "Resolve all merge conflicts before committing.", projectDir, inspectStatus(projectDir));
            }
            if (!mergingResolved && statusBefore.isClean()) {
                return new GitOpResult(false, "Working tree is clean — nothing to commit.", projectDir, inspectStatus(projectDir));
            }

            PersonIdent ident = new PersonIdent(name, email);
            RevCommit commit = git.commit()
                    .setMessage(cleanMsg)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call();

            GitRepoStatus after = inspectStatus(projectDir);
            return new GitOpResult(true, "Committed " + commit.abbreviate(7).name() + ": " + commit.getShortMessage(), projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Commit failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult createBranch(File projectDir, String branchName, boolean checkoutAfter) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String clean = sanitizeBranchName(branchName);
        if (clean.isEmpty()) {
            return new GitOpResult(false, "Enter a valid branch name.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) {
                // Unborn HEAD: re-point symbolic HEAD to the new branch name
                repo.updateRef(Constants.HEAD).link(Constants.R_HEADS + clean);
                GitRepoStatus after = inspectStatus(projectDir);
                return new GitOpResult(true, "Switched unborn branch to " + clean, projectDir, after);
            }
            if (checkoutAfter) {
                git.checkout()
                        .setCreateBranch(true)
                        .setName(clean)
                        .call();
            } else {
                git.branchCreate()
                        .setName(clean)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                        .call();
            }
            GitRepoStatus after = inspectStatus(projectDir);
            return new GitOpResult(true,
                    (checkoutAfter ? "Created & checked out " : "Created branch ") + clean,
                    projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Create branch failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult checkoutBranch(File projectDir, String branchName) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String clean = sanitizeBranchName(branchName);
        if (clean.isEmpty()) {
            return new GitOpResult(false, "Invalid branch name.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            git.checkout().setName(clean).call();
            GitRepoStatus after = inspectStatus(projectDir);
            return new GitOpResult(true, "Switched to branch " + clean, projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Checkout failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult setRemote(File projectDir, String remoteUrl) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String cleanUrl = remoteUrl != null ? remoteUrl.trim() : "";
        if (cleanUrl.isEmpty()) {
            return new GitOpResult(false, "Remote URL cannot be empty.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            URIish uri = new URIish(cleanUrl);
            String existing = repo.getConfig().getString("remote", "origin", "url");
            if (existing == null || existing.trim().isEmpty()) {
                git.remoteAdd().setName("origin").setUri(uri).call();
            } else {
                git.remoteSetUrl().setRemoteName("origin").setRemoteUri(uri).call();
            }
            GitRepoStatus after = inspectStatus(projectDir);
            return new GitOpResult(true, "Configured origin -> " + cleanUrl, projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Set remote failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult pull(File projectDir, String username, String token) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            String remote = repo.getConfig().getString("remote", "origin", "url");
            if (remote == null || remote.trim().isEmpty()) {
                return new GitOpResult(false, "No origin remote configured.", projectDir, inspectStatus(projectDir));
            }
            String branch = repo.getBranch();
            CredentialsProvider creds = buildCredentials(username, token);
            org.eclipse.jgit.api.PullCommand pullCmd = git.pull()
                    .setRemote("origin")
                    .setTimeout(30)
                    .setCredentialsProvider(creds);
            if (branch != null && !branch.isEmpty()) {
                pullCmd.setRemoteBranchName(branch);
            }
            PullResult res = pullCmd.call();
            GitRepoStatus after = inspectStatus(projectDir);
            if (res.isSuccessful()) {
                return new GitOpResult(true, "Pull completed from origin.", projectDir, after);
            }
            return new GitOpResult(false, "Pull finished with conflicts or merge issue.", projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Pull failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult push(File projectDir, String username, String token) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            String remote = repo.getConfig().getString("remote", "origin", "url");
            if (remote == null || remote.trim().isEmpty()) {
                return new GitOpResult(false, "No origin remote configured.", projectDir, inspectStatus(projectDir));
            }
            if (repo.resolve(Constants.HEAD) == null) {
                return new GitOpResult(false, "Make an initial commit before pushing.", projectDir, inspectStatus(projectDir));
            }
            CredentialsProvider creds = buildCredentials(username, token);
            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .setTimeout(30)
                    .setCredentialsProvider(creds)
                    .call();
            StringBuilder summary = new StringBuilder();
            boolean anyError = false;
            for (PushResult pr : results) {
                for (RemoteRefUpdate rru : pr.getRemoteUpdates()) {
                    RemoteRefUpdate.Status st = rru.getStatus();
                    if (st != RemoteRefUpdate.Status.OK && st != RemoteRefUpdate.Status.UP_TO_DATE) {
                        anyError = true;
                        summary.append(st.name());
                        if (rru.getMessage() != null) {
                            summary.append(": ").append(rru.getMessage());
                        }
                        summary.append(" ");
                    } else if (summary.length() == 0) {
                        summary.append(st == RemoteRefUpdate.Status.UP_TO_DATE ? "Already up to date." : "Pushed to origin.");
                    }
                }
            }
            if (!anyError) {
                String branch = repo.getBranch();
                if (branch != null && !branch.isEmpty()) {
                    StoredConfig cfg = repo.getConfig();
                    if (cfg.getString("branch", branch, "remote") == null) {
                        cfg.setString("branch", branch, "remote", "origin");
                        cfg.setString("branch", branch, "merge", Constants.R_HEADS + branch);
                        cfg.save();
                    }
                }
            }
            GitRepoStatus after = inspectStatus(projectDir);
            String msg = summary.length() > 0 ? summary.toString().trim() : "Push completed.";
            return new GitOpResult(!anyError, msg, projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Push failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult cloneRepository(File workspaceRoot, String folderName, String remoteUrl, String username, String token) {
        if (workspaceRoot == null) {
            return new GitOpResult(false, "Workspace root unavailable.", null, GitRepoStatus.notARepo());
        }
        String cleanUrl = remoteUrl != null ? remoteUrl.trim() : "";
        if (cleanUrl.isEmpty()) {
            return new GitOpResult(false, "Remote repository URL cannot be empty.", null, GitRepoStatus.notARepo());
        }
        String cleanName = sanitizeFolderName(folderName, cleanUrl);
        File targetDir = new File(workspaceRoot, cleanName);
        if (targetDir.exists() && targetDir.list() != null && targetDir.list().length > 0) {
            return new GitOpResult(false, "Folder '" + cleanName + "' already exists and is not empty.", targetDir, GitRepoStatus.notARepo());
        }

        CredentialsProvider creds = buildCredentials(username, token);
        try {
            try (Git ignored = Git.cloneRepository()
                    .setURI(cleanUrl)
                    .setDirectory(targetDir)
                    .setTimeout(45)
                    .setCredentialsProvider(creds)
                    .call()) {
                // Cloned successfully using remote HEAD
            }
            GitRepoStatus status = inspectStatus(targetDir);
            return new GitOpResult(true, "Cloned into " + targetDir.getName(), targetDir, status);
        } catch (Exception firstErr) {
            // If upstream bare repo has an unborn HEAD (e.g. points to master while main was pushed),
            // clean up and clone an available branch explicitly.
            String msg = safeMessage(firstErr);
            if (msg.contains("HEAD") && msg.contains("not found")) {
                try {
                    deleteRecursively(targetDir);
                    String fallbackBranch = resolveFirstRemoteBranch(cleanUrl, creds);
                    if (fallbackBranch != null) {
                        try (Git ignored = Git.cloneRepository()
                                .setURI(cleanUrl)
                                .setDirectory(targetDir)
                                .setBranch(fallbackBranch)
                                .setTimeout(45)
                                .setCredentialsProvider(creds)
                                .call()) {
                            // Cloned fallback branch
                        }
                        GitRepoStatus status = inspectStatus(targetDir);
                        return new GitOpResult(true, "Cloned " + Repository.shortenRefName(fallbackBranch) + " into " + targetDir.getName(), targetDir, status);
                    }
                } catch (Exception retryErr) {
                    return new GitOpResult(false, "Clone failed: " + safeMessage(retryErr), targetDir, GitRepoStatus.notARepo());
                }
            }
            return new GitOpResult(false, "Clone failed: " + msg, targetDir, GitRepoStatus.notARepo());
        }
    }

    private static String resolveFirstRemoteBranch(String remoteUrl, CredentialsProvider creds) throws Exception {
        java.util.Collection<Ref> refs = Git.lsRemoteRepository()
                .setHeads(true)
                .setRemote(remoteUrl)
                .setTimeout(20)
                .setCredentialsProvider(creds)
                .call();
        String preferred = null;
        for (Ref r : refs) {
            String name = r.getName();
            if ("refs/heads/main".equals(name)) return name;
            if (preferred == null && name.startsWith(Constants.R_HEADS)) {
                preferred = name;
            }
        }
        return preferred;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public String diffFile(File projectDir, String relativePath) {
        if (projectDir == null || relativePath == null || relativePath.trim().isEmpty()) {
            return "";
        }
        if (!isGitRepository(projectDir)) {
            return "";
        }
        try (Repository repo = openRepo(projectDir)) {
            ObjectId headTreeId = repo.resolve("HEAD^{tree}");
            File target = new File(projectDir, relativePath);
            if (headTreeId == null) {
                return formatNewFileDiff(relativePath, target);
            }

            // Check if file is untracked or exists only in working dir
            try (Git git = new Git(repo)) {
                Status status = git.status().addPath(relativePath).call();
                if (status.getUntracked().contains(relativePath)) {
                    return formatNewFileDiff(relativePath, target);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ObjectReader reader = repo.newObjectReader();
                 DiffFormatter df = new DiffFormatter(out)) {
                CanonicalTreeParser headTree = new CanonicalTreeParser();
                headTree.reset(reader, headTreeId);
                FileTreeIterator workingTree = new FileTreeIterator(repo);

                df.setRepository(repo);
                df.setPathFilter(PathFilter.create(relativePath));
                df.format(headTree, workingTree);
                df.flush();
            }
            String text = out.toString(StandardCharsets.UTF_8.name());
            if (text.trim().isEmpty() && target.exists()) {
                return formatNewFileDiff(relativePath, target);
            }
            return text;
        } catch (Exception e) {
            return "Unable to compute diff: " + safeMessage(e);
        }
    }

    public GitOpResult stageFile(File projectDir, String relativePath) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            File target = new File(projectDir, relativePath);
            if (!target.exists()) {
                git.add().setUpdate(true).addFilepattern(relativePath).call();
            } else {
                git.add().addFilepattern(relativePath).call();
            }
            return new GitOpResult(true, "Staged " + relativePath, projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Stage failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult unstageFile(File projectDir, String relativePath) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            if (repo.resolve(Constants.HEAD) == null) {
                git.rm().setCached(true).addFilepattern(relativePath).call();
            } else {
                git.reset().addPath(relativePath).call();
            }
            return new GitOpResult(true, "Unstaged " + relativePath, projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Unstage failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult discardFileChanges(File projectDir, String relativePath) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            Status st = git.status().addPath(relativePath).call();
            if (st.getUntracked().contains(relativePath)) {
                File f = new File(projectDir, relativePath);
                if (f.exists() && !f.delete()) {
                    return new GitOpResult(false, "Could not delete untracked file.", projectDir, inspectStatus(projectDir));
                }
            } else {
                if (repo.resolve(Constants.HEAD) != null) {
                    git.reset().addPath(relativePath).call();
                }
                git.checkout().addPath(relativePath).call();
            }
            return new GitOpResult(true, "Discarded local changes in " + relativePath, projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Discard failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult commitStaged(File projectDir, String message, String authorName, String authorEmail) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String cleanMsg = message != null ? message.trim() : "";
        if (cleanMsg.isEmpty()) {
            return new GitOpResult(false, "Commit message cannot be empty.", projectDir, inspectStatus(projectDir));
        }
        String name = (authorName != null && !authorName.trim().isEmpty()) ? authorName.trim() : "Sandrix Developer";
        String email = (authorEmail != null && !authorEmail.trim().isEmpty()) ? authorEmail.trim() : "dev@sandrix.local";
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            Status st = git.status().call();
            boolean mergingResolved = repo.getRepositoryState() == RepositoryState.MERGING_RESOLVED;
            if (!st.getConflicting().isEmpty()) {
                return new GitOpResult(false, "Resolve all merge conflicts before committing.", projectDir, inspectStatus(projectDir));
            }
            if (!mergingResolved && st.getAdded().isEmpty() && st.getChanged().isEmpty() && st.getRemoved().isEmpty()) {
                return new GitOpResult(false, "No staged files. Stage file(s) or use Commit All.", projectDir, inspectStatus(projectDir));
            }
            PersonIdent ident = new PersonIdent(name, email);
            RevCommit commit = git.commit()
                    .setMessage(cleanMsg)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call();
            return new GitOpResult(true, "Committed staged (" + commit.abbreviate(7).name() + "): "
                    + commit.getShortMessage(), projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Commit staged failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult stashSave(File projectDir, String message) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            if (repo.resolve(Constants.HEAD) == null) {
                return new GitOpResult(false, "Make an initial commit before stashing.", projectDir, inspectStatus(projectDir));
            }
            RevCommit stashed = git.stashCreate()
                    .setIncludeUntracked(true)
                    .setWorkingDirectoryMessage(message != null && !message.trim().isEmpty()
                            ? message.trim()
                            : "Sandrix WIP stash")
                    .call();
            if (stashed == null) {
                return new GitOpResult(false, "Working tree is clean — nothing to stash.", projectDir, inspectStatus(projectDir));
            }
            return new GitOpResult(true, "Saved changes to stash", projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Stash failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult stashApplyOrPop(File projectDir, int stashIndex, boolean dropAfter) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            git.stashApply()
                    .setStashRef("stash@{" + Math.max(0, stashIndex) + "}")
                    .call();
            if (dropAfter) {
                git.stashDrop()
                        .setStashRef(Math.max(0, stashIndex))
                        .call();
            }
            return new GitOpResult(true, (dropAfter ? "Popped " : "Applied ") + "stash@{" + Math.max(0, stashIndex) + "}",
                    projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Stash apply failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult mergeBranch(File projectDir, String sourceBranch) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String clean = sanitizeBranchName(sourceBranch);
        if (clean.isEmpty()) {
            return new GitOpResult(false, "Invalid branch name.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            Ref targetRef = repo.findRef(clean);
            if (targetRef == null) {
                targetRef = repo.findRef(Constants.R_HEADS + clean);
            }
            if (targetRef == null) {
                return new GitOpResult(false, "Branch not found: " + clean, projectDir, inspectStatus(projectDir));
            }
            MergeResult res = git.merge()
                    .include(targetRef)
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .setMessage("Merge branch '" + clean + "'")
                    .call();
            GitRepoStatus after = inspectStatus(projectDir);
            if (res.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                return new GitOpResult(false, "Merge conflict in " + res.getConflicts().size()
                        + " file(s). Resolve Ours/Theirs and commit.", projectDir, after);
            }
            return new GitOpResult(res.getMergeStatus().isSuccessful(),
                    "Merge " + clean + ": " + res.getMergeStatus(), projectDir, after);
        } catch (Exception e) {
            return new GitOpResult(false, "Merge failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult resolveConflict(File projectDir, String relativePath, boolean keepOurs) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            git.checkout()
                    .setStage(keepOurs ? CheckoutCommand.Stage.OURS : CheckoutCommand.Stage.THEIRS)
                    .addPath(relativePath)
                    .call();
            git.add().addFilepattern(relativePath).call();
            return new GitOpResult(true,
                    "Resolved conflict in " + relativePath + " (" + (keepOurs ? "Ours" : "Theirs") + ")",
                    projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Conflict resolve failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public GitOpResult createTag(File projectDir, String tagName, String message) {
        if (projectDir == null || !isGitRepository(projectDir)) {
            return new GitOpResult(false, "Project is not a Git repository.", projectDir, inspectStatus(projectDir));
        }
        String clean = sanitizeBranchName(tagName);
        if (clean.isEmpty()) {
            return new GitOpResult(false, "Enter a valid tag name (e.g. v1.0.0).", projectDir, inspectStatus(projectDir));
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            if (repo.resolve(Constants.HEAD) == null) {
                return new GitOpResult(false, "Make a commit before tagging.", projectDir, inspectStatus(projectDir));
            }
            git.tag()
                    .setName(clean)
                    .setMessage(message != null ? message.trim() : "Release " + clean)
                    .call();
            return new GitOpResult(true, "Created tag " + clean, projectDir, inspectStatus(projectDir));
        } catch (Exception e) {
            return new GitOpResult(false, "Create tag failed: " + safeMessage(e), projectDir, inspectStatus(projectDir));
        }
    }

    public String inspectCommitDiff(File projectDir, String shortHash) {
        if (projectDir == null || !isGitRepository(projectDir) || shortHash == null || shortHash.trim().isEmpty()) {
            return "";
        }
        try (Repository repo = openRepo(projectDir);
             Git git = new Git(repo)) {
            for (RevCommit rc : git.log().setMaxCount(50).call()) {
                if (rc.abbreviate(7).name().equals(shortHash) || rc.getName().startsWith(shortHash)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (ObjectReader reader = repo.newObjectReader();
                         DiffFormatter df = new DiffFormatter(out)) {
                        df.setRepository(repo);
                        CanonicalTreeParser newTree = new CanonicalTreeParser();
                        newTree.reset(reader, rc.getTree());
                        if (rc.getParentCount() > 0) {
                            RevCommit parent = rc.getParent(0);
                            repo.parseCommit(parent);
                            CanonicalTreeParser oldTree = new CanonicalTreeParser();
                            oldTree.reset(reader, parent.getTree());
                            df.format(oldTree, newTree);
                        } else {
                            df.format(new EmptyTreeIterator(), newTree);
                        }
                        df.flush();
                    }
                    String header = "Commit " + rc.abbreviate(7).name() + "\n"
                            + "Author: " + (rc.getAuthorIdent() != null ? rc.getAuthorIdent().getName() : "Developer") + "\n"
                            + "Message: " + rc.getFullMessage().trim() + "\n\n";
                    return header + out.toString(StandardCharsets.UTF_8.name());
                }
            }
            return "Commit not found: " + shortHash;
        } catch (Exception e) {
            return "Unable to load commit diff: " + safeMessage(e);
        }
    }

    private Repository openRepo(File projectDir) throws IOException {
        return new FileRepositoryBuilder()
                .setGitDir(new File(projectDir, Constants.DOT_GIT))
                .setWorkTree(projectDir)
                .readEnvironment()
                .findGitDir(projectDir)
                .build();
    }

    private static CredentialsProvider buildCredentials(String username, String token) {
        String cleanUser = username != null ? username.trim() : "";
        String cleanToken = token != null ? token.trim() : "";
        if (cleanUser.isEmpty() && cleanToken.isEmpty()) {
            return null;
        }
        if (cleanUser.isEmpty()) {
            // GitHub/GitLab HTTPS PATs work when a non-empty placeholder username is supplied
            cleanUser = "x-access-token";
        }
        return new UsernamePasswordCredentialsProvider(cleanUser, cleanToken);
    }

    private static String formatNewFileDiff(String relativePath, File file) {
        if (!file.exists() || !file.isFile()) {
            return "--- a/" + relativePath + "\n+++ /dev/null\n(deleted file)\n";
        }
        String content = readUtf8(file);
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(relativePath).append("\n");
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    private static String sanitizeBranchName(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9._/-]", "");
    }

    public static String suggestFolderNameFromUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) return "ClonedApp";
        String trimmed = remoteUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        String tail = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (tail.endsWith(".git")) {
            tail = tail.substring(0, tail.length() - 4);
        }
        String cleaned = tail.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "ClonedApp" : cleaned;
    }

    private static String sanitizeFolderName(String requested, String remoteUrl) {
        String clean = requested != null ? requested.trim().replaceAll("[^A-Za-z0-9_-]", "") : "";
        if (!clean.isEmpty()) return clean;
        return suggestFolderNameFromUrl(remoteUrl);
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }

    private static boolean containsPath(List<GitFileChange> changes, String path) {
        for (GitFileChange c : changes) {
            if (c.path.equals(path)) return true;
        }
        return false;
    }

    private static String readUtf8(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeUtf8(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg != null && !msg.trim().isEmpty()) ? msg : t.getClass().getSimpleName();
    }
}
