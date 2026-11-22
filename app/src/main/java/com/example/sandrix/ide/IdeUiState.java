package com.example.sandrix.ide;

import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.debug.DebugModels;
import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.engine.SandrixToolchain;
import com.example.sandrix.git.SandrixGitManager;
import com.example.sandrix.logcat.LogcatStreamReader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IdeUiState {
    public final boolean hasExternalStorageAccess;
    public final String workspacePath;
    public final List<File> projects;
    public final File activeProject;
    public final List<FileNode> fileTree;
    public final List<EditorTab> openTabs;
    public final int activeTabIndex;
    public final EditorViewMode viewMode;
    public final boolean consoleExpanded;
    public final ConsoleTab activeConsoleTab;
    public final boolean isBuilding;
    public final BuildEngineTask.Stage buildStage;
    public final List<BuildLogLine> buildLogs;
    public final List<BuildEngineTask.BuildDiagnostic> diagnostics;
    public final List<LogcatStreamReader.LogEntry> logcatEntries;
    public final boolean logcatPaused;
    public final String logcatLevelFilter;
    public final String logcatSearchQuery;
    public final File lastBuiltApk;
    public final File lastBuiltAab;
    public final File lastMappingFile;
    public final String lastBuildSummary;
    public final BuildSdParser.BuildSdConfig parsedConfig;
    public final List<SandrixToolchain.ToolStatus> toolchainStatuses;
    public final Map<String, String> stringResources;
    public final Map<String, String> colorResources;
    public final Map<String, String> dimenResources;
    public final List<File> drawableFiles;
    public final List<File> assetFiles;
    public final List<String> xmlResourceIds;
    public final Map<String, Set<Integer>> breakpointsByFile;
    public final List<DebugModels.BreakpointSpec> breakpointSpecs;
    public final DebugModels.DebugSessionState debugSessionState;
    public final String previewXmlContent;
    public final String previewLayoutName;
    public final boolean darkEditorTheme;
    public final boolean previewLightCanvas;
    public final String statusBannerMessage;
    public final SandrixGitManager.GitRepoStatus gitStatus;
    public final boolean isGitBusy;
    public final String gitAuthorName;
    public final String gitAuthorEmail;
    public final String gitUsername;
    public final String gitDiffTitle;
    public final String gitDiffContent;

    private IdeUiState(Builder b) {
        this.hasExternalStorageAccess = b.hasExternalStorageAccess;
        this.workspacePath = b.workspacePath;
        this.projects = Collections.unmodifiableList(new ArrayList<>(b.projects));
        this.activeProject = b.activeProject;
        this.fileTree = Collections.unmodifiableList(new ArrayList<>(b.fileTree));
        this.openTabs = Collections.unmodifiableList(new ArrayList<>(b.openTabs));
        this.activeTabIndex = b.activeTabIndex;
        this.viewMode = b.viewMode;
        this.consoleExpanded = b.consoleExpanded;
        this.activeConsoleTab = b.activeConsoleTab;
        this.isBuilding = b.isBuilding;
        this.buildStage = b.buildStage;
        this.buildLogs = Collections.unmodifiableList(new ArrayList<>(b.buildLogs));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(b.diagnostics));
        this.logcatEntries = Collections.unmodifiableList(new ArrayList<>(b.logcatEntries));
        this.logcatPaused = b.logcatPaused;
        this.logcatLevelFilter = b.logcatLevelFilter;
        this.logcatSearchQuery = b.logcatSearchQuery;
        this.lastBuiltApk = b.lastBuiltApk;
        this.lastBuiltAab = b.lastBuiltAab;
        this.lastMappingFile = b.lastMappingFile;
        this.lastBuildSummary = b.lastBuildSummary;
        this.parsedConfig = b.parsedConfig;
        this.toolchainStatuses = Collections.unmodifiableList(new ArrayList<>(b.toolchainStatuses));
        this.stringResources = Collections.unmodifiableMap(new LinkedHashMap<>(b.stringResources));
        this.colorResources = Collections.unmodifiableMap(new LinkedHashMap<>(b.colorResources));
        this.dimenResources = Collections.unmodifiableMap(new LinkedHashMap<>(b.dimenResources));
        this.drawableFiles = Collections.unmodifiableList(new ArrayList<>(b.drawableFiles));
        this.assetFiles = Collections.unmodifiableList(new ArrayList<>(b.assetFiles));
        this.xmlResourceIds = Collections.unmodifiableList(new ArrayList<>(b.xmlResourceIds));
        Map<String, Set<Integer>> bpCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> e : b.breakpointsByFile.entrySet()) {
            bpCopy.put(e.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(e.getValue())));
        }
        this.breakpointsByFile = Collections.unmodifiableMap(bpCopy);
        this.breakpointSpecs = Collections.unmodifiableList(new ArrayList<>(b.breakpointSpecs));
        this.debugSessionState = b.debugSessionState != null
                ? b.debugSessionState : DebugModels.DebugSessionState.idle();
        this.previewXmlContent = b.previewXmlContent;
        this.previewLayoutName = b.previewLayoutName;
        this.darkEditorTheme = b.darkEditorTheme;
        this.previewLightCanvas = b.previewLightCanvas;
        this.statusBannerMessage = b.statusBannerMessage;
        this.gitStatus = b.gitStatus != null ? b.gitStatus : SandrixGitManager.GitRepoStatus.notARepo();
        this.isGitBusy = b.isGitBusy;
        this.gitAuthorName = b.gitAuthorName;
        this.gitAuthorEmail = b.gitAuthorEmail;
        this.gitUsername = b.gitUsername;
        this.gitDiffTitle = b.gitDiffTitle;
        this.gitDiffContent = b.gitDiffContent;
    }

    public EditorTab getActiveTab() {
        if (activeTabIndex >= 0 && activeTabIndex < openTabs.size()) {
            return openTabs.get(activeTabIndex);
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private boolean hasExternalStorageAccess = false;
        private String workspacePath = "";
        private List<File> projects = Collections.emptyList();
        private File activeProject = null;
        private List<FileNode> fileTree = Collections.emptyList();
        private List<EditorTab> openTabs = Collections.emptyList();
        private int activeTabIndex = -1;
        private EditorViewMode viewMode = EditorViewMode.SPLIT;
        private boolean consoleExpanded = true;
        private ConsoleTab activeConsoleTab = ConsoleTab.BUILD;
        private boolean isBuilding = false;
        private BuildEngineTask.Stage buildStage = null;
        private List<BuildLogLine> buildLogs = Collections.emptyList();
        private List<BuildEngineTask.BuildDiagnostic> diagnostics = Collections.emptyList();
        private List<LogcatStreamReader.LogEntry> logcatEntries = Collections.emptyList();
        private boolean logcatPaused = false;
        private String logcatLevelFilter = "ALL";
        private String logcatSearchQuery = "";
        private File lastBuiltApk = null;
        private File lastBuiltAab = null;
        private File lastMappingFile = null;
        private String lastBuildSummary = null;
        private BuildSdParser.BuildSdConfig parsedConfig = null;
        private List<SandrixToolchain.ToolStatus> toolchainStatuses = Collections.emptyList();
        private Map<String, String> stringResources = Collections.emptyMap();
        private Map<String, String> colorResources = Collections.emptyMap();
        private Map<String, String> dimenResources = Collections.emptyMap();
        private List<File> drawableFiles = Collections.emptyList();
        private List<File> assetFiles = Collections.emptyList();
        private List<String> xmlResourceIds = Collections.emptyList();
        private Map<String, Set<Integer>> breakpointsByFile = Collections.emptyMap();
        private List<DebugModels.BreakpointSpec> breakpointSpecs = Collections.emptyList();
        private DebugModels.DebugSessionState debugSessionState = DebugModels.DebugSessionState.idle();
        private String previewXmlContent = "";
        private String previewLayoutName = "activity_main.xml";
        private boolean darkEditorTheme = true;
        private boolean previewLightCanvas = true;
        private String statusBannerMessage = null;
        private SandrixGitManager.GitRepoStatus gitStatus = SandrixGitManager.GitRepoStatus.notARepo();
        private boolean isGitBusy = false;
        private String gitAuthorName = "Sandrix Developer";
        private String gitAuthorEmail = "dev@sandrix.local";
        private String gitUsername = "";
        private String gitDiffTitle = null;
        private String gitDiffContent = null;

        public Builder() {}

        public Builder(IdeUiState src) {
            this.hasExternalStorageAccess = src.hasExternalStorageAccess;
            this.workspacePath = src.workspacePath;
            this.projects = src.projects;
            this.activeProject = src.activeProject;
            this.fileTree = src.fileTree;
            this.openTabs = src.openTabs;
            this.activeTabIndex = src.activeTabIndex;
            this.viewMode = src.viewMode;
            this.consoleExpanded = src.consoleExpanded;
            this.activeConsoleTab = src.activeConsoleTab;
            this.isBuilding = src.isBuilding;
            this.buildStage = src.buildStage;
            this.buildLogs = src.buildLogs;
            this.diagnostics = src.diagnostics;
            this.logcatEntries = src.logcatEntries;
            this.logcatPaused = src.logcatPaused;
            this.logcatLevelFilter = src.logcatLevelFilter;
            this.logcatSearchQuery = src.logcatSearchQuery;
            this.lastBuiltApk = src.lastBuiltApk;
            this.lastBuiltAab = src.lastBuiltAab;
            this.lastMappingFile = src.lastMappingFile;
            this.lastBuildSummary = src.lastBuildSummary;
            this.parsedConfig = src.parsedConfig;
            this.toolchainStatuses = src.toolchainStatuses;
            this.stringResources = src.stringResources;
            this.colorResources = src.colorResources;
            this.dimenResources = src.dimenResources;
            this.drawableFiles = src.drawableFiles;
            this.assetFiles = src.assetFiles;
            this.xmlResourceIds = src.xmlResourceIds;
            this.breakpointsByFile = src.breakpointsByFile;
            this.breakpointSpecs = src.breakpointSpecs;
            this.debugSessionState = src.debugSessionState;
            this.previewXmlContent = src.previewXmlContent;
            this.previewLayoutName = src.previewLayoutName;
            this.darkEditorTheme = src.darkEditorTheme;
            this.previewLightCanvas = src.previewLightCanvas;
            this.statusBannerMessage = src.statusBannerMessage;
            this.gitStatus = src.gitStatus;
            this.isGitBusy = src.isGitBusy;
            this.gitAuthorName = src.gitAuthorName;
            this.gitAuthorEmail = src.gitAuthorEmail;
            this.gitUsername = src.gitUsername;
            this.gitDiffTitle = src.gitDiffTitle;
            this.gitDiffContent = src.gitDiffContent;
        }

        public Builder hasExternalStorageAccess(boolean v) { this.hasExternalStorageAccess = v; return this; }
        public Builder workspacePath(String v) { this.workspacePath = v != null ? v : ""; return this; }
        public Builder projects(List<File> v) { this.projects = v != null ? v : Collections.emptyList(); return this; }
        public Builder activeProject(File v) { this.activeProject = v; return this; }
        public Builder fileTree(List<FileNode> v) { this.fileTree = v != null ? v : Collections.emptyList(); return this; }
        public Builder openTabs(List<EditorTab> v) { this.openTabs = v != null ? v : Collections.emptyList(); return this; }
        public Builder activeTabIndex(int v) { this.activeTabIndex = v; return this; }
        public Builder viewMode(EditorViewMode v) { this.viewMode = v; return this; }
        public Builder consoleExpanded(boolean v) { this.consoleExpanded = v; return this; }
        public Builder activeConsoleTab(ConsoleTab v) { this.activeConsoleTab = v; return this; }
        public Builder isBuilding(boolean v) { this.isBuilding = v; return this; }
        public Builder buildStage(BuildEngineTask.Stage v) { this.buildStage = v; return this; }
        public Builder buildLogs(List<BuildLogLine> v) { this.buildLogs = v != null ? v : Collections.emptyList(); return this; }
        public Builder diagnostics(List<BuildEngineTask.BuildDiagnostic> v) { this.diagnostics = v != null ? v : Collections.emptyList(); return this; }
        public Builder logcatEntries(List<LogcatStreamReader.LogEntry> v) { this.logcatEntries = v != null ? v : Collections.emptyList(); return this; }
        public Builder logcatPaused(boolean v) { this.logcatPaused = v; return this; }
        public Builder logcatLevelFilter(String v) { this.logcatLevelFilter = v != null ? v : "ALL"; return this; }
        public Builder logcatSearchQuery(String v) { this.logcatSearchQuery = v != null ? v : ""; return this; }
        public Builder lastBuiltApk(File v) { this.lastBuiltApk = v; return this; }
        public Builder lastBuiltAab(File v) { this.lastBuiltAab = v; return this; }
        public Builder lastMappingFile(File v) { this.lastMappingFile = v; return this; }
        public Builder lastBuildSummary(String v) { this.lastBuildSummary = v; return this; }
        public Builder parsedConfig(BuildSdParser.BuildSdConfig v) { this.parsedConfig = v; return this; }
        public Builder toolchainStatuses(List<SandrixToolchain.ToolStatus> v) { this.toolchainStatuses = v != null ? v : Collections.emptyList(); return this; }
        public Builder stringResources(Map<String, String> v) { this.stringResources = v != null ? v : Collections.emptyMap(); return this; }
        public Builder colorResources(Map<String, String> v) { this.colorResources = v != null ? v : Collections.emptyMap(); return this; }
        public Builder dimenResources(Map<String, String> v) { this.dimenResources = v != null ? v : Collections.emptyMap(); return this; }
        public Builder drawableFiles(List<File> v) { this.drawableFiles = v != null ? v : Collections.emptyList(); return this; }
        public Builder assetFiles(List<File> v) { this.assetFiles = v != null ? v : Collections.emptyList(); return this; }
        public Builder xmlResourceIds(List<String> v) { this.xmlResourceIds = v != null ? v : Collections.emptyList(); return this; }
        public Builder breakpointsByFile(Map<String, Set<Integer>> v) { this.breakpointsByFile = v != null ? v : Collections.emptyMap(); return this; }
        public Builder breakpointSpecs(List<DebugModels.BreakpointSpec> v) { this.breakpointSpecs = v != null ? v : Collections.emptyList(); return this; }
        public Builder debugSessionState(DebugModels.DebugSessionState v) { this.debugSessionState = v != null ? v : DebugModels.DebugSessionState.idle(); return this; }
        public Builder previewXmlContent(String v) { this.previewXmlContent = v != null ? v : ""; return this; }
        public Builder previewLayoutName(String v) { this.previewLayoutName = v != null ? v : "activity_main.xml"; return this; }
        public Builder darkEditorTheme(boolean v) { this.darkEditorTheme = v; return this; }
        public Builder previewLightCanvas(boolean v) { this.previewLightCanvas = v; return this; }
        public Builder statusBannerMessage(String v) { this.statusBannerMessage = v; return this; }
        public Builder gitStatus(SandrixGitManager.GitRepoStatus v) { this.gitStatus = v != null ? v : SandrixGitManager.GitRepoStatus.notARepo(); return this; }
        public Builder isGitBusy(boolean v) { this.isGitBusy = v; return this; }
        public Builder gitAuthorName(String v) { this.gitAuthorName = v != null ? v : "Sandrix Developer"; return this; }
        public Builder gitAuthorEmail(String v) { this.gitAuthorEmail = v != null ? v : "dev@sandrix.local"; return this; }
        public Builder gitUsername(String v) { this.gitUsername = v != null ? v : ""; return this; }
        public Builder gitDiffTitle(String v) { this.gitDiffTitle = v; return this; }
        public Builder gitDiffContent(String v) { this.gitDiffContent = v; return this; }

        public IdeUiState build() {
            return new IdeUiState(this);
        }
    }
}
