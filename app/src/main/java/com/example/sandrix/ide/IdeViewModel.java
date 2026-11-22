package com.example.sandrix.ide;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.debug.DebugModels;
import com.example.sandrix.debug.DebugSessionController;
import com.example.sandrix.editor.CodeEditorView;
import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.engine.DexCacheManager;
import com.example.sandrix.engine.SandrixToolchain;
import com.example.sandrix.git.SandrixGitManager;
import com.example.sandrix.intel.JavaProjectIndexer;
import com.example.sandrix.logcat.LogcatStreamReader;
import com.example.sandrix.workspace.WorkspaceManager;
import com.example.sandrix.xml.XmlPatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Java ViewModel managing Sandrix IDE workspace, tabs, build pipeline, Git, and logcat.
 */
public final class IdeViewModel extends AndroidViewModel {

    public interface ReferencesCallback {
        void onReferencesFound(String symbol, List<JavaProjectIndexer.ReferenceLocation> references);
    }

    private static final String PREF_GIT = "sandrix_git_prefs";
    private static final String KEY_GIT_NAME = "author_name";
    private static final String KEY_GIT_EMAIL = "author_email";
    private static final String KEY_GIT_USER = "git_username";
    private static final String KEY_GIT_TOKEN = "git_token";

    private interface StateUpdater {
        IdeUiState update(IdeUiState current);
    }

    private final WorkspaceManager workspace;
    private final BuildEngineTask buildEngine;
    private final SandrixGitManager gitManager;
    private final SharedPreferences gitPrefs;
    private final LogcatStreamReader logcatReader;
    private final DebugSessionController debugController = new DebugSessionController();
    private final Set<String> expandedFolders = new HashSet<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService intelExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<IdeUiState> uiStateLiveData;
    private IdeUiState currentState;
    private volatile JavaProjectIndexer.ProjectIndex projectIndex;
    private volatile String lastOpenedDebugLocationKey = "";
    private final List<BuildEngineTask.BuildDiagnostic> lastBuildDiagnostics =
            Collections.synchronizedList(new ArrayList<>());
    private final Runnable liveAnalysisRunnable = this::runLiveAnalysisAsync;

    public IdeViewModel(@NonNull Application application) {
        super(application);
        this.workspace = new WorkspaceManager(application);
        this.buildEngine = new BuildEngineTask(application);
        this.gitManager = new SandrixGitManager(application);
        this.gitPrefs = application.getSharedPreferences(PREF_GIT, Context.MODE_PRIVATE);
        this.logcatReader = new LogcatStreamReader();
        this.currentState = IdeUiState.builder()
                .gitAuthorName(gitPrefs.getString(KEY_GIT_NAME, "Sandrix Developer"))
                .gitAuthorEmail(gitPrefs.getString(KEY_GIT_EMAIL, "dev@sandrix.local"))
                .gitUsername(gitPrefs.getString(KEY_GIT_USER, ""))
                .build();
        this.uiStateLiveData = new MutableLiveData<>(currentState);

        debugController.setStateListener(this::onDebugSessionStateChanged);
        refreshWorkspace(true);
        startLogcatStream();
    }

    public LiveData<IdeUiState> getUiState() {
        return uiStateLiveData;
    }

    public IdeUiState getCurrentState() {
        return currentState;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mainHandler.removeCallbacks(liveAnalysisRunnable);
        debugController.shutdown();
        buildEngine.shutdown();
        logcatReader.shutdown();
        ioExecutor.shutdownNow();
        intelExecutor.shutdownNow();
    }

    private synchronized void updateState(StateUpdater updater) {
        IdeUiState next = updater.update(currentState);
        if (next != null) {
            currentState = next;
            if (Looper.myLooper() == Looper.getMainLooper()) {
                uiStateLiveData.setValue(next);
            } else {
                uiStateLiveData.postValue(next);
            }
        }
    }

    public void refreshWorkspace() {
        refreshWorkspace(false);
    }

    public void refreshWorkspace(boolean initialLoad) {
        ioExecutor.execute(() -> {
            boolean hasPerm = workspace.hasExternalWorkspacePermission();
            File root = workspace.getWorkspaceRoot();
            List<File> projects = workspace.ensureSampleProjects();

            IdeUiState snap = currentState;
            File currentProject = snap.activeProject;
            if (currentProject == null || !currentProject.exists() || !root.equals(currentProject.getParentFile())) {
                currentProject = projects.isEmpty() ? null : projects.get(0);
            }
            boolean changedProject = currentProject != null && !currentProject.equals(snap.activeProject);

            updateState(s -> s.toBuilder()
                    .hasExternalStorageAccess(hasPerm)
                    .workspacePath(root.getAbsolutePath())
                    .projects(projects)
                    .build());

            if (currentProject != null) {
                selectProject(currentProject, initialLoad || changedProject);
            } else {
                inspectToolchain();
            }
        });
    }

    public void selectProject(File projectDir) {
        selectProject(projectDir, true);
    }

    public void selectProject(File projectDir, boolean forceReloadTabs) {
        if (projectDir == null) return;
        ioExecutor.execute(() -> {
            expandedFolders.clear();
            expandedFolders.add(projectDir.getAbsolutePath());
            File srcDir = new File(projectDir, "src");
            if (srcDir.exists()) expandedFolders.add(srcDir.getAbsolutePath());
            File resDir = workspace.resolveResDir(projectDir);
            if (resDir != null && resDir.exists()) expandedFolders.add(resDir.getAbsolutePath());
            File layoutDir = resDir != null ? new File(resDir, "layout") : null;
            if (layoutDir != null && layoutDir.exists()) expandedFolders.add(layoutDir.getAbsolutePath());

            List<FileNode> tree = buildTree(projectDir, 0);
            BuildSdParser.BuildSdConfig config = parseBuildSdSafe(projectDir);
            Map<String, String> strings = workspace.parseStringResources(projectDir);
            Map<String, String> colors = workspace.parseColorResources(projectDir);
            Map<String, String> dimens = workspace.parseDimenResources(projectDir);
            List<File> drawables = workspace.listDrawableFiles(projectDir);
            List<File> assets = workspace.listAssetFiles(projectDir);
            List<String> xmlIds = workspace.collectXmlResourceIds(projectDir);
            File defaultLayout = workspace.findPrimaryLayoutXml(projectDir);
            String defaultLayoutXml = defaultLayout != null ? workspace.readFile(defaultLayout) : "";

            List<EditorTab> tabs;
            if (forceReloadTabs) {
                tabs = new ArrayList<>();
                for (File f : workspace.findDefaultFilesToOpen(projectDir)) {
                    tabs.add(new EditorTab(f, workspace.readFile(f), false, null, Collections.emptySet()));
                }
            } else {
                tabs = currentState.openTabs;
            }
            int nextTabIdx = tabs.isEmpty() ? -1 : 0;
            EditorTab firstTab = nextTabIdx >= 0 ? tabs.get(nextTabIdx) : null;

            SandrixGitManager.GitRepoStatus gitStatus = gitManager.inspectStatus(projectDir);
            lastBuildDiagnostics.clear();
            projectIndex = JavaProjectIndexer.buildIndex(projectDir, collectOpenContentMap(tabs));

            updateState(s -> s.toBuilder()
                    .activeProject(projectDir)
                    .fileTree(tree)
                    .openTabs(tabs)
                    .activeTabIndex(nextTabIdx)
                    .parsedConfig(config)
                    .stringResources(strings)
                    .colorResources(colors)
                    .dimenResources(dimens)
                    .drawableFiles(drawables)
                    .assetFiles(assets)
                    .xmlResourceIds(xmlIds)
                    .previewXmlContent(resolvePreviewContent(firstTab, defaultLayoutXml))
                    .previewLayoutName(resolvePreviewName(firstTab, defaultLayout != null ? defaultLayout.getName() : "activity_main.xml"))
                    .lastBuiltApk(null)
                    .lastBuiltAab(null)
                    .lastMappingFile(null)
                    .diagnostics(Collections.emptyList())
                    .gitStatus(gitStatus)
                    .build());

            inspectToolchain();
            scheduleLiveAnalysis(80);
            Log.i("SandrixIDE", "Opened project: " + projectDir.getName());
        });
    }

    public void toggleFolder(File folder) {
        if (folder == null) return;
        String path = folder.getAbsolutePath();
        if (expandedFolders.contains(path)) {
            expandedFolders.remove(path);
        } else {
            expandedFolders.add(path);
        }
        File project = currentState.activeProject;
        if (project == null) return;
        ioExecutor.execute(() -> {
            List<FileNode> tree = buildTree(project, 0);
            updateState(s -> s.toBuilder().fileTree(tree).build());
        });
    }

    public void openFile(File file) {
        openFile(file, null);
    }

    public void openFile(File file, Integer jumpToLine) {
        if (file == null) return;
        if (file.isDirectory()) {
            toggleFolder(file);
            return;
        }
        List<EditorTab> currentTabs = currentState.openTabs;
        int existingIdx = -1;
        for (int i = 0; i < currentTabs.size(); i++) {
            if (currentTabs.get(i).file.getAbsolutePath().equals(file.getAbsolutePath())) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0) {
            final int targetIdx = existingIdx;
            updateState(s -> {
                List<EditorTab> updated = new ArrayList<>(s.openTabs);
                if (targetIdx < updated.size()) {
                    EditorTab tab = updated.get(targetIdx);
                    if (jumpToLine != null) {
                        tab = tab.withPendingJumpLine(jumpToLine);
                        updated.set(targetIdx, tab);
                    }
                    return s.toBuilder()
                            .openTabs(updated)
                            .activeTabIndex(targetIdx)
                            .previewXmlContent(isLayoutXml(tab.file) ? tab.content : s.previewXmlContent)
                            .previewLayoutName(isLayoutXml(tab.file) ? tab.file.getName() : s.previewLayoutName)
                            .build();
                }
                return s;
            });
            return;
        }

        ioExecutor.execute(() -> {
            String content = workspace.readFile(file);
            updateState(s -> {
                EditorTab newTab = new EditorTab(file, content, false, jumpToLine, Collections.emptySet());
                List<EditorTab> updated = new ArrayList<>(s.openTabs);
                updated.add(newTab);
                int newIdx = updated.size() - 1;
                return s.toBuilder()
                        .openTabs(updated)
                        .activeTabIndex(newIdx)
                        .previewXmlContent(isLayoutXml(file) ? content : s.previewXmlContent)
                        .previewLayoutName(isLayoutXml(file) ? file.getName() : s.previewLayoutName)
                        .build();
            });
            scheduleLiveAnalysis(60);
        });
    }

    public void consumePendingJumpLine(int tabIndex) {
        updateState(s -> {
            if (tabIndex < 0 || tabIndex >= s.openTabs.size()) return s;
            EditorTab tab = s.openTabs.get(tabIndex);
            if (tab.pendingJumpLine == null) return s;
            List<EditorTab> list = new ArrayList<>(s.openTabs);
            list.set(tabIndex, tab.withPendingJumpLine(null));
            return s.toBuilder().openTabs(list).build();
        });
    }

    public void selectTab(int index) {
        updateState(s -> {
            if (index < 0 || index >= s.openTabs.size()) return s;
            EditorTab tab = s.openTabs.get(index);
            return s.toBuilder()
                    .activeTabIndex(index)
                    .previewXmlContent(isLayoutXml(tab.file) ? tab.content : s.previewXmlContent)
                    .previewLayoutName(isLayoutXml(tab.file) ? tab.file.getName() : s.previewLayoutName)
                    .build();
        });
        scheduleLiveAnalysis(60);
    }

    public void closeTab(int index) {
        updateState(s -> {
            if (index < 0 || index >= s.openTabs.size()) return s;
            List<EditorTab> updated = new ArrayList<>(s.openTabs);
            updated.remove(index);
            int nextIdx;
            if (updated.isEmpty()) {
                nextIdx = -1;
            } else if (index <= s.activeTabIndex) {
                nextIdx = Math.max(0, s.activeTabIndex - 1);
            } else {
                nextIdx = s.activeTabIndex;
            }
            return s.toBuilder().openTabs(updated).activeTabIndex(nextIdx).build();
        });
        scheduleLiveAnalysis(60);
    }

    public void updateActiveCode(String newCode) {
        updateState(s -> {
            int idx = s.activeTabIndex;
            if (idx < 0 || idx >= s.openTabs.size()) return s;
            EditorTab tab = s.openTabs.get(idx);
            if (tab.content.equals(newCode)) return s;
            List<EditorTab> updated = new ArrayList<>(s.openTabs);
            updated.set(idx, tab.withContent(newCode, true));
            return s.toBuilder()
                    .openTabs(updated)
                    .previewXmlContent(isLayoutXml(tab.file) ? newCode : s.previewXmlContent)
                    .previewLayoutName(isLayoutXml(tab.file) ? tab.file.getName() : s.previewLayoutName)
                    .build();
        });
        scheduleLiveAnalysis(260);
    }

    public void saveActiveFile() {
        EditorTab tab = currentState.getActiveTab();
        if (tab == null) return;
        ioExecutor.execute(() -> {
            workspace.writeFile(tab.file, tab.content);
            updateState(s -> {
                List<EditorTab> list = new ArrayList<>(s.openTabs);
                int idx = s.activeTabIndex;
                if (idx >= 0 && idx < list.size()) {
                    list.set(idx, list.get(idx).withDirty(false));
                }
                return s.toBuilder()
                        .openTabs(list)
                        .statusBannerMessage("Saved " + tab.file.getName())
                        .build();
            });
            String name = tab.file.getName();
            if ("build.sd".equals(name) || "strings.xml".equals(name) || "colors.xml".equals(name)
                    || isLayoutXml(tab.file)) {
                reloadProjectMetadataSync();
            }
            refreshGitStatusSync();
        });
    }

    public void saveAllFiles(Runnable onSaved) {
        List<EditorTab> tabs = currentState.openTabs;
        ioExecutor.execute(() -> {
            for (EditorTab tab : tabs) {
                if (tab.isDirty) {
                    workspace.writeFile(tab.file, tab.content);
                }
            }
            updateState(s -> {
                List<EditorTab> clean = new ArrayList<>(s.openTabs.size());
                for (EditorTab t : s.openTabs) {
                    clean.add(t.withDirty(false));
                }
                return s.toBuilder().openTabs(clean).build();
            });
            reloadProjectMetadataSync();
            refreshGitStatusSync();
            if (onSaved != null) {
                mainHandler.post(onSaved);
            }
        });
    }

    private void reloadProjectMetadataSync() {
        File project = currentState.activeProject;
        if (project == null) return;
        BuildSdParser.BuildSdConfig config = parseBuildSdSafe(project);
        Map<String, String> strings = workspace.parseStringResources(project);
        Map<String, String> colors = workspace.parseColorResources(project);
        Map<String, String> dimens = workspace.parseDimenResources(project);
        List<File> drawables = workspace.listDrawableFiles(project);
        List<File> assets = workspace.listAssetFiles(project);
        List<String> xmlIds = workspace.collectXmlResourceIds(project);
        projectIndex = JavaProjectIndexer.buildIndex(project, collectOpenContentMap(currentState.openTabs));
        updateState(s -> s.toBuilder()
                .parsedConfig(config)
                .stringResources(strings)
                .colorResources(colors)
                .dimenResources(dimens)
                .drawableFiles(drawables)
                .assetFiles(assets)
                .xmlResourceIds(xmlIds)
                .build());
        inspectToolchain();
        scheduleLiveAnalysis(60);
    }

    public void syncManifestAndXml() {
        File project = currentState.activeProject;
        if (project == null) return;
        saveAllFiles(() -> ioExecutor.execute(() -> {
            List<BuildLogLine> logs = new ArrayList<>();
            try {
                BuildSdParser.BuildSdConfig config = BuildSdParser.parseProjectConfig(project);
                File manifest = workspace.resolveManifestFile(project);
                File mainActivity = findMainActivity(project);
                XmlPatcher.PatchReport manifestReport = XmlPatcher.syncManifestPackage(mainActivity, manifest, config);
                logs.add(new BuildLogLine("[XmlPatcher] " + manifestReport.summary, false));
                for (String act : manifestReport.actions) {
                    logs.add(new BuildLogLine("  + " + act, false));
                }

                File layoutDir = new File(workspace.resolveResDir(project), "layout");
                File[] xmls = layoutDir.listFiles();
                if (xmls != null) {
                    for (File xml : xmls) {
                        if (xml.getName().endsWith(".xml")) {
                            XmlPatcher.PatchReport report = XmlPatcher.ensureAndroidNamespace(xml);
                            logs.add(new BuildLogLine("[XmlPatcher] " + xml.getName() + ": " + report.summary, false));
                            for (String act : report.actions) {
                                logs.add(new BuildLogLine("  + " + act, false));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logs.add(new BuildLogLine("[XmlPatcher ERROR] " + e.getMessage(), true));
            }

            List<EditorTab> refreshedTabs = new ArrayList<>();
            for (EditorTab tab : currentState.openTabs) {
                if (tab.file.exists()) {
                    refreshedTabs.add(tab.withContent(workspace.readFile(tab.file), false));
                } else {
                    refreshedTabs.add(tab);
                }
            }
            updateState(s -> {
                EditorTab active = (s.activeTabIndex >= 0 && s.activeTabIndex < refreshedTabs.size())
                        ? refreshedTabs.get(s.activeTabIndex)
                        : null;
                List<BuildLogLine> combined = appendBounded(s.buildLogs, logs, 400);
                return s.toBuilder()
                        .openTabs(refreshedTabs)
                        .buildLogs(combined)
                        .consoleExpanded(true)
                        .activeConsoleTab(ConsoleTab.BUILD)
                        .previewXmlContent((active != null && isLayoutXml(active.file)) ? active.content : s.previewXmlContent)
                        .statusBannerMessage("Manifest & XML namespaces synchronized")
                        .build();
            });
            scheduleLiveAnalysis(60);
        }));
    }

    public void runBuild(boolean autoInstallApk) {
        runBuild(autoInstallApk, false, false);
    }

    public void runBuild(boolean autoInstallApk, boolean releaseBuild) {
        runBuild(autoInstallApk, releaseBuild, false);
    }

    public void runBuild(boolean autoInstallApk, boolean releaseBuild, boolean cleanBuild) {
        BuildSdParser.BuildSdConfig cfg = currentState.parsedConfig;
        boolean minify = cfg != null && cfg.isMinifyEnabledForVariant(releaseBuild);
        boolean shrinkRes = cfg != null && releaseBuild && cfg.releaseShrinkResources;
        runBuild(new BuildEngineTask.BuildOptions(autoInstallApk, releaseBuild, cleanBuild, false, minify, shrinkRes));
    }

    public void runR8OptimizedBuild(boolean releaseBuild, boolean autoInstallApk, boolean cleanBuild) {
        runBuild(new BuildEngineTask.BuildOptions(autoInstallApk, releaseBuild, cleanBuild, false, true, true));
    }

    public void runBuildAab(boolean releaseBuild, boolean forceR8, boolean cleanBuild) {
        runBuild(new BuildEngineTask.BuildOptions(false, releaseBuild, cleanBuild, true, forceR8, forceR8));
    }

    public void runBuild(BuildEngineTask.BuildOptions options) {
        File project = currentState.activeProject;
        if (project == null || currentState.isBuilding) return;
        BuildEngineTask.BuildOptions effective = options != null
                ? options
                : new BuildEngineTask.BuildOptions(true, false, false, false, false, false);

        saveAllFiles(() -> {
            lastBuildDiagnostics.clear();
            updateState(s -> s.toBuilder()
                    .isBuilding(true)
                    .buildStage(BuildEngineTask.Stage.CONFIG)
                    .buildLogs(Collections.singletonList(
                            new BuildLogLine("=== Sandrix Build Started: " + project.getName()
                                    + (effective.buildAab ? " [AAB]" : " [APK]")
                                    + (effective.forceR8Optimize ? " [R8]" : "")
                                    + " ===", false)))
                    .diagnostics(Collections.emptyList())
                    .consoleExpanded(true)
                    .activeConsoleTab(ConsoleTab.BUILD)
                    .lastBuildSummary(null)
                    .build());

            Log.i("SandrixBuild", "Starting Sandrix " + (effective.releaseBuild ? "RELEASE" : "DEBUG")
                    + (effective.buildAab ? " AAB" : " APK")
                    + (effective.forceR8Optimize ? " (R8)" : "")
                    + (effective.cleanBuild ? " (CLEAN)" : "")
                    + " pipeline for " + project.getName());

            buildEngine.execute(project, effective, new BuildEngineTask.Callback() {
                @Override
                public void onStageChanged(BuildEngineTask.Stage stage) {
                    updateState(s -> s.toBuilder().buildStage(stage).build());
                }

                @Override
                public void onLogLine(String line, boolean isError) {
                    onLogBatch(Collections.singletonList(new BuildLogLine(line, isError)));
                }

                @Override
                public void onLogBatch(List<BuildLogLine> lines) {
                    if (lines == null || lines.isEmpty()) return;
                    updateState(s -> s.toBuilder()
                            .buildLogs(appendBounded(s.buildLogs, lines, 500))
                            .build());
                }

                @Override
                public void onDiagnostic(BuildEngineTask.BuildDiagnostic diagnostic) {
                    lastBuildDiagnostics.add(diagnostic);
                    updateState(s -> {
                        List<EditorTab> updatedTabs = new ArrayList<>(s.openTabs.size());
                        for (EditorTab tab : s.openTabs) {
                            if (tab.file.getAbsolutePath().equals(diagnostic.filePath)) {
                                updatedTabs.add(tab.withAddedErrorLine(diagnostic.lineNumber));
                            } else {
                                updatedTabs.add(tab);
                            }
                        }
                        List<BuildEngineTask.BuildDiagnostic> diags = new ArrayList<>(s.diagnostics);
                        diags.add(diagnostic);
                        return s.toBuilder()
                                .diagnostics(diags)
                                .openTabs(updatedTabs)
                                .build();
                    });
                }

                @Override
                public void onFinished(BuildEngineTask.BuildResult result) {
                    ioExecutor.execute(() -> {
                        List<EditorTab> refreshedTabs = new ArrayList<>();
                        for (EditorTab tab : currentState.openTabs) {
                            if (tab.file.exists()) {
                                refreshedTabs.add(tab.withContent(workspace.readFile(tab.file), false));
                            } else {
                                refreshedTabs.add(tab);
                            }
                        }
                        ConsoleTab nextTab = (!result.validationPassed && !result.diagnostics.isEmpty())
                                ? ConsoleTab.PROBLEMS
                                : ConsoleTab.BUILD;
                        updateState(s -> s.toBuilder()
                                .isBuilding(false)
                                .buildStage(null)
                                .openTabs(refreshedTabs)
                                .lastBuiltApk(result.apkFile != null ? result.apkFile : s.lastBuiltApk)
                                .lastBuiltAab(result.aabFile != null ? result.aabFile : s.lastBuiltAab)
                                .lastMappingFile(result.mappingFile != null ? result.mappingFile : s.lastMappingFile)
                                .lastBuildSummary(result.summary)
                                .activeConsoleTab(nextTab)
                                .statusBannerMessage(result.summary)
                                .build());
                    });
                }
            });
        });
    }

    public void installLastApk() {
        File apk = currentState.lastBuiltApk;
        if (apk == null) return;
        boolean launched = BuildEngineTask.launchApkInstaller(getApplication(), apk);
        updateState(s -> s.toBuilder()
                .statusBannerMessage(launched
                        ? "Launched Package Installer for " + apk.getName()
                        : "Unable to launch Package Installer.")
                .build());
    }

    public boolean shareLastAab(Context activityContext) {
        File aab = currentState.lastBuiltAab;
        if (aab == null || !aab.exists() || activityContext == null) {
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("No signed .aab bundle built yet.")
                    .build());
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    activityContext,
                    activityContext.getPackageName() + ".fileprovider",
                    aab);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, aab.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activityContext.startActivity(Intent.createChooser(share, "Share / Export " + aab.getName()));
            return true;
        } catch (Exception e) {
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("AAB located at: " + aab.getAbsolutePath())
                    .build());
            return false;
        }
    }

    public void jumpToDiagnostic(BuildEngineTask.BuildDiagnostic diag) {
        if (diag == null) return;
        File target = resolveProjectFile(currentState.activeProject, diag.filePath);
        if (target != null && target.exists()) {
            if (currentState.viewMode == EditorViewMode.PREVIEW) {
                setViewMode(EditorViewMode.SPLIT);
            }
            openFile(target, diag.lineNumber);
        }
    }

    public void jumpToLogLine(BuildLogLine line) {
        if (line == null || !line.hasLocation()) return;
        File project = currentState.activeProject;
        ioExecutor.execute(() -> {
            File target = resolveProjectFile(project, line.filePath);
            if (target != null && target.exists()) {
                updateState(s -> {
                    EditorViewMode mode = s.viewMode == EditorViewMode.PREVIEW
                            ? EditorViewMode.SPLIT
                            : s.viewMode;
                    return s.toBuilder()
                            .viewMode(mode)
                            .statusBannerMessage("Jumped to " + target.getName() + ":" + line.lineNumber)
                            .build();
                });
                openFile(target, line.lineNumber);
            } else {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Source file not found in project: " + line.filePath)
                        .build());
            }
        });
    }

    public File resolveProjectFile(File projectDir, String pathOrName) {
        if (pathOrName == null || pathOrName.trim().isEmpty()) return null;
        String clean = pathOrName.trim();
        File direct = new File(clean);
        if (direct.isFile()) {
            return direct;
        }
        if (projectDir != null) {
            File relative = new File(projectDir, clean);
            if (relative.isFile()) {
                return relative;
            }
            String fileName = new File(clean).getName();
            if (!fileName.isEmpty()) {
                File matched = findProjectFileByName(projectDir, fileName);
                if (matched != null && matched.isFile()) {
                    return matched;
                }
            }
        }
        return null;
    }

    private File findProjectFileByName(File dir, String targetName) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                String dirName = f.getName();
                if (!"build".equals(dirName) && !".git".equals(dirName)) {
                    File found = findProjectFileByName(f, targetName);
                    if (found != null) return found;
                }
            } else if (targetName.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    // ==================== Visual Resource & Asset Manager Actions ====================

    public void saveStringResource(String key, String value) {
        File project = currentState.activeProject;
        if (project == null || key == null || key.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            workspace.saveStringResource(project, key, value);
            File edited = new File(workspace.resolveResDir(project), "values/strings.xml");
            syncResourcesAndTabsSync(project, edited, null,
                    "Saved @string/" + WorkspaceManager.sanitizeResourceName(key, "string_item"));
        });
    }

    public void deleteStringResource(String key) {
        File project = currentState.activeProject;
        if (project == null || key == null || key.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            workspace.deleteStringResource(project, key);
            File edited = new File(workspace.resolveResDir(project), "values/strings.xml");
            syncResourcesAndTabsSync(project, edited, null, "Removed @string/" + key);
        });
    }

    public void saveColorResource(String key, String hexColor) {
        File project = currentState.activeProject;
        if (project == null || key == null || key.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            try {
                workspace.saveColorResource(project, key, hexColor);
                File edited = new File(workspace.resolveResDir(project), "values/colors.xml");
                syncResourcesAndTabsSync(project, edited, null,
                        "Saved @color/" + WorkspaceManager.sanitizeResourceName(key, "color_item"));
            } catch (IllegalArgumentException e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage(e.getMessage())
                        .build());
            }
        });
    }

    public void deleteColorResource(String key) {
        File project = currentState.activeProject;
        if (project == null || key == null || key.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            workspace.deleteColorResource(project, key);
            File edited = new File(workspace.resolveResDir(project), "values/colors.xml");
            syncResourcesAndTabsSync(project, edited, null, "Removed @color/" + key);
        });
    }

    public void saveDimenResource(String key, String value) {
        File project = currentState.activeProject;
        if (project == null || key == null || key.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            workspace.saveDimenResource(project, key, value);
            File edited = new File(workspace.resolveResDir(project), "values/dimens.xml");
            syncResourcesAndTabsSync(project, edited, null,
                    "Saved @dimen/" + WorkspaceManager.sanitizeResourceName(key, "dimen_item"));
        });
    }

    public void deleteDimenResource(String key) {
        File project = currentState.activeProject;
        if (project == null || key == null || key.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            workspace.deleteDimenResource(project, key);
            File edited = new File(workspace.resolveResDir(project), "values/dimens.xml");
            syncResourcesAndTabsSync(project, edited, null, "Removed @dimen/" + key);
        });
    }

    public void createDrawableTemplate(String rawName, String templateType, boolean openInEditor) {
        File project = currentState.activeProject;
        if (project == null) return;
        ioExecutor.execute(() -> {
            File created = workspace.createXmlDrawable(project, rawName, templateType);
            syncResourcesAndTabsSync(project, created, null, "Created @drawable/" + created.getName());
            if (openInEditor) {
                openFile(created, null);
            }
        });
    }

    public void importDrawableImage(Uri uri, String preferredName) {
        File project = currentState.activeProject;
        if (project == null || uri == null) return;
        ioExecutor.execute(() -> {
            try {
                File imported = workspace.importDrawableImage(project, uri, preferredName);
                syncResourcesAndTabsSync(project, imported, null,
                        "Imported drawable " + imported.getName());
            } catch (Exception e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Image import failed: " + e.getMessage())
                        .build());
            }
        });
    }

    public void importProjectAsset(Uri uri, String preferredName) {
        File project = currentState.activeProject;
        if (project == null || uri == null) return;
        ioExecutor.execute(() -> {
            try {
                File imported = workspace.importProjectAsset(project, uri, preferredName);
                syncResourcesAndTabsSync(project, imported, null,
                        "Imported asset " + imported.getName());
            } catch (Exception e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Asset import failed: " + e.getMessage())
                        .build());
            }
        });
    }

    public void deleteResourceFile(File target) {
        File project = currentState.activeProject;
        if (project == null || target == null) return;
        ioExecutor.execute(() -> {
            boolean deleted = workspace.deleteProjectResourceFile(target);
            if (deleted) {
                syncResourcesAndTabsSync(project, null, target, "Deleted " + target.getName());
            }
        });
    }

    private void syncResourcesAndTabsSync(
            File projectDir,
            File updatedOrCreatedFile,
            File deletedFile,
            String bannerMessage) {
        List<FileNode> tree = buildTree(projectDir, 0);
        Map<String, String> strings = workspace.parseStringResources(projectDir);
        Map<String, String> colors = workspace.parseColorResources(projectDir);
        Map<String, String> dimens = workspace.parseDimenResources(projectDir);
        List<File> drawables = workspace.listDrawableFiles(projectDir);
        List<File> assets = workspace.listAssetFiles(projectDir);
        List<String> xmlIds = workspace.collectXmlResourceIds(projectDir);
        SandrixGitManager.GitRepoStatus gitStatus = gitManager.inspectStatus(projectDir);

        updateState(s -> {
            List<EditorTab> nextTabs = new ArrayList<>();
            for (EditorTab tab : s.openTabs) {
                String tabPath = tab.file.getAbsolutePath();
                if (deletedFile != null && tabPath.equals(deletedFile.getAbsolutePath())) {
                    continue;
                }
                if (updatedOrCreatedFile != null
                        && tabPath.equals(updatedOrCreatedFile.getAbsolutePath())
                        && updatedOrCreatedFile.exists()) {
                    nextTabs.add(tab.withContent(workspace.readFile(updatedOrCreatedFile), false));
                } else {
                    nextTabs.add(tab);
                }
            }
            int nextIdx = nextTabs.isEmpty()
                    ? -1
                    : Math.max(0, Math.min(s.activeTabIndex, nextTabs.size() - 1));
            return s.toBuilder()
                    .fileTree(tree)
                    .openTabs(nextTabs)
                    .activeTabIndex(nextIdx)
                    .stringResources(strings)
                    .colorResources(colors)
                    .dimenResources(dimens)
                    .drawableFiles(drawables)
                    .assetFiles(assets)
                    .xmlResourceIds(xmlIds)
                    .gitStatus(gitStatus)
                    .statusBannerMessage(bannerMessage)
                    .build();
        });
        scheduleLiveAnalysis(60);
    }

    public void createNewProject(String name, String packageName, WorkspaceManager.ProjectTemplate template) {
        ioExecutor.execute(() -> {
            File dir = workspace.createProject(name, packageName, template);
            List<File> projects = workspace.listProjects();
            updateState(s -> s.toBuilder()
                    .projects(projects)
                    .statusBannerMessage("Created project " + dir.getName())
                    .build());
            selectProject(dir, true);
        });
    }

    public void createNewFile(String relativePath) {
        File project = currentState.activeProject;
        if (project == null || relativePath == null || relativePath.trim().isEmpty()) return;
        ioExecutor.execute(() -> {
            File created = workspace.createNewFile(project, relativePath);
            if (created == null) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Kotlin files are not supported. Create a .java or .xml file.")
                        .build());
                return;
            }
            List<FileNode> tree = buildTree(project, 0);
            SandrixGitManager.GitRepoStatus gitStatus = gitManager.inspectStatus(project);
            updateState(s -> s.toBuilder()
                    .fileTree(tree)
                    .gitStatus(gitStatus)
                    .statusBannerMessage("Created " + created.getName())
                    .build());
            openFile(created, null);
        });
    }

    public void deleteFile(File file) {
        File project = currentState.activeProject;
        if (project == null || file == null) return;
        ioExecutor.execute(() -> {
            deleteRecursively(file);
            List<FileNode> tree = buildTree(project, 0);
            SandrixGitManager.GitRepoStatus gitStatus = gitManager.inspectStatus(project);
            updateState(s -> {
                String delPath = file.getAbsolutePath();
                List<EditorTab> remaining = new ArrayList<>();
                for (EditorTab tab : s.openTabs) {
                    String tabPath = tab.file.getAbsolutePath();
                    if (!tabPath.equals(delPath) && !tabPath.startsWith(delPath + File.separator)) {
                        remaining.add(tab);
                    }
                }
                int nextIdx = remaining.isEmpty()
                        ? -1
                        : Math.max(0, Math.min(s.activeTabIndex, remaining.size() - 1));
                return s.toBuilder()
                        .fileTree(tree)
                        .openTabs(remaining)
                        .activeTabIndex(nextIdx)
                        .gitStatus(gitStatus)
                        .statusBannerMessage("Deleted " + file.getName())
                        .build();
            });
        });
    }

    public void importToolchainZip(Uri uri) {
        if (uri == null) return;
        ioExecutor.execute(() -> {
            try {
                int count = SandrixToolchain.inspect(getApplication()).importToolchainZip(uri);
                inspectToolchain();
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Imported " + count + " Sandrix Engine files")
                        .build());
            } catch (Exception e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Toolchain import failed: " + e.getMessage())
                        .build());
            }
        });
    }

    public void inspectToolchain() {
        int compileSdk = currentState.parsedConfig != null ? currentState.parsedConfig.compileSdk : 36;
        ioExecutor.execute(() -> {
            List<SandrixToolchain.ToolStatus> statuses =
                    SandrixToolchain.inspect(getApplication()).getStatusList(compileSdk);
            updateState(s -> s.toBuilder().toolchainStatuses(statuses).build());
        });
    }

    public void setViewMode(EditorViewMode mode) {
        updateState(s -> s.toBuilder().viewMode(mode).build());
    }

    public void toggleConsoleExpanded() {
        updateState(s -> s.toBuilder().consoleExpanded(!s.consoleExpanded).build());
    }

    public void setConsoleTab(ConsoleTab tab) {
        updateState(s -> s.toBuilder().activeConsoleTab(tab).consoleExpanded(true).build());
        if (tab == ConsoleTab.LOGCAT && !logcatReader.isRunning()) {
            startLogcatStream();
        }
    }

    public void clearBuildLogs() {
        updateState(s -> s.toBuilder().buildLogs(Collections.emptyList()).build());
    }

    public void clearLogcat() {
        com.example.sandrix.logcat.SandrixLogcatBus.clear();
        updateState(s -> s.toBuilder().logcatEntries(Collections.emptyList()).build());
    }

    public void toggleLogcatPause() {
        updateState(s -> s.toBuilder().logcatPaused(!s.logcatPaused).build());
    }

    public void setLogcatFilter(String level) {
        updateState(s -> s.toBuilder().logcatLevelFilter(level).build());
    }

    public void setLogcatSearchQuery(String query) {
        updateState(s -> s.toBuilder().logcatSearchQuery(query).build());
    }

    public void toggleBreakpoint(int lineNumber) {
        EditorTab active = currentState.getActiveTab();
        if (active == null || lineNumber < 1) return;
        boolean added = debugController.toggleBreakpoint(active.file, lineNumber);
        syncBreakpointsToState((added ? "Breakpoint set at " : "Breakpoint removed at ")
                + active.file.getName() + ":" + lineNumber);
    }

    public void setBreakpointCondition(String filePath, String fileName, int lineNumber, String condition) {
        if (lineNumber < 1) return;
        String resolvedPath = filePath;
        String resolvedName = fileName;
        if ((resolvedPath == null || resolvedPath.isEmpty()) && currentState.getActiveTab() != null) {
            resolvedPath = currentState.getActiveTab().file.getAbsolutePath();
            resolvedName = currentState.getActiveTab().file.getName();
        }
        if (resolvedPath == null || resolvedPath.isEmpty()) return;
        debugController.setBreakpointCondition(resolvedPath, resolvedName, lineNumber, condition);
        syncBreakpointsToState((condition != null && !condition.trim().isEmpty())
                ? "Conditional breakpoint (" + condition.trim() + ") at " + resolvedName + ":" + lineNumber
                : "Updated breakpoint at " + resolvedName + ":" + lineNumber);
    }

    public void toggleBreakpointEnabled(String filePath, int lineNumber, boolean enabled) {
        debugController.setBreakpointEnabled(filePath, lineNumber, enabled);
        syncBreakpointsToState((enabled ? "Enabled" : "Disabled") + " breakpoint at line " + lineNumber);
    }

    public void removeBreakpoint(String filePath, int lineNumber) {
        debugController.removeBreakpoint(filePath, lineNumber);
        syncBreakpointsToState("Removed breakpoint at line " + lineNumber);
    }

    public void clearAllBreakpoints() {
        for (DebugModels.BreakpointSpec spec : debugController.getAllBreakpoints()) {
            debugController.removeBreakpoint(spec.filePath, spec.lineNumber);
        }
        syncBreakpointsToState("Cleared all breakpoints");
    }

    private void syncBreakpointsToState(String bannerMessage) {
        List<DebugModels.BreakpointSpec> specs = debugController.getAllBreakpoints();
        Map<String, Set<Integer>> map = new LinkedHashMap<>();
        for (DebugModels.BreakpointSpec bp : specs) {
            map.computeIfAbsent(bp.filePath, k -> new LinkedHashSet<>()).add(bp.lineNumber);
        }
        updateState(s -> s.toBuilder()
                .breakpointsByFile(map)
                .breakpointSpecs(specs)
                .statusBannerMessage(bannerMessage)
                .build());
    }

    // ==================== Phase 5: Advanced Debugger Lifecycle & Actions ====================

    public void startLocalDebug() {
        File project = currentState.activeProject;
        if (project == null) {
            updateState(s -> s.toBuilder().statusBannerMessage("Open a project before starting debugger.").build());
            return;
        }
        EditorTab active = currentState.getActiveTab();
        File targetJava = (active != null && active.file.getName().endsWith(".java"))
                ? active.file
                : findMainActivity(project);
        saveAllFiles(() -> {
            lastOpenedDebugLocationKey = "";
            debugController.startLocalDebug(project, targetJava);
        });
    }

    public void connectJdwp(String host, int port) {
        lastOpenedDebugLocationKey = "";
        debugController.connectJdwp(host, port);
    }

    public void debugStepOver() {
        debugController.stepOver();
    }

    public void debugStepInto() {
        debugController.stepInto();
    }

    public void debugStepOut() {
        debugController.stepOut();
    }

    public void debugResume() {
        debugController.resume();
    }

    public void debugPause() {
        debugController.pause();
    }

    public void debugStop() {
        lastOpenedDebugLocationKey = "";
        debugController.stop();
    }

    public void selectDebugFrame(int frameIndex) {
        debugController.selectStackFrame(frameIndex);
    }

    public void addDebugWatch(String expression) {
        debugController.addWatch(expression);
    }

    public void removeDebugWatch(String expression) {
        debugController.removeWatch(expression);
    }

    public DebugModels.WatchItem evaluateDebugExpression(String expression) {
        return debugController.evaluateExpression(expression);
    }

    public boolean setDebugVariableValue(String varName, String valueExpr) {
        return debugController.setVariableValue(varName, valueExpr);
    }

    private void onDebugSessionStateChanged(DebugModels.DebugSessionState dbgState) {
        List<DebugModels.BreakpointSpec> specs = debugController.getAllBreakpoints();
        updateState(s -> s.toBuilder()
                .debugSessionState(dbgState)
                .breakpointSpecs(specs)
                .build());
        if (dbgState != null && dbgState.mode == DebugModels.Mode.PAUSED
                && !dbgState.activeFilePath.isEmpty() && dbgState.activeLine > 0) {
            String key = dbgState.activeFilePath + ":" + dbgState.activeLine;
            if (!key.equals(lastOpenedDebugLocationKey)) {
                lastOpenedDebugLocationKey = key;
                File target = resolveProjectFile(currentState.activeProject, dbgState.activeFilePath);
                if (target != null && target.exists()) {
                    if (currentState.viewMode == EditorViewMode.PREVIEW) {
                        setViewMode(EditorViewMode.SPLIT);
                    }
                    openFile(target, null);
                }
            }
        } else if (dbgState == null || dbgState.mode != DebugModels.Mode.PAUSED) {
            lastOpenedDebugLocationKey = "";
        }
    }

    // ==================== Phase 3: Layout Inspector, Manifest Editor, Styles, Logcat Export ====================

    public void openOrCreateStylesXml() {
        File project = currentState.activeProject;
        if (project == null) return;
        ioExecutor.execute(() -> {
            File stylesFile = new File(workspace.resolveResDir(project), "values/styles.xml");
            if (!stylesFile.exists()) {
                workspace.writeFile(stylesFile,
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                                + "<resources>\n"
                                + "    <style name=\"AppTheme\" parent=\"android:Theme.Material.Light.DarkActionBar\">\n"
                                + "        <item name=\"android:colorPrimary\">@color/primary_cyan</item>\n"
                                + "    </style>\n"
                                + "</resources>\n");
            }
            syncResourcesAndTabsSync(project, stylesFile, null, "Opened values/styles.xml");
            openFile(stylesFile, null);
        });
    }

    public String readActiveManifestXml() {
        File project = currentState.activeProject;
        if (project == null) return "";
        File manifestFile = workspace.resolveManifestFile(project);
        if (manifestFile == null) return "";
        for (EditorTab tab : currentState.openTabs) {
            if (tab.file.getAbsolutePath().equals(manifestFile.getAbsolutePath())) {
                return tab.content;
            }
        }
        return manifestFile.exists() ? workspace.readFile(manifestFile) : "";
    }

    public void saveManifestXml(String updatedXml) {
        File project = currentState.activeProject;
        if (project == null || updatedXml == null) return;
        ioExecutor.execute(() -> {
            File manifestFile = workspace.resolveManifestFile(project);
            workspace.writeFile(manifestFile, updatedXml);
            syncResourcesAndTabsSync(project, manifestFile, null, "Saved AndroidManifest.xml");
        });
    }

    public void applyUpdatedPreviewLayoutXml(String updatedXml) {
        File project = currentState.activeProject;
        if (project == null || updatedXml == null) return;
        ioExecutor.execute(() -> {
            EditorTab active = currentState.getActiveTab();
            File targetLayout = (active != null && isLayoutXml(active.file))
                    ? active.file
                    : workspace.findPrimaryLayoutXml(project);
            if (targetLayout == null) return;
            workspace.writeFile(targetLayout, updatedXml);
            List<String> xmlIds = workspace.collectXmlResourceIds(project);
            updateState(s -> {
                List<EditorTab> nextTabs = new ArrayList<>(s.openTabs.size());
                for (EditorTab t : s.openTabs) {
                    if (t.file.getAbsolutePath().equals(targetLayout.getAbsolutePath())) {
                        nextTabs.add(t.withContent(updatedXml, false));
                    } else {
                        nextTabs.add(t);
                    }
                }
                return s.toBuilder()
                        .openTabs(nextTabs)
                        .xmlResourceIds(xmlIds)
                        .previewXmlContent(updatedXml)
                        .previewLayoutName(targetLayout.getName())
                        .statusBannerMessage("Applied Layout Inspector change to " + targetLayout.getName())
                        .build();
            });
            scheduleLiveAnalysis(60);
        });
    }

    public void openPreviewLayoutAtLine(int lineNumber) {
        File project = currentState.activeProject;
        if (project == null) return;
        EditorTab active = currentState.getActiveTab();
        File targetLayout = (active != null && isLayoutXml(active.file))
                ? active.file
                : workspace.findPrimaryLayoutXml(project);
        if (targetLayout != null && targetLayout.exists()) {
            if (currentState.viewMode == EditorViewMode.PREVIEW) {
                setViewMode(EditorViewMode.SPLIT);
            }
            openFile(targetLayout, lineNumber);
        }
    }

    public void exportLogcatLogs(List<LogcatStreamReader.LogEntry> entries) {
        File project = currentState.activeProject;
        if (project == null || entries == null || entries.isEmpty()) {
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("No Logcat lines available to export")
                    .build());
            return;
        }
        ioExecutor.execute(() -> {
            File logsDir = new File(project, "build/logs");
            if (!logsDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                logsDir.mkdirs();
            }
            String fileName = "logcat_" + System.currentTimeMillis() + ".txt";
            File outFile = new File(logsDir, fileName);
            StringBuilder sb = new StringBuilder();
            for (LogcatStreamReader.LogEntry e : entries) {
                sb.append(e.timestamp).append(' ')
                        .append(e.level).append('/')
                        .append(e.tag).append(": ")
                        .append(e.message).append('\n');
            }
            workspace.writeFile(outFile, sb.toString());
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("Exported " + entries.size() + " log lines to build/logs/" + fileName)
                    .build());
        });
    }

    // ==================== Phase 2: IDE Quality (Autocomplete, Navigation, Refs, Refactor, Diagnostics) ====================

    public List<CodeEditorView.SuggestionItem> getContextSuggestions(
            CodeEditorView.Language language,
            String code,
            int cursorOffset) {
        EditorTab active = currentState.getActiveTab();
        File activeFile = active != null ? active.file : null;
        JavaProjectIndexer.ProjectIndex idx = projectIndex;
        List<JavaProjectIndexer.CompletionCandidate> candidates;
        if (language == CodeEditorView.Language.JAVA) {
            candidates = JavaProjectIndexer.suggestJava(idx, activeFile, code, cursorOffset);
        } else if (language == CodeEditorView.Language.XML) {
            candidates = JavaProjectIndexer.suggestXml(idx, activeFile, code, cursorOffset);
        } else {
            return Collections.emptyList();
        }
        List<CodeEditorView.SuggestionItem> out = new ArrayList<>(candidates.size());
        for (JavaProjectIndexer.CompletionCandidate c : candidates) {
            out.add(new CodeEditorView.SuggestionItem(
                    c.kind, c.label, c.insertText, c.cursorBackOffset, c.tokenStartOverride));
        }
        return out;
    }

    public void goToDefinitionAtCursor(int cursorOffset) {
        EditorTab active = currentState.getActiveTab();
        File project = currentState.activeProject;
        if (active == null || project == null) return;
        intelExecutor.execute(() -> {
            JavaProjectIndexer.ProjectIndex idx = ensureProjectIndexSync(project);
            JavaProjectIndexer.NavigationTarget target =
                    JavaProjectIndexer.resolveDefinition(idx, active.file, active.content, cursorOffset);
            if (target == null || target.file == null || !target.file.exists()) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("No definition found at cursor")
                        .build());
                return;
            }
            updateState(s -> s.toBuilder()
                    .viewMode(s.viewMode == EditorViewMode.PREVIEW ? EditorViewMode.SPLIT : s.viewMode)
                    .statusBannerMessage("Jumped to " + target.displayLabel + " (" + target.file.getName() + ":" + target.lineNumber + ")")
                    .build());
            openFile(target.file, target.lineNumber);
        });
    }

    public String getSymbolAtCursor(int cursorOffset) {
        EditorTab active = currentState.getActiveTab();
        if (active == null) return "";
        return JavaProjectIndexer.extractSymbolAtCursor(active.content, cursorOffset);
    }

    public void findReferencesAtCursor(int cursorOffset, ReferencesCallback callback) {
        String symbol = getSymbolAtCursor(cursorOffset);
        findReferencesForSymbol(symbol, callback);
    }

    public void findReferencesForSymbol(String symbol, ReferencesCallback callback) {
        File project = currentState.activeProject;
        if (project == null || symbol == null || symbol.trim().isEmpty()) {
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("Place cursor on a symbol to find references")
                    .build());
            return;
        }
        String clean = symbol.trim();
        Map<String, String> openMap = collectOpenContentMap(currentState.openTabs);
        intelExecutor.execute(() -> {
            List<JavaProjectIndexer.ReferenceLocation> refs =
                    JavaProjectIndexer.findReferences(project, openMap, clean);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onReferencesFound(clean, refs);
                }
            });
        });
    }

    public void renameSymbol(String oldName, String newName) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveAllFiles(() -> ioExecutor.execute(() -> {
            try {
                JavaProjectIndexer.RenameResult res = JavaProjectIndexer.renameSymbolInProject(
                        project,
                        collectOpenContentMap(currentState.openTabs),
                        oldName,
                        newName);
                if (!res.success) {
                    updateState(s -> s.toBuilder().statusBannerMessage(res.message).build());
                    return;
                }
                List<FileNode> tree = buildTree(project, 0);
                Map<String, String> strings = workspace.parseStringResources(project);
                Map<String, String> colors = workspace.parseColorResources(project);
                Map<String, String> dimens = workspace.parseDimenResources(project);
                List<String> xmlIds = workspace.collectXmlResourceIds(project);
                SandrixGitManager.GitRepoStatus gitStatus = gitManager.inspectStatus(project);

                List<EditorTab> nextTabs = new ArrayList<>();
                for (EditorTab tab : currentState.openTabs) {
                    File tabFile = tab.file;
                    if (res.renamedFromFile != null && res.renamedToFile != null
                            && tabFile.getAbsolutePath().equals(res.renamedFromFile.getAbsolutePath())) {
                        tabFile = res.renamedToFile;
                    }
                    String updatedText = res.updatedContentsByPath.get(tabFile.getAbsolutePath());
                    if (updatedText != null) {
                        nextTabs.add(new EditorTab(tabFile, updatedText, false, tab.pendingJumpLine, tab.errorLines, tab.warningLines));
                    } else if (tabFile != tab.file && tabFile.exists()) {
                        nextTabs.add(new EditorTab(tabFile, workspace.readFile(tabFile), false, tab.pendingJumpLine, tab.errorLines, tab.warningLines));
                    } else {
                        nextTabs.add(tab);
                    }
                }
                projectIndex = JavaProjectIndexer.buildIndex(project, collectOpenContentMap(nextTabs));
                updateState(s -> s.toBuilder()
                        .fileTree(tree)
                        .openTabs(nextTabs)
                        .stringResources(strings)
                        .colorResources(colors)
                        .dimenResources(dimens)
                        .xmlResourceIds(xmlIds)
                        .gitStatus(gitStatus)
                        .statusBannerMessage(res.message)
                        .build());
                scheduleLiveAnalysis(40);
            } catch (Exception e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Rename failed: " + e.getMessage())
                        .build());
            }
        }));
    }

    public void organizeActiveImports() {
        EditorTab active = currentState.getActiveTab();
        File project = currentState.activeProject;
        if (active == null || !active.file.getName().endsWith(".java")) {
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("Organize Imports applies to .java files")
                    .build());
            return;
        }
        intelExecutor.execute(() -> {
            JavaProjectIndexer.ProjectIndex idx = project != null
                    ? ensureProjectIndexSync(project)
                    : projectIndex;
            String organized = JavaProjectIndexer.organizeImports(idx, active.content);
            if (organized == null || organized.equals(active.content)) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Imports already clean")
                        .build());
                return;
            }
            updateState(s -> {
                int tabIdx = s.activeTabIndex;
                if (tabIdx < 0 || tabIdx >= s.openTabs.size()) return s;
                List<EditorTab> next = new ArrayList<>(s.openTabs);
                next.set(tabIdx, next.get(tabIdx).withContent(organized, true));
                return s.toBuilder()
                        .openTabs(next)
                        .statusBannerMessage("Organized & added missing Java imports")
                        .build();
            });
            scheduleLiveAnalysis(60);
        });
    }

    private void scheduleLiveAnalysis(long delayMs) {
        mainHandler.removeCallbacks(liveAnalysisRunnable);
        mainHandler.postDelayed(liveAnalysisRunnable, delayMs);
    }

    private void runLiveAnalysisAsync() {
        IdeUiState snap = currentState;
        File project = snap.activeProject;
        EditorTab active = snap.getActiveTab();
        if (project == null) return;
        Map<String, String> openMap = collectOpenContentMap(snap.openTabs);
        intelExecutor.execute(() -> {
            JavaProjectIndexer.ProjectIndex idx = JavaProjectIndexer.buildIndex(project, openMap);
            projectIndex = idx;

            List<BuildEngineTask.BuildDiagnostic> liveDiags = new ArrayList<>();
            Map<String, Set<Integer>> errByFile = new LinkedHashMap<>();
            Map<String, Set<Integer>> warnByFile = new LinkedHashMap<>();

            // Analyze open tabs (and prioritize active tab)
            for (EditorTab tab : snap.openTabs) {
                List<BuildEngineTask.BuildDiagnostic> fileDiags =
                        JavaProjectIndexer.analyzeFile(idx, tab.file, tab.content);
                liveDiags.addAll(fileDiags);
                Set<Integer> errs = new LinkedHashSet<>();
                Set<Integer> warns = new LinkedHashSet<>();
                for (BuildEngineTask.BuildDiagnostic d : fileDiags) {
                    if (d.severity == BuildEngineTask.BuildDiagnostic.Severity.ERROR) {
                        errs.add(d.lineNumber);
                    } else {
                        warns.add(d.lineNumber);
                    }
                }
                errByFile.put(tab.file.getAbsolutePath(), errs);
                warnByFile.put(tab.file.getAbsolutePath(), warns);
            }

            // Merge build diagnostics with live diagnostics without duplicates
            List<BuildEngineTask.BuildDiagnostic> merged = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            synchronized (lastBuildDiagnostics) {
                for (BuildEngineTask.BuildDiagnostic d : lastBuildDiagnostics) {
                    String key = d.filePath + "|" + d.lineNumber + "|" + d.message;
                    if (seen.add(key)) {
                        merged.add(d);
                        errByFile.computeIfAbsent(d.filePath, k -> new LinkedHashSet<>()).add(d.lineNumber);
                    }
                }
            }
            for (BuildEngineTask.BuildDiagnostic d : liveDiags) {
                String key = d.filePath + "|" + d.lineNumber + "|" + d.message;
                if (seen.add(key)) {
                    merged.add(d);
                }
            }

            updateState(s -> {
                List<EditorTab> updatedTabs = new ArrayList<>(s.openTabs.size());
                for (EditorTab tab : s.openTabs) {
                    String path = tab.file.getAbsolutePath();
                    Set<Integer> errs = errByFile.containsKey(path) ? errByFile.get(path) : Collections.emptySet();
                    Set<Integer> warns = warnByFile.containsKey(path) ? warnByFile.get(path) : Collections.emptySet();
                    updatedTabs.add(tab.withLineMarkers(errs, warns));
                }
                return s.toBuilder()
                        .openTabs(updatedTabs)
                        .diagnostics(merged)
                        .build();
            });
        });
    }

    private JavaProjectIndexer.ProjectIndex ensureProjectIndexSync(File project) {
        JavaProjectIndexer.ProjectIndex idx = projectIndex;
        if (idx == null || !project.equals(idx.projectDir)) {
            idx = JavaProjectIndexer.buildIndex(project, collectOpenContentMap(currentState.openTabs));
            projectIndex = idx;
        }
        return idx;
    }

    private static Map<String, String> collectOpenContentMap(List<EditorTab> tabs) {
        if (tabs == null || tabs.isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (EditorTab t : tabs) {
            if (t.file != null) {
                map.put(t.file.getAbsolutePath(), t.content);
            }
        }
        return map;
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspace;
    }

    public void toggleEditorTheme() {
        updateState(s -> s.toBuilder().darkEditorTheme(!s.darkEditorTheme).build());
    }

    public void togglePreviewCanvasTheme() {
        updateState(s -> s.toBuilder().previewLightCanvas(!s.previewLightCanvas).build());
    }

    public void dismissBanner() {
        updateState(s -> s.toBuilder().statusBannerMessage(null).build());
    }

    // ==================== Phase 4: Dependencies & Build Caching ====================

    public void addOrUpdateDependency(String configuration, String coordinate) {
        File project = currentState.activeProject;
        if (project == null || coordinate == null || coordinate.trim().isEmpty()) return;
        saveAllFiles(() -> ioExecutor.execute(() -> {
            try {
                File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(project);
                File cfgFile = new File(effectiveRoot, "build.sd");
                if (!cfgFile.exists()) {
                    File gradleFile = new File(effectiveRoot, "build.gradle");
                    if (gradleFile.exists()) cfgFile = gradleFile;
                }
                BuildSdParser.addOrUpdateDependency(cfgFile, configuration, coordinate.trim());
                syncResourcesAndTabsSync(project, cfgFile, null,
                        "Updated dependency: " + coordinate.trim());
                reloadProjectMetadataSync();
            } catch (Exception e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Dependency update failed: " + e.getMessage())
                        .build());
            }
        }));
    }

    public void removeDependency(String coordinate) {
        File project = currentState.activeProject;
        if (project == null || coordinate == null || coordinate.trim().isEmpty()) return;
        saveAllFiles(() -> ioExecutor.execute(() -> {
            try {
                File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(project);
                File cfgFile = new File(effectiveRoot, "build.sd");
                if (!cfgFile.exists()) {
                    File gradleFile = new File(effectiveRoot, "build.gradle");
                    if (gradleFile.exists()) cfgFile = gradleFile;
                }
                boolean removed = BuildSdParser.removeDependency(cfgFile, coordinate.trim());
                if (removed) {
                    syncResourcesAndTabsSync(project, cfgFile, null,
                            "Removed dependency: " + coordinate.trim());
                    reloadProjectMetadataSync();
                }
            } catch (Exception e) {
                updateState(s -> s.toBuilder()
                        .statusBannerMessage("Remove dependency failed: " + e.getMessage())
                        .build());
            }
        }));
    }

    public void clearEngineDexCache() {
        ioExecutor.execute(() -> {
            File engineRoot = SandrixToolchain.inspect(getApplication()).engineRoot;
            int deleted = DexCacheManager.clearDexCache(engineRoot);
            updateState(s -> s.toBuilder()
                    .statusBannerMessage("Cleared " + deleted + " pre-dexed cache file(s)")
                    .build());
        });
    }

    public void cleanProjectBuildCache() {
        File project = currentState.activeProject;
        if (project == null) return;
        ioExecutor.execute(() -> {
            File effectiveRoot = BuildEngineTask.resolveEffectiveProjectRoot(project);
            deleteRecursively(new File(effectiveRoot, "build"));
            if (!effectiveRoot.equals(project)) {
                deleteRecursively(new File(project, "build"));
            }
            updateState(s -> s.toBuilder()
                    .lastBuiltApk(null)
                    .lastBuiltAab(null)
                    .lastMappingFile(null)
                    .statusBannerMessage("Cleaned project build/ intermediates")
                    .build());
        });
    }

    // ==================== Git Version Control Actions ====================

    public String getSavedGitToken() {
        return gitPrefs.getString(KEY_GIT_TOKEN, "");
    }

    public void saveGitIdentity(String name, String email) {
        String cleanName = (name != null && !name.trim().isEmpty()) ? name.trim() : "Sandrix Developer";
        String cleanEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : "dev@sandrix.local";
        gitPrefs.edit()
                .putString(KEY_GIT_NAME, cleanName)
                .putString(KEY_GIT_EMAIL, cleanEmail)
                .apply();
        updateState(s -> s.toBuilder()
                .gitAuthorName(cleanName)
                .gitAuthorEmail(cleanEmail)
                .build());
    }

    private void saveGitCredentialsIfRequested(String username, String token, boolean remember) {
        String cleanUser = username != null ? username.trim() : "";
        SharedPreferences.Editor editor = gitPrefs.edit().putString(KEY_GIT_USER, cleanUser);
        if (remember) {
            editor.putString(KEY_GIT_TOKEN, token != null ? token.trim() : "");
        }
        editor.apply();
        updateState(s -> s.toBuilder().gitUsername(cleanUser).build());
    }

    public void refreshGitStatus() {
        ioExecutor.execute(this::refreshGitStatusSync);
    }

    private void refreshGitStatusSync() {
        File project = currentState.activeProject;
        SandrixGitManager.GitRepoStatus status = gitManager.inspectStatus(project);
        updateState(s -> s.toBuilder().gitStatus(status).build());
    }

    public void gitInit(String authorName, String authorEmail) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveGitIdentity(authorName, authorEmail);
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult result = gitManager.initRepository(project, authorName, authorEmail);
            List<FileNode> tree = buildTree(project, 0);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .fileTree(tree)
                    .gitStatus(result.status)
                    .statusBannerMessage(result.message)
                    .build());
        });
    }

    public void gitCommitAll(String message, String authorName, String authorEmail) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveGitIdentity(authorName, authorEmail);
        saveAllFiles(() -> {
            updateState(s -> s.toBuilder().isGitBusy(true).build());
            ioExecutor.execute(() -> {
                SandrixGitManager.GitOpResult result =
                        gitManager.commitAll(project, message, authorName, authorEmail);
                updateState(s -> s.toBuilder()
                        .isGitBusy(false)
                        .gitStatus(result.status)
                        .statusBannerMessage(result.message)
                        .build());
            });
        });
    }

    public void gitCreateBranch(String branchName, boolean checkoutAfter) {
        File project = currentState.activeProject;
        if (project == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult result = gitManager.createBranch(project, branchName, checkoutAfter);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(result.status)
                    .statusBannerMessage(result.message)
                    .build());
        });
    }

    public void gitCheckoutBranch(String branchName) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveAllFiles(() -> {
            updateState(s -> s.toBuilder().isGitBusy(true).build());
            ioExecutor.execute(() -> {
                SandrixGitManager.GitOpResult result = gitManager.checkoutBranch(project, branchName);
                reloadProjectAfterGitChangeSync(project, result);
            });
        });
    }

    public void gitSetRemote(String remoteUrl) {
        File project = currentState.activeProject;
        if (project == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult result = gitManager.setRemote(project, remoteUrl);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(result.status)
                    .statusBannerMessage(result.message)
                    .build());
        });
    }

    public void gitPull(String username, String token, boolean rememberToken) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveGitCredentialsIfRequested(username, token, rememberToken);
        saveAllFiles(() -> {
            updateState(s -> s.toBuilder().isGitBusy(true).build());
            ioExecutor.execute(() -> {
                SandrixGitManager.GitOpResult result = gitManager.pull(project, username, token);
                reloadProjectAfterGitChangeSync(project, result);
            });
        });
    }

    public void gitPush(String username, String token, boolean rememberToken) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveGitCredentialsIfRequested(username, token, rememberToken);
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult result = gitManager.push(project, username, token);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(result.status)
                    .statusBannerMessage(result.message)
                    .build());
        });
    }

    public void gitClone(String remoteUrl, String folderName, String username, String token, boolean rememberToken) {
        saveGitCredentialsIfRequested(username, token, rememberToken);
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            File root = workspace.getWorkspaceRoot();
            SandrixGitManager.GitOpResult result =
                    gitManager.cloneRepository(root, folderName, remoteUrl, username, token);
            List<File> projects = workspace.listProjects();
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .projects(projects)
                    .statusBannerMessage(result.message)
                    .build());
            if (result.success && result.targetDirectory != null && result.targetDirectory.exists()) {
                selectProject(result.targetDirectory, true);
            }
        });
    }

    public void loadGitDiff(String relativePath) {
        File project = currentState.activeProject;
        if (project == null || relativePath == null) return;
        ioExecutor.execute(() -> {
            String diff = gitManager.diffFile(project, relativePath);
            updateState(s -> s.toBuilder()
                    .gitDiffTitle(relativePath)
                    .gitDiffContent(diff)
                    .build());
        });
    }

    public void loadCommitDiff(String shortHash) {
        File project = currentState.activeProject;
        if (project == null || shortHash == null) return;
        ioExecutor.execute(() -> {
            String diff = gitManager.inspectCommitDiff(project, shortHash);
            updateState(s -> s.toBuilder()
                    .gitDiffTitle("commit " + shortHash)
                    .gitDiffContent(diff)
                    .build());
        });
    }

    public void gitStageFile(String relativePath) {
        File project = currentState.activeProject;
        if (project == null || relativePath == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult res = gitManager.stageFile(project, relativePath);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(res.status)
                    .statusBannerMessage(res.message)
                    .build());
        });
    }

    public void gitUnstageFile(String relativePath) {
        File project = currentState.activeProject;
        if (project == null || relativePath == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult res = gitManager.unstageFile(project, relativePath);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(res.status)
                    .statusBannerMessage(res.message)
                    .build());
        });
    }

    public void gitDiscardFile(String relativePath) {
        File project = currentState.activeProject;
        if (project == null || relativePath == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult res = gitManager.discardFileChanges(project, relativePath);
            reloadProjectAfterGitChangeSync(project, res);
        });
    }

    public void gitCommitStaged(String message, String authorName, String authorEmail) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveGitIdentity(authorName, authorEmail);
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult res =
                    gitManager.commitStaged(project, message, authorName, authorEmail);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(res.status)
                    .statusBannerMessage(res.message)
                    .build());
        });
    }

    public void gitStashSave(String message) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveAllFiles(() -> {
            updateState(s -> s.toBuilder().isGitBusy(true).build());
            ioExecutor.execute(() -> {
                SandrixGitManager.GitOpResult res = gitManager.stashSave(project, message);
                reloadProjectAfterGitChangeSync(project, res);
            });
        });
    }

    public void gitStashApplyOrPop(int stashIndex, boolean dropAfter) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveAllFiles(() -> {
            updateState(s -> s.toBuilder().isGitBusy(true).build());
            ioExecutor.execute(() -> {
                SandrixGitManager.GitOpResult res = gitManager.stashApplyOrPop(project, stashIndex, dropAfter);
                reloadProjectAfterGitChangeSync(project, res);
            });
        });
    }

    public void gitMergeBranch(String sourceBranch) {
        File project = currentState.activeProject;
        if (project == null) return;
        saveAllFiles(() -> {
            updateState(s -> s.toBuilder().isGitBusy(true).build());
            ioExecutor.execute(() -> {
                SandrixGitManager.GitOpResult res = gitManager.mergeBranch(project, sourceBranch);
                reloadProjectAfterGitChangeSync(project, res);
            });
        });
    }

    public void gitResolveConflict(String relativePath, boolean keepOurs) {
        File project = currentState.activeProject;
        if (project == null || relativePath == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult res = gitManager.resolveConflict(project, relativePath, keepOurs);
            reloadProjectAfterGitChangeSync(project, res);
        });
    }

    public void gitCreateTag(String tagName, String message) {
        File project = currentState.activeProject;
        if (project == null) return;
        updateState(s -> s.toBuilder().isGitBusy(true).build());
        ioExecutor.execute(() -> {
            SandrixGitManager.GitOpResult res = gitManager.createTag(project, tagName, message);
            updateState(s -> s.toBuilder()
                    .isGitBusy(false)
                    .gitStatus(res.status)
                    .statusBannerMessage(res.message)
                    .build());
        });
    }

    public void clearGitDiff() {
        updateState(s -> s.toBuilder()
                .gitDiffTitle(null)
                .gitDiffContent(null)
                .build());
    }

    private void reloadProjectAfterGitChangeSync(File projectDir, SandrixGitManager.GitOpResult result) {
        List<FileNode> tree = buildTree(projectDir, 0);
        BuildSdParser.BuildSdConfig config = parseBuildSdSafe(projectDir);
        Map<String, String> strings = workspace.parseStringResources(projectDir);
        Map<String, String> colors = workspace.parseColorResources(projectDir);
        Map<String, String> dimens = workspace.parseDimenResources(projectDir);
        List<File> drawables = workspace.listDrawableFiles(projectDir);
        List<File> assets = workspace.listAssetFiles(projectDir);
        List<String> xmlIds = workspace.collectXmlResourceIds(projectDir);
        List<EditorTab> refreshedTabs = new ArrayList<>();
        for (EditorTab tab : currentState.openTabs) {
            if (tab.file.exists()) {
                refreshedTabs.add(tab.withContent(workspace.readFile(tab.file), false));
            }
        }
        projectIndex = JavaProjectIndexer.buildIndex(projectDir, collectOpenContentMap(refreshedTabs));
        updateState(s -> {
            int nextIdx = refreshedTabs.isEmpty()
                    ? -1
                    : Math.max(0, Math.min(s.activeTabIndex, refreshedTabs.size() - 1));
            EditorTab active = nextIdx >= 0 ? refreshedTabs.get(nextIdx) : null;
            return s.toBuilder()
                    .isGitBusy(false)
                    .fileTree(tree)
                    .openTabs(refreshedTabs)
                    .activeTabIndex(nextIdx)
                    .parsedConfig(config)
                    .stringResources(strings)
                    .colorResources(colors)
                    .dimenResources(dimens)
                    .drawableFiles(drawables)
                    .assetFiles(assets)
                    .xmlResourceIds(xmlIds)
                    .previewXmlContent(active != null && isLayoutXml(active.file) ? active.content : s.previewXmlContent)
                    .previewLayoutName(active != null && isLayoutXml(active.file) ? active.file.getName() : s.previewLayoutName)
                    .gitStatus(result.status)
                    .statusBannerMessage(result.message)
                    .build();
        });
        scheduleLiveAnalysis(60);
    }

    private void startLogcatStream() {
        logcatReader.start(new LogcatStreamReader.Listener() {
            @Override
            public void onLogLine(LogcatStreamReader.LogEntry entry) {
                if (currentState.logcatPaused) return;
                updateState(s -> s.toBuilder()
                        .logcatEntries(appendBounded(s.logcatEntries, Collections.singletonList(entry), 300))
                        .build());
            }

            @Override
            public void onStreamError(String message) {
                LogcatStreamReader.LogEntry entry =
                        new LogcatStreamReader.LogEntry("--:--:--", "W", "Logcat", message, message);
                updateState(s -> s.toBuilder()
                        .logcatEntries(appendBounded(s.logcatEntries, Collections.singletonList(entry), 300))
                        .build());
            }
        });
    }

    private List<FileNode> buildTree(File root, int depth) {
        List<FileNode> nodes = new ArrayList<>();
        File[] raw = root.listFiles();
        if (raw == null) return nodes;
        List<File> children = new ArrayList<>();
        for (File f : raw) {
            String name = f.getName();
            if (!"build".equals(name)
                    && !".git".equals(name)
                    && (!name.startsWith(".") || ".gitignore".equals(name))) {
                children.add(f);
            }
        }
        Collections.sort(children, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));

        for (File child : children) {
            boolean expanded = child.isDirectory() && expandedFolders.contains(child.getAbsolutePath());
            nodes.add(new FileNode(child, child.getName(), depth, child.isDirectory(), expanded));
            if (expanded) {
                nodes.addAll(buildTree(child, depth + 1));
            }
        }
        return nodes;
    }

    private BuildSdParser.BuildSdConfig parseBuildSdSafe(File projectDir) {
        try {
            BuildSdParser.BuildSdConfig cfg = BuildSdParser.parseProjectConfig(projectDir);
            File hasAny = workspace.resolveBuildConfigFile(projectDir);
            return (hasAny != null && hasAny.exists()) ? cfg : null;
        } catch (Exception e) {
            return null;
        }
    }

    private File findMainActivity(File projectDir) {
        return findMainActivityRecursive(projectDir);
    }

    private File findMainActivityRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!"build".equals(f.getName())) {
                    File found = findMainActivityRecursive(f);
                    if (found != null) return found;
                }
            } else if ("MainActivity.java".equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    private static boolean isLayoutXml(File file) {
        if (file == null || !file.getName().endsWith(".xml")) return false;
        File parent = file.getParentFile();
        return parent != null && parent.getName().startsWith("layout");
    }

    private static String resolvePreviewContent(EditorTab tab, String fallbackXml) {
        return (tab != null && isLayoutXml(tab.file)) ? tab.content : fallbackXml;
    }

    private static String resolvePreviewName(EditorTab tab, String fallbackName) {
        return (tab != null && isLayoutXml(tab.file)) ? tab.file.getName() : fallbackName;
    }

    private static <T> List<T> appendBounded(List<T> existing, List<T> additions, int max) {
        List<T> next = new ArrayList<>(existing.size() + additions.size());
        next.addAll(existing);
        next.addAll(additions);
        if (next.size() > max) {
            return new ArrayList<>(next.subList(next.size() - max, next.size()));
        }
        return next;
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
}
