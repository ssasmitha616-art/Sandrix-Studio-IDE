package com.example;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.sandrix.debug.DebugModels;
import com.example.sandrix.editor.CodeEditorView;
import com.example.sandrix.engine.KeystoreGenerator;
import com.example.sandrix.git.SandrixGitManager;
import com.example.sandrix.ide.ConsoleTab;
import com.example.sandrix.ide.EditorTab;
import com.example.sandrix.ide.EditorViewMode;
import com.example.sandrix.ide.IdeUiState;
import com.example.sandrix.ide.IdeViewModel;
import com.example.sandrix.logcat.LogcatStreamReader;
import com.example.sandrix.preview.LayoutPreviewer;
import com.example.sandrix.ui.ApkAnalyzerDialog;
import com.example.sandrix.ui.BuildLogsAdapter;
import com.example.sandrix.ui.DebugInspectorDialog;
import com.example.sandrix.ui.DependencyManagerDialog;
import com.example.sandrix.ui.EditorTabsAdapter;
import com.example.sandrix.ui.FileTreeAdapter;
import com.example.sandrix.ui.GitDialogs;
import com.example.sandrix.ui.IdeDialogs;
import com.example.sandrix.ui.LayoutInspectorDialog;
import com.example.sandrix.ui.LogcatAdapter;
import com.example.sandrix.ui.ManifestEditorDialog;
import com.example.sandrix.ui.ProblemsAdapter;
import com.example.sandrix.ui.ResourceManagerDialog;
import com.example.sandrix.ui.SigningManagerDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure Java Material 3 MainActivity hosting the Sandrix Studio IDE workspace.
 */
public class MainActivity extends AppCompatActivity {

    private IdeViewModel ideViewModel;

    private DrawerLayout drawerLayout;
    private View mainWorkspaceRoot;
    private View drawerPanel;

    private TextView tvProjectName;
    private TextView tvProjectSubtitle;
    private ImageButton btnSave;
    private ImageButton btnInstallApk;
    private MaterialButton btnRunBuild;

    private RecyclerView rvEditorTabs;
    private EditorTabsAdapter tabsAdapter;
    private ImageButton btnModeCode;
    private ImageButton btnModeSplit;
    private ImageButton btnModePreview;
    private ImageButton btnResources;
    private ImageButton btnGit;
    private ImageButton btnToggleEditorTheme;

    private LinearLayout workspaceSplitContainer;
    private View editorPane;
    private View splitDivider;
    private View previewPane;
    private View emptyWorkspacePane;
    private TextView tvEmptyProjectName;

    private CodeEditorView codeEditor;
    private LinearLayout symbolBarContainer;
    private TextView tvPreviewTitle;
    private TextView btnInspectLayout;
    private TextView btnTogglePreviewCanvas;
    private LayoutPreviewer layoutPreviewer;

    private View debugControlBar;
    private ImageView ivDebugIndicator;
    private TextView tvDebugStatus;
    private ImageButton btnDebugResumePause;
    private ImageButton btnDebugStepOver;
    private ImageButton btnDebugStepInto;
    private ImageButton btnDebugStepOut;
    private ImageButton btnDebugInspect;
    private ImageButton btnDebugStop;

    private LinearProgressIndicator buildProgress;
    private View tabConsoleBuild;
    private ImageView ivConsoleBuild;
    private TextView tvConsoleBuild;
    private View tabConsoleProblems;
    private ImageView ivConsoleProblems;
    private TextView tvConsoleProblems;
    private TextView tvProblemsBadge;
    private View tabConsoleLogcat;
    private ImageView ivConsoleLogcat;
    private TextView tvConsoleLogcat;
    private TextView tvBuildStage;
    private ImageButton btnOpenDebugger;
    private ImageButton btnLogcatPause;
    private ImageButton btnConsoleClear;
    private ImageButton btnConsoleToggle;
    private View consoleBody;

    private View consolePageBuild;
    private RecyclerView rvBuildLogs;
    private TextView tvEmptyBuildLogs;
    private BuildLogsAdapter buildLogsAdapter;

    private View consolePageProblems;
    private RecyclerView rvProblems;
    private TextView tvEmptyProblems;
    private ProblemsAdapter problemsAdapter;

    private View consolePageLogcat;
    private ChipGroup cgLogcatFilter;
    private EditText etLogcatSearch;
    private ImageButton btnLogcatExport;
    private RecyclerView rvLogcat;
    private LogcatAdapter logcatAdapter;
    private List<LogcatStreamReader.LogEntry> lastFilteredLogcat = new ArrayList<>();

    // Drawer views
    private TextView tvDrawerProjectName;
    private ImageView ivStorageStatus;
    private TextView tvStorageStatus;
    private TextView tvWorkspacePath;
    private MaterialButton btnGrantStorage;
    private ChipGroup chipGroupProjects;
    private View drawerConfigRow;
    private TextView tvDrawerBuildSummary;
    private ImageView ivDrawerGit;
    private TextView tvDrawerGitStatus;
    private RecyclerView rvFileTree;
    private FileTreeAdapter fileTreeAdapter;
    private GitDialogs.GitPanelController activeGitPanel;
    private ResourceManagerDialog.Controller activeResourceDialog;
    private DebugInspectorDialog.Controller activeDebugDialog;

    // Diff caches to avoid unnecessary re-renders
    private boolean lastIsWide = false;
    private String lastPreviewXml = null;
    private String lastPreviewName = null;
    private boolean lastPreviewLight = true;
    private Map<String, String> lastStringRes = null;
    private Map<String, String> lastColorRes = null;
    private String lastDynamicSuggestionSignature = "";
    private String lastProjectsKey = "";
    private String lastActiveProjectPath = "";
    private int lastBuildLogsSize = 0;
    private int lastLogcatSize = 0;
    private String lastLogcatFilterKey = null;
    private int lastLogcatSourceCount = -1;
    private LogcatStreamReader.LogEntry lastLogcatFirst = null;
    private LogcatStreamReader.LogEntry lastLogcatLast = null;
    private List<LogcatStreamReader.LogEntry> lastLogcatResult = new ArrayList<>();
    private boolean suppressLogcatChipCallback = false;

    private final ActivityResultLauncher<Intent> manageAllFilesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> ideViewModel.refreshWorkspace());

    private final ActivityResultLauncher<String[]> legacyStorageLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> ideViewModel.refreshWorkspace());

    private final ActivityResultLauncher<String[]> toolchainZipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    ideViewModel.importToolchainZip(uri);
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> drawableImagePicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    ideViewModel.importDrawableImage(uri, null);
                }
            });

    private final ActivityResultLauncher<String[]> projectAssetPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    ideViewModel.importProjectAsset(uri, null);
                }
            });

    private final ActivityResultLauncher<String[]> externalApkPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    analyzeExternalApkUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ideViewModel = new ViewModelProvider(this).get(IdeViewModel.class);

        bindViews();
        applyEdgeToEdgeInsets();
        setupRecyclerViews();
        setupSymbolBar();
        setupListeners();
        setupBackHandler();

        ideViewModel.getUiState().observe(this, this::renderState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ideViewModel != null) {
            ideViewModel.refreshWorkspace();
        }
    }

    private void bindViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        mainWorkspaceRoot = findViewById(R.id.main_workspace_root);
        drawerPanel = findViewById(R.id.drawer_panel);

        tvProjectName = findViewById(R.id.tv_project_name);
        tvProjectSubtitle = findViewById(R.id.tv_project_subtitle);
        btnSave = findViewById(R.id.btn_save);
        btnInstallApk = findViewById(R.id.btn_install_apk);
        btnRunBuild = findViewById(R.id.btn_run_build);

        rvEditorTabs = findViewById(R.id.rv_editor_tabs);
        btnModeCode = findViewById(R.id.btn_mode_code);
        btnModeSplit = findViewById(R.id.btn_mode_split);
        btnModePreview = findViewById(R.id.btn_mode_preview);
        btnResources = findViewById(R.id.btn_resources);
        btnGit = findViewById(R.id.btn_git);
        btnToggleEditorTheme = findViewById(R.id.btn_toggle_editor_theme);

        workspaceSplitContainer = findViewById(R.id.workspace_split_container);
        editorPane = findViewById(R.id.editor_pane);
        splitDivider = findViewById(R.id.split_divider);
        previewPane = findViewById(R.id.preview_pane);
        emptyWorkspacePane = findViewById(R.id.empty_workspace_pane);
        tvEmptyProjectName = findViewById(R.id.tv_empty_project_name);

        codeEditor = findViewById(R.id.code_editor);
        symbolBarContainer = findViewById(R.id.symbol_bar_container);
        tvPreviewTitle = findViewById(R.id.tv_preview_title);
        btnInspectLayout = findViewById(R.id.btn_inspect_layout);
        btnTogglePreviewCanvas = findViewById(R.id.btn_toggle_preview_canvas);
        layoutPreviewer = findViewById(R.id.layout_previewer);

        debugControlBar = findViewById(R.id.debug_control_bar);
        ivDebugIndicator = findViewById(R.id.iv_debug_indicator);
        tvDebugStatus = findViewById(R.id.tv_debug_status);
        btnDebugResumePause = findViewById(R.id.btn_debug_resume_pause);
        btnDebugStepOver = findViewById(R.id.btn_debug_step_over);
        btnDebugStepInto = findViewById(R.id.btn_debug_step_into);
        btnDebugStepOut = findViewById(R.id.btn_debug_step_out);
        btnDebugInspect = findViewById(R.id.btn_debug_inspect);
        btnDebugStop = findViewById(R.id.btn_debug_stop);

        buildProgress = findViewById(R.id.build_progress);
        tabConsoleBuild = findViewById(R.id.tab_console_build);
        ivConsoleBuild = findViewById(R.id.iv_console_build);
        tvConsoleBuild = findViewById(R.id.tv_console_build);
        tabConsoleProblems = findViewById(R.id.tab_console_problems);
        ivConsoleProblems = findViewById(R.id.iv_console_problems);
        tvConsoleProblems = findViewById(R.id.tv_console_problems);
        tvProblemsBadge = findViewById(R.id.tv_problems_badge);
        tabConsoleLogcat = findViewById(R.id.tab_console_logcat);
        ivConsoleLogcat = findViewById(R.id.iv_console_logcat);
        tvConsoleLogcat = findViewById(R.id.tv_console_logcat);
        tvBuildStage = findViewById(R.id.tv_build_stage);
        btnOpenDebugger = findViewById(R.id.btn_open_debugger);
        btnLogcatPause = findViewById(R.id.btn_logcat_pause);
        btnConsoleClear = findViewById(R.id.btn_console_clear);
        btnConsoleToggle = findViewById(R.id.btn_console_toggle);
        consoleBody = findViewById(R.id.console_body);

        consolePageBuild = findViewById(R.id.console_page_build);
        rvBuildLogs = findViewById(R.id.rv_build_logs);
        tvEmptyBuildLogs = findViewById(R.id.tv_empty_build_logs);

        consolePageProblems = findViewById(R.id.console_page_problems);
        rvProblems = findViewById(R.id.rv_problems);
        tvEmptyProblems = findViewById(R.id.tv_empty_problems);

        consolePageLogcat = findViewById(R.id.console_page_logcat);
        cgLogcatFilter = findViewById(R.id.cg_logcat_filter);
        etLogcatSearch = findViewById(R.id.et_logcat_search);
        btnLogcatExport = findViewById(R.id.btn_logcat_export);
        rvLogcat = findViewById(R.id.rv_logcat);

        tvDrawerProjectName = findViewById(R.id.tv_drawer_project_name);
        ivStorageStatus = findViewById(R.id.iv_storage_status);
        tvStorageStatus = findViewById(R.id.tv_storage_status);
        tvWorkspacePath = findViewById(R.id.tv_workspace_path);
        btnGrantStorage = findViewById(R.id.btn_grant_storage);
        chipGroupProjects = findViewById(R.id.chip_group_projects);
        drawerConfigRow = findViewById(R.id.drawer_config_row);
        tvDrawerBuildSummary = findViewById(R.id.tv_drawer_build_summary);
        ivDrawerGit = findViewById(R.id.iv_drawer_git);
        tvDrawerGitStatus = findViewById(R.id.tv_drawer_git_status);
        rvFileTree = findViewById(R.id.rv_file_tree);
    }

    private void applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mainWorkspaceRoot, (v, windowInsets) -> {
            Insets sys = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(sys.bottom, ime.bottom);
            v.setPadding(sys.left, sys.top, sys.right, bottom);
            return windowInsets;
        });

        final int baseLeft = drawerPanel.getPaddingLeft();
        final int baseTop = drawerPanel.getPaddingTop();
        final int baseRight = drawerPanel.getPaddingRight();
        final int baseBottom = drawerPanel.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(drawerPanel, (v, windowInsets) -> {
            Insets sys = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(baseLeft + sys.left, baseTop + sys.top, baseRight, baseBottom + sys.bottom);
            return windowInsets;
        });
    }

    private void setupRecyclerViews() {
        rvEditorTabs.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        tabsAdapter = new EditorTabsAdapter(new EditorTabsAdapter.Listener() {
            @Override
            public void onSelectTab(int index) {
                ideViewModel.selectTab(index);
            }

            @Override
            public void onCloseTab(int index) {
                ideViewModel.closeTab(index);
            }
        });
        rvEditorTabs.setAdapter(tabsAdapter);

        rvBuildLogs.setLayoutManager(new LinearLayoutManager(this));
        buildLogsAdapter = new BuildLogsAdapter(line -> ideViewModel.jumpToLogLine(line));
        rvBuildLogs.setAdapter(buildLogsAdapter);

        rvProblems.setLayoutManager(new LinearLayoutManager(this));
        problemsAdapter = new ProblemsAdapter(diag -> ideViewModel.jumpToDiagnostic(diag));
        rvProblems.setAdapter(problemsAdapter);

        rvLogcat.setLayoutManager(new LinearLayoutManager(this));
        logcatAdapter = new LogcatAdapter();
        rvLogcat.setAdapter(logcatAdapter);

        rvFileTree.setLayoutManager(new LinearLayoutManager(this));
        fileTreeAdapter = new FileTreeAdapter(new FileTreeAdapter.Listener() {
            @Override
            public void onNodeClicked(com.example.sandrix.ide.FileNode node) {
                if (node.isDirectory) {
                    ideViewModel.toggleFolder(node.file);
                } else {
                    ideViewModel.openFile(node.file);
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
            }

            @Override
            public void onDeleteClicked(File file) {
                ideViewModel.deleteFile(file);
            }
        });
        rvFileTree.setAdapter(fileTreeAdapter);
    }

    private void setupSymbolBar() {
        String[][] symbols = {
                {"TAB", "    "},
                {"{", "{"},
                {"}", "}"},
                {"(", "("},
                {")", ")"},
                {";", ";"},
                {"\"", "\""},
                {"<", "<"},
                {">", ">"},
                {"/", "/"},
                {"=", "="},
                {"android:", "android:"}
        };
        Typeface mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono);
        float density = getResources().getDisplayMetrics().density;
        int hPad = Math.round(12 * density);
        int vPad = Math.round(7 * density);
        int marginEnd = Math.round(6 * density);

        TextView autoChip = new TextView(this);
        autoChip.setText("Auto ⇥");
        autoChip.setTypeface(mono, Typeface.BOLD);
        autoChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        autoChip.setTextColor(ContextCompat.getColor(this, R.color.sandrix_cyan));
        autoChip.setBackgroundResource(R.drawable.bg_pill_selected);
        autoChip.setPadding(hPad, vPad, hPad, vPad);
        autoChip.setMinHeight(Math.round(32 * density));
        autoChip.setGravity(Gravity.CENTER);
        autoChip.setContentDescription("Trigger or accept code suggestion");
        autoChip.setOnClickListener(v -> codeEditor.acceptTopSuggestionOrTrigger());
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.setMarginEnd(marginEnd);
        symbolBarContainer.addView(autoChip, autoLp);

        addSymbolActionChip("GoDef", "Go to Definition", mono, hPad, vPad, marginEnd, density,
                v -> ideViewModel.goToDefinitionAtCursor(codeEditor.getCursorOffset()));
        addSymbolActionChip("Refs", "Find References", mono, hPad, vPad, marginEnd, density,
                v -> ideViewModel.findReferencesAtCursor(codeEditor.getCursorOffset(), (sym, refs) ->
                        IdeDialogs.showFindReferencesDialog(this, sym, refs, ref ->
                                ideViewModel.openFile(ref.file, ref.lineNumber))));
        addSymbolActionChip("Rename", "Rename Symbol", mono, hPad, vPad, marginEnd, density,
                v -> IdeDialogs.showRenameSymbolDialog(this,
                        ideViewModel.getSymbolAtCursor(codeEditor.getCursorOffset()),
                        (oldN, newN) -> ideViewModel.renameSymbol(oldN, newN)));
        addSymbolActionChip("Imports", "Organize Imports", mono, hPad, vPad, marginEnd, density,
                v -> ideViewModel.organizeActiveImports());

        for (String[] pair : symbols) {
            TextView chip = new TextView(this);
            chip.setText(pair[0]);
            chip.setTypeface(mono);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setTextColor(ContextCompat.getColor(this, R.color.sandrix_text_primary));
            chip.setBackgroundResource(R.drawable.bg_pill_surface);
            chip.setPadding(hPad, vPad, hPad, vPad);
            chip.setMinHeight(Math.round(32 * density));
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> codeEditor.insertTextAtCursor(pair[1]));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEnd);
            symbolBarContainer.addView(chip, lp);
        }
    }

    private void addSymbolActionChip(
            String label,
            String contentDesc,
            Typeface mono,
            int hPad,
            int vPad,
            int marginEnd,
            float density,
            View.OnClickListener clickListener) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTypeface(mono, Typeface.BOLD);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        chip.setTextColor(ContextCompat.getColor(this, R.color.sandrix_emerald));
        chip.setBackgroundResource(R.drawable.bg_pill_surface);
        chip.setPadding(hPad, vPad, hPad, vPad);
        chip.setMinHeight(Math.round(32 * density));
        chip.setGravity(Gravity.CENTER);
        chip.setContentDescription(contentDesc);
        chip.setOnClickListener(clickListener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(marginEnd);
        symbolBarContainer.addView(chip, lp);
    }

    private void showBuildVariantMenu() {
        IdeUiState snap = ideViewModel.getCurrentState();
        File activeProject = snap.activeProject;
        if (activeProject == null) {
            Snackbar.make(btnRunBuild, "Open a project before building.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        PopupMenu menu = new PopupMenu(this, btnRunBuild);
        menu.getMenu().add(0, 1, 0, "Build Debug APK (Install)");
        menu.getMenu().add(0, 2, 1, "Build Debug APK (APK Only)");
        menu.getMenu().add(0, 3, 2, "Build Debug APK (R8 Optimized)");
        menu.getMenu().add(0, 4, 3, "Clean Debug Build");
        menu.getMenu().add(0, 5, 4, "Build Release APK (Signed)");
        menu.getMenu().add(0, 6, 5, "Build Release APK (R8 + Shrink)");
        menu.getMenu().add(0, 7, 6, "Build Release AAB (.aab Signed)");
        menu.getMenu().add(0, 8, 7, "Build Debug AAB (.aab)");
        if (snap.lastBuiltAab != null && snap.lastBuiltAab.exists()) {
            menu.getMenu().add(0, 9, 8, "Share / Export " + snap.lastBuiltAab.getName());
            menu.getMenu().add(0, 10, 9, "Analyze " + snap.lastBuiltAab.getName());
        }
        if (snap.lastMappingFile != null && snap.lastMappingFile.exists()) {
            menu.getMenu().add(0, 11, 10, "View R8 mapping.txt...");
        }
        menu.getMenu().add(0, 12, 11, "Start Local Step Debugger");
        menu.getMenu().add(0, 13, 12, "Clean Release Build");
        menu.getMenu().add(0, 14, 13, "Release Signing Manager...");
        menu.getMenu().add(0, 15, 14, "Dependency Manager...");
        menu.getMenu().add(0, 16, 15, "Build Cache & Variants...");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                ideViewModel.runBuild(true, false, false);
                return true;
            }
            if (id == 2) {
                ideViewModel.runBuild(false, false, false);
                return true;
            }
            if (id == 3) {
                ideViewModel.runR8OptimizedBuild(false, true, false);
                return true;
            }
            if (id == 4) {
                ideViewModel.runBuild(true, false, true);
                return true;
            }
            if (id == 5 || id == 6 || id == 13) {
                boolean r8 = (id == 6);
                boolean clean = (id == 13);
                if (KeystoreGenerator.loadSigningConfig(activeProject).configured) {
                    if (r8) {
                        ideViewModel.runR8OptimizedBuild(true, false, false);
                    } else {
                        ideViewModel.runBuild(false, true, clean);
                    }
                } else {
                    Snackbar.make(btnRunBuild, "Configure release signing first.", Snackbar.LENGTH_SHORT).show();
                    openReleaseSigningManager(clean);
                }
                return true;
            }
            if (id == 7) {
                if (KeystoreGenerator.loadSigningConfig(activeProject).configured) {
                    ideViewModel.runBuildAab(true, true, false);
                } else {
                    Snackbar.make(btnRunBuild, "Configure release signing for Release AAB.", Snackbar.LENGTH_SHORT).show();
                    openReleaseSigningManager(false);
                }
                return true;
            }
            if (id == 8) {
                ideViewModel.runBuildAab(false, false, false);
                return true;
            }
            if (id == 9) {
                ideViewModel.shareLastAab(this);
                return true;
            }
            if (id == 10) {
                openApkAnalyzer(snap.lastBuiltAab);
                return true;
            }
            if (id == 11) {
                IdeDialogs.showMappingFileDialog(this, snap.lastMappingFile);
                return true;
            }
            if (id == 12) {
                ideViewModel.startLocalDebug();
                return true;
            }
            if (id == 14) {
                openReleaseSigningManager(false);
                return true;
            }
            if (id == 15) {
                openDependencyManager();
                return true;
            }
            if (id == 16) {
                openToolchainStatusDialog();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void openToolchainStatusDialog() {
        ideViewModel.inspectToolchain();
        IdeUiState snap = ideViewModel.getCurrentState();
        IdeDialogs.showToolchainStatusDialog(
                this,
                snap,
                () -> toolchainZipPicker.launch(
                        new String[]{"application/zip", "application/x-zip-compressed"}),
                () -> ideViewModel.installLastApk(),
                () -> ideViewModel.clearEngineDexCache(),
                () -> ideViewModel.cleanProjectBuildCache());
    }

    private void setupListeners() {
        findViewById(R.id.btn_open_drawer).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.btn_empty_open_explorer).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));

        View.OnClickListener openToolchain = v -> openToolchainStatusDialog();
        findViewById(R.id.project_header_touch).setOnClickListener(openToolchain);
        findViewById(R.id.btn_toolchain).setOnClickListener(openToolchain);

        btnSave.setOnClickListener(v -> ideViewModel.saveActiveFile());
        findViewById(R.id.btn_sync_xml).setOnClickListener(v -> ideViewModel.syncManifestAndXml());
        btnInstallApk.setOnClickListener(v -> ideViewModel.installLastApk());
        btnRunBuild.setOnClickListener(v -> showBuildVariantMenu());

        btnModeCode.setOnClickListener(v -> ideViewModel.setViewMode(EditorViewMode.CODE));
        btnModeSplit.setOnClickListener(v -> ideViewModel.setViewMode(EditorViewMode.SPLIT));
        btnModePreview.setOnClickListener(v -> ideViewModel.setViewMode(EditorViewMode.PREVIEW));
        btnToggleEditorTheme.setOnClickListener(v -> ideViewModel.toggleEditorTheme());
        btnTogglePreviewCanvas.setOnClickListener(v -> ideViewModel.togglePreviewCanvasTheme());
        btnInspectLayout.setOnClickListener(v -> openLayoutInspector(-1));
        layoutPreviewer.setOnInspectNodeTapListener(node -> openLayoutInspector(node.index));

        codeEditor.setOnCodeChangedListener(newCode -> ideViewModel.updateActiveCode(newCode));
        codeEditor.setContextCompletionProvider((lang, code, cursor, manual) ->
                ideViewModel.getContextSuggestions(lang, code, cursor));
        codeEditor.setOnBreakpointToggleListener(new CodeEditorView.OnBreakpointToggleListener() {
            @Override
            public void onToggleBreakpoint(int lineNumber) {
                ideViewModel.toggleBreakpoint(lineNumber);
            }

            @Override
            public void onBreakpointLongPress(int lineNumber) {
                IdeUiState snap = ideViewModel.getCurrentState();
                EditorTab tab = snap.getActiveTab();
                if (tab == null) return;
                String existingCond = "";
                for (DebugModels.BreakpointSpec bp : snap.breakpointSpecs) {
                    if (bp.lineNumber == lineNumber && bp.filePath.equals(tab.file.getAbsolutePath())) {
                        existingCond = bp.condition;
                        break;
                    }
                }
                IdeDialogs.showBreakpointConditionDialog(MainActivity.this, lineNumber, existingCond, cond ->
                        ideViewModel.setBreakpointCondition(
                                tab.file.getAbsolutePath(), tab.file.getName(), lineNumber, cond));
            }
        });

        btnDebugResumePause.setOnClickListener(v -> {
            DebugModels.DebugSessionState dbg = ideViewModel.getCurrentState().debugSessionState;
            if (dbg != null && dbg.mode == DebugModels.Mode.PAUSED) {
                ideViewModel.debugResume();
            } else {
                ideViewModel.debugPause();
            }
        });
        btnDebugStepOver.setOnClickListener(v -> ideViewModel.debugStepOver());
        btnDebugStepInto.setOnClickListener(v -> ideViewModel.debugStepInto());
        btnDebugStepOut.setOnClickListener(v -> ideViewModel.debugStepOut());
        btnDebugInspect.setOnClickListener(v -> openDebugInspector());
        btnDebugStop.setOnClickListener(v -> ideViewModel.debugStop());

        workspaceSplitContainer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int widthDp = Math.round((r - l) / getResources().getDisplayMetrics().density);
            boolean isWide = widthDp >= 600;
            if (isWide != lastIsWide) {
                lastIsWide = isWide;
                applySplitLayoutOrientation(ideViewModel.getCurrentState().viewMode, isWide);
            }
        });

        // Console listeners
        tabConsoleBuild.setOnClickListener(v -> ideViewModel.setConsoleTab(ConsoleTab.BUILD));
        tabConsoleProblems.setOnClickListener(v -> ideViewModel.setConsoleTab(ConsoleTab.PROBLEMS));
        tabConsoleLogcat.setOnClickListener(v -> ideViewModel.setConsoleTab(ConsoleTab.LOGCAT));
        btnConsoleToggle.setOnClickListener(v -> ideViewModel.toggleConsoleExpanded());
        btnOpenDebugger.setOnClickListener(v -> openDebugInspector());
        btnLogcatPause.setOnClickListener(v -> ideViewModel.toggleLogcatPause());
        btnLogcatExport.setOnClickListener(v -> ideViewModel.exportLogcatLogs(lastFilteredLogcat));
        etLogcatSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                ideViewModel.setLogcatSearchQuery(s != null ? s.toString() : "");
            }
        });
        btnConsoleClear.setOnClickListener(v -> {
            if (ideViewModel.getCurrentState().activeConsoleTab == ConsoleTab.LOGCAT) {
                ideViewModel.clearLogcat();
            } else {
                ideViewModel.clearBuildLogs();
            }
        });

        cgLogcatFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (suppressLogcatChipCallback || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_log_info) {
                ideViewModel.setLogcatFilter("I");
            } else if (id == R.id.chip_log_warn) {
                ideViewModel.setLogcatFilter("W");
            } else if (id == R.id.chip_log_error) {
                ideViewModel.setLogcatFilter("E");
            } else {
                ideViewModel.setLogcatFilter("ALL");
            }
        });

        // Drawer listeners
        findViewById(R.id.btn_drawer_new_file).setOnClickListener(v ->
                IdeDialogs.showNewFileDialog(this, relPath -> {
                    ideViewModel.createNewFile(relPath);
                    drawerLayout.closeDrawer(GravityCompat.START);
                }));
        findViewById(R.id.btn_drawer_new_project).setOnClickListener(v ->
                IdeDialogs.showNewProjectDialog(this, (name, pkg, template) -> {
                    ideViewModel.createNewProject(name, pkg, template);
                    drawerLayout.closeDrawer(GravityCompat.START);
                }));
        btnGrantStorage.setOnClickListener(v -> requestWorkspaceStoragePermission());
        findViewById(R.id.btn_drawer_sync_xml).setOnClickListener(v -> {
            ideViewModel.syncManifestAndXml();
            drawerLayout.closeDrawer(GravityCompat.START);
        });
        findViewById(R.id.btn_drawer_manifest_editor).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openManifestEditor();
        });
        findViewById(R.id.btn_drawer_apk_analyzer).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            IdeUiState snap = ideViewModel.getCurrentState();
            File target = (snap.lastBuiltApk != null && snap.lastBuiltApk.exists())
                    ? snap.lastBuiltApk
                    : snap.lastBuiltAab;
            openApkAnalyzer(target);
        });
        findViewById(R.id.btn_drawer_debugger).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openDebugInspector();
        });
        findViewById(R.id.btn_drawer_layout_inspector).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openLayoutInspector(-1);
        });
        findViewById(R.id.btn_drawer_deps).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openDependencyManager();
        });
        findViewById(R.id.btn_drawer_signing).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            openReleaseSigningManager(false);
        });

        View.OnClickListener openResourceManager = v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            activeResourceDialog = ResourceManagerDialog.show(
                    this,
                    ideViewModel.getCurrentState(),
                    createResourceManagerListener());
        };
        btnResources.setOnClickListener(openResourceManager);
        findViewById(R.id.btn_drawer_resources).setOnClickListener(openResourceManager);

        View.OnClickListener openGitPanel = v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            ideViewModel.refreshGitStatus();
            activeGitPanel = GitDialogs.showGitPanel(
                    this,
                    ideViewModel.getCurrentState(),
                    createGitActionsListener());
        };
        btnGit.setOnClickListener(openGitPanel);
        findViewById(R.id.btn_drawer_git).setOnClickListener(openGitPanel);
        findViewById(R.id.btn_drawer_git_clone).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            IdeUiState snap = ideViewModel.getCurrentState();
            GitDialogs.showCloneDialog(
                    this,
                    snap.gitUsername,
                    ideViewModel.getSavedGitToken(),
                    createGitActionsListener());
        });
    }

    private void openLayoutInspector(int initialNodeIndex) {
        layoutPreviewer.setInspectMode(true);
        IdeUiState snap = ideViewModel.getCurrentState();
        LayoutInspectorDialog.show(
                this,
                snap.previewLayoutName,
                snap.previewXmlContent,
                layoutPreviewer.getInspectedNodes(),
                initialNodeIndex,
                new LayoutInspectorDialog.Listener() {
                    @Override
                    public void onSelectNodeInPreview(int nodeIndex) {
                        layoutPreviewer.selectInspectedNode(nodeIndex);
                    }

                    @Override
                    public void onJumpToXmlLine(int lineNumber) {
                        ideViewModel.openPreviewLayoutAtLine(lineNumber);
                    }

                    @Override
                    public void onApplyUpdatedLayoutXml(String updatedXml) {
                        ideViewModel.applyUpdatedPreviewLayoutXml(updatedXml);
                    }
                });
    }

    private void openManifestEditor() {
        IdeUiState snap = ideViewModel.getCurrentState();
        if (snap.activeProject == null) {
            Snackbar.make(mainWorkspaceRoot, "Open a project first.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        ManifestEditorDialog.show(
                this,
                ideViewModel.readActiveManifestXml(),
                updatedXml -> ideViewModel.saveManifestXml(updatedXml));
    }

    private void openApkAnalyzer(File apkFile) {
        ApkAnalyzerDialog.show(
                this,
                apkFile,
                () -> externalApkPicker.launch(new String[]{
                        "application/vnd.android.package-archive", "application/octet-stream", "*/*"}));
    }

    private void analyzeExternalApkUri(Uri uri) {
        try {
            File tmp = new File(getCacheDir(), "inspected_external.apk");
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
            }
            openApkAnalyzer(tmp);
        } catch (Exception e) {
            Snackbar.make(mainWorkspaceRoot, "Failed to read APK: " + e.getMessage(), Snackbar.LENGTH_SHORT).show();
        }
    }

    private void openDebugInspector() {
        activeDebugDialog = DebugInspectorDialog.show(
                this,
                ideViewModel.getCurrentState(),
                new DebugInspectorDialog.Listener() {
                    @Override
                    public void onStartLocalDebug() {
                        ideViewModel.startLocalDebug();
                    }

                    @Override
                    public void onConnectJdwp(String host, int port) {
                        ideViewModel.connectJdwp(host, port);
                    }

                    @Override
                    public void onStepOver() {
                        ideViewModel.debugStepOver();
                    }

                    @Override
                    public void onStepInto() {
                        ideViewModel.debugStepInto();
                    }

                    @Override
                    public void onStepOut() {
                        ideViewModel.debugStepOut();
                    }

                    @Override
                    public void onResume() {
                        ideViewModel.debugResume();
                    }

                    @Override
                    public void onPause() {
                        ideViewModel.debugPause();
                    }

                    @Override
                    public void onStop() {
                        ideViewModel.debugStop();
                    }

                    @Override
                    public void onSelectStackFrame(int frameIndex) {
                        ideViewModel.selectDebugFrame(frameIndex);
                    }

                    @Override
                    public void onAddWatch(String expression) {
                        ideViewModel.addDebugWatch(expression);
                    }

                    @Override
                    public void onRemoveWatch(String expression) {
                        ideViewModel.removeDebugWatch(expression);
                    }

                    @Override
                    public DebugModels.WatchItem onEvaluateExpression(String expression) {
                        return ideViewModel.evaluateDebugExpression(expression);
                    }

                    @Override
                    public boolean onSetVariableValue(String varName, String valueExpr) {
                        return ideViewModel.setDebugVariableValue(varName, valueExpr);
                    }

                    @Override
                    public void onJumpToBreakpoint(File file, int lineNumber) {
                        ideViewModel.openFile(file, lineNumber);
                    }

                    @Override
                    public void onSetBreakpointCondition(String filePath, String fileName, int lineNumber, String condition) {
                        ideViewModel.setBreakpointCondition(filePath, fileName, lineNumber, condition);
                    }

                    @Override
                    public void onToggleBreakpointEnabled(String filePath, int lineNumber, boolean enabled) {
                        ideViewModel.toggleBreakpointEnabled(filePath, lineNumber, enabled);
                    }

                    @Override
                    public void onRemoveBreakpoint(String filePath, int lineNumber) {
                        ideViewModel.removeBreakpoint(filePath, lineNumber);
                    }

                    @Override
                    public void onClearBreakpoints() {
                        ideViewModel.clearAllBreakpoints();
                    }

                    @Override
                    public void onJumpToLogcatTab() {
                        ideViewModel.setConsoleTab(ConsoleTab.LOGCAT);
                    }
                });
    }

    private void openDependencyManager() {
        IdeUiState snap = ideViewModel.getCurrentState();
        if (snap.activeProject == null) {
            Snackbar.make(mainWorkspaceRoot, "Open a project first.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        DependencyManagerDialog.show(
                this,
                snap,
                new DependencyManagerDialog.Listener() {
                    @Override
                    public void onAddOrUpdateDependency(String configuration, String coordinate) {
                        ideViewModel.addOrUpdateDependency(configuration, coordinate);
                    }

                    @Override
                    public void onRemoveDependency(String coordinate) {
                        ideViewModel.removeDependency(coordinate);
                    }
                });
    }

    private void openReleaseSigningManager(boolean cleanReleaseBuild) {
        File projectDir = ideViewModel.getCurrentState().activeProject;
        if (projectDir == null) {
            Snackbar.make(mainWorkspaceRoot, "Open a project first.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        SigningManagerDialog.show(
                this,
                projectDir,
                (bannerMessage, triggerReleaseBuildNow) -> {
                    if (bannerMessage != null && !bannerMessage.isEmpty()) {
                        Snackbar.make(mainWorkspaceRoot, bannerMessage, Snackbar.LENGTH_SHORT).show();
                    }
                    if (triggerReleaseBuildNow) {
                        ideViewModel.runBuild(false, true, cleanReleaseBuild);
                    }
                });
    }

    private ResourceManagerDialog.Listener createResourceManagerListener() {
        return new ResourceManagerDialog.Listener() {
            @Override
            public void onSaveString(String key, String value) {
                ideViewModel.saveStringResource(key, value);
            }

            @Override
            public void onDeleteString(String key) {
                ideViewModel.deleteStringResource(key);
            }

            @Override
            public void onSaveColor(String key, String hexColor) {
                ideViewModel.saveColorResource(key, hexColor);
            }

            @Override
            public void onDeleteColor(String key) {
                ideViewModel.deleteColorResource(key);
            }

            @Override
            public void onSaveDimen(String key, String value) {
                ideViewModel.saveDimenResource(key, value);
            }

            @Override
            public void onDeleteDimen(String key) {
                ideViewModel.deleteDimenResource(key);
            }

            @Override
            public void onOpenOrCreateStylesXml() {
                ideViewModel.openOrCreateStylesXml();
            }

            @Override
            public void onFindUsages(String resourceSymbol) {
                if (activeResourceDialog != null) {
                    activeResourceDialog.dismissSafe();
                }
                ideViewModel.findReferencesForSymbol(resourceSymbol, (sym, refs) ->
                        IdeDialogs.showFindReferencesDialog(MainActivity.this, sym, refs, ref ->
                                ideViewModel.openFile(ref.file, ref.lineNumber)));
            }

            @Override
            public void onCreateXmlDrawable(String name, String templateType, boolean openInEditor) {
                ideViewModel.createDrawableTemplate(name, templateType, openInEditor);
            }

            @Override
            public void onRequestImportDrawableImage() {
                drawableImagePicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            }

            @Override
            public void onRequestImportAssetFile() {
                projectAssetPicker.launch(new String[]{"*/*"});
            }

            @Override
            public void onOpenFile(File file) {
                if (ideViewModel.getCurrentState().viewMode == EditorViewMode.PREVIEW) {
                    ideViewModel.setViewMode(EditorViewMode.SPLIT);
                }
                ideViewModel.openFile(file);
            }

            @Override
            public void onDeleteFile(File file) {
                ideViewModel.deleteResourceFile(file);
            }
        };
    }

    private GitDialogs.GitActionsListener createGitActionsListener() {
        return new GitDialogs.GitActionsListener() {
            @Override public void onInit(String authorName, String authorEmail) {
                ideViewModel.gitInit(authorName, authorEmail);
            }
            @Override public void onCommitAll(String message, String authorName, String authorEmail) {
                ideViewModel.gitCommitAll(message, authorName, authorEmail);
            }
            @Override public void onCommitStaged(String message, String authorName, String authorEmail) {
                ideViewModel.gitCommitStaged(message, authorName, authorEmail);
            }
            @Override public void onStageFile(String relativePath) {
                ideViewModel.gitStageFile(relativePath);
            }
            @Override public void onUnstageFile(String relativePath) {
                ideViewModel.gitUnstageFile(relativePath);
            }
            @Override public void onDiscardFile(String relativePath) {
                ideViewModel.gitDiscardFile(relativePath);
            }
            @Override public void onResolveConflict(String relativePath, boolean keepOurs) {
                ideViewModel.gitResolveConflict(relativePath, keepOurs);
            }
            @Override public void onCreateBranch(String branchName, boolean checkoutAfter) {
                ideViewModel.gitCreateBranch(branchName, checkoutAfter);
            }
            @Override public void onCheckoutBranch(String branchName) {
                ideViewModel.gitCheckoutBranch(branchName);
            }
            @Override public void onMergeBranch(String sourceBranch) {
                ideViewModel.gitMergeBranch(sourceBranch);
            }
            @Override public void onStashSave(String message) {
                ideViewModel.gitStashSave(message);
            }
            @Override public void onStashApplyOrPop(int index, boolean dropAfter) {
                ideViewModel.gitStashApplyOrPop(index, dropAfter);
            }
            @Override public void onCreateTag(String tagName, String message) {
                ideViewModel.gitCreateTag(tagName, message);
            }
            @Override public void onSetRemote(String remoteUrl) {
                ideViewModel.gitSetRemote(remoteUrl);
            }
            @Override public void onPull(String username, String token, boolean rememberToken) {
                ideViewModel.gitPull(username, token, rememberToken);
            }
            @Override public void onPush(String username, String token, boolean rememberToken) {
                ideViewModel.gitPush(username, token, rememberToken);
            }
            @Override public void onClone(String remoteUrl, String folderName, String username, String token, boolean rememberToken) {
                ideViewModel.gitClone(remoteUrl, folderName, username, token, rememberToken);
            }
            @Override public void onRequestDiff(String relativePath) {
                ideViewModel.loadGitDiff(relativePath);
            }
            @Override public void onRequestCommitDiff(String shortHash) {
                ideViewModel.loadCommitDiff(shortHash);
            }
            @Override public void onClearDiff() {
                ideViewModel.clearGitDiff();
            }
            @Override public String getSavedToken() {
                return ideViewModel.getSavedGitToken();
            }
        };
    }

    private void setupBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void renderState(IdeUiState state) {
        if (state == null) return;

        // 1. Top Studio Bar
        String projectTitle = state.activeProject != null ? state.activeProject.getName() : "Sandrix Studio";
        tvProjectName.setText(projectTitle);
        tvEmptyProjectName.setText(projectTitle);
        tvDrawerProjectName.setText(state.activeProject != null ? state.activeProject.getName() : "No project selected");

        if (state.parsedConfig != null) {
            tvProjectSubtitle.setText("SDK " + state.parsedConfig.compileSdk
                    + " • Java " + state.parsedConfig.javaVersion
                    + " • v" + state.parsedConfig.versionName);
        } else {
            tvProjectSubtitle.setText("Sandrix IDE");
        }

        EditorTab activeTab = state.getActiveTab();
        btnSave.setEnabled(activeTab != null);
        boolean dirty = activeTab != null && activeTab.isDirty;
        btnSave.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                dirty ? R.color.sandrix_amber : R.color.sandrix_text_secondary)));

        boolean hasApk = state.lastBuiltApk != null && state.lastBuiltApk.exists();
        btnInstallApk.setVisibility(hasApk ? View.VISIBLE : View.GONE);

        btnRunBuild.setEnabled(!state.isBuilding && state.activeProject != null);
        btnRunBuild.setText(state.isBuilding ? "Building" : "Run");

        // 2. Tabs & View Mode
        tabsAdapter.submit(state.openTabs, state.activeTabIndex);
        updateModeButtonStyles(state.viewMode);
        btnToggleEditorTheme.setImageResource(
                state.darkEditorTheme ? R.drawable.ic_theme_light : R.drawable.ic_theme_dark);

        // 3. Workspace / Editor / Preview
        boolean isEmptyWorkspace = state.openTabs.isEmpty() && state.viewMode != EditorViewMode.PREVIEW;
        emptyWorkspacePane.setVisibility(isEmptyWorkspace ? View.VISIBLE : View.GONE);
        workspaceSplitContainer.setVisibility(isEmptyWorkspace ? View.GONE : View.VISIBLE);

        if (!isEmptyWorkspace) {
            applySplitLayoutOrientation(state.viewMode, lastIsWide);
        }

        DebugModels.DebugSessionState dbgState = state.debugSessionState;
        if (activeTab != null) {
            codeEditor.setDarkMode(state.darkEditorTheme);
            codeEditor.setLanguage(CodeEditorView.Language.fromFileName(activeTab.file.getName()));
            codeEditor.setCode(activeTab.content);
            codeEditor.setErrorLines(activeTab.errorLines);
            codeEditor.setWarningLines(activeTab.warningLines);

            String activePath = activeTab.file.getAbsolutePath();
            Set<Integer> condLines = new HashSet<>();
            for (DebugModels.BreakpointSpec bp : state.breakpointSpecs) {
                if (bp.hasCondition() && bp.filePath.equals(activePath)) {
                    condLines.add(bp.lineNumber);
                }
            }
            codeEditor.setBreakpointLines(state.breakpointsByFile.get(activePath), condLines);

            int dbgLine = (dbgState != null && dbgState.mode == DebugModels.Mode.PAUSED
                    && activePath.equals(dbgState.activeFilePath))
                    ? dbgState.activeLine : -1;
            codeEditor.setActiveDebugLine(dbgLine);

            if (activeTab.pendingJumpLine != null) {
                codeEditor.jumpToLine(activeTab.pendingJumpLine);
                ideViewModel.consumePendingJumpLine(state.activeTabIndex);
            }
        } else {
            codeEditor.setActiveDebugLine(-1);
        }

        renderDebuggerBar(dbgState);

        String sugSig = buildDynamicSuggestionSignature(state);
        if (!sugSig.equals(lastDynamicSuggestionSignature)) {
            lastDynamicSuggestionSignature = sugSig;
            codeEditor.setDynamicSuggestions(buildDynamicSuggestions(state));
        }

        tvPreviewTitle.setText("Preview • " + state.previewLayoutName);
        btnTogglePreviewCanvas.setText(state.previewLightCanvas ? "Light Canvas" : "Dark Canvas");
        boolean previewDirty = !Objects.equals(lastPreviewXml, state.previewXmlContent)
                || !Objects.equals(lastPreviewName, state.previewLayoutName)
                || lastPreviewLight != state.previewLightCanvas
                || !Objects.equals(lastStringRes, state.stringResources)
                || !Objects.equals(lastColorRes, state.colorResources);
        if (previewDirty) {
            lastPreviewXml = state.previewXmlContent;
            lastPreviewName = state.previewLayoutName;
            lastPreviewLight = state.previewLightCanvas;
            lastStringRes = state.stringResources;
            lastColorRes = state.colorResources;
            layoutPreviewer.setPreviewLightCanvas(state.previewLightCanvas);
            layoutPreviewer.setResources(state.stringResources, state.colorResources);
            layoutPreviewer.renderXml(state.previewXmlContent, state.previewLayoutName);
        }

        // 4. Console
        renderConsole(state);

        // 5. Drawer, Git Status & Active Dialogs
        renderGitIndicators(state);
        renderDrawer(state);
        if (activeGitPanel != null && activeGitPanel.isShowing()) {
            activeGitPanel.update(state);
        }
        if (activeResourceDialog != null && activeResourceDialog.isShowing()) {
            activeResourceDialog.update(state);
        }
        if (activeDebugDialog != null && activeDebugDialog.isShowing()) {
            activeDebugDialog.update(state);
        }

        // 6. Status Snackbar
        if (state.statusBannerMessage != null) {
            Snackbar.make(mainWorkspaceRoot, state.statusBannerMessage, Snackbar.LENGTH_SHORT).show();
            ideViewModel.dismissBanner();
        }
    }

    private void renderDebuggerBar(DebugModels.DebugSessionState dbgState) {
        if (dbgState == null || !dbgState.isActive()) {
            debugControlBar.setVisibility(View.GONE);
            return;
        }
        debugControlBar.setVisibility(View.VISIBLE);
        boolean paused = dbgState.mode == DebugModels.Mode.PAUSED;
        int accent = ContextCompat.getColor(this,
                paused ? R.color.sandrix_amber : R.color.sandrix_emerald);
        int cyan = ContextCompat.getColor(this, R.color.sandrix_cyan);
        int disabled = ContextCompat.getColor(this, R.color.sandrix_text_secondary);

        ivDebugIndicator.setColorFilter(accent);
        tvDebugStatus.setTextColor(accent);
        if (paused) {
            tvDebugStatus.setText(dbgState.activeFileName + ":" + dbgState.activeLine
                    + " • " + dbgState.suspendReason);
        } else {
            tvDebugStatus.setText("Debugging (" + dbgState.backend.name() + ") • " + dbgState.suspendReason);
        }

        btnDebugResumePause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        btnDebugResumePause.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, paused ? R.color.sandrix_emerald : R.color.sandrix_amber)));

        btnDebugStepOver.setEnabled(paused);
        btnDebugStepInto.setEnabled(paused);
        btnDebugStepOut.setEnabled(paused);
        btnDebugStepOver.setImageTintList(ColorStateList.valueOf(paused ? cyan : disabled));
        btnDebugStepInto.setImageTintList(ColorStateList.valueOf(paused ? cyan : disabled));
        btnDebugStepOut.setImageTintList(ColorStateList.valueOf(paused ? cyan : disabled));
    }

    private void renderGitIndicators(IdeUiState state) {
        SandrixGitManager.GitRepoStatus git = state.gitStatus;
        int tint;
        if (!git.isRepo) {
            tint = ContextCompat.getColor(this, R.color.sandrix_text_secondary);
            tvDrawerGitStatus.setText(R.string.git_not_initialized);
            tvDrawerGitStatus.setTextColor(tint);
        } else if (git.changes.isEmpty()) {
            tint = ContextCompat.getColor(this, R.color.sandrix_emerald);
            tvDrawerGitStatus.setText(git.currentBranch + " • clean");
            tvDrawerGitStatus.setTextColor(tint);
        } else {
            tint = ContextCompat.getColor(this, R.color.sandrix_amber);
            tvDrawerGitStatus.setText(git.currentBranch + " • " + git.changes.size() + " changed");
            tvDrawerGitStatus.setTextColor(tint);
        }
        btnGit.setImageTintList(ColorStateList.valueOf(tint));
        ivDrawerGit.setColorFilter(tint);
    }

    private void updateModeButtonStyles(EditorViewMode mode) {
        int cyan = ContextCompat.getColor(this, R.color.sandrix_cyan);
        int muted = ContextCompat.getColor(this, R.color.sandrix_text_secondary);

        btnModeCode.setBackgroundResource(mode == EditorViewMode.CODE
                ? R.drawable.bg_pill_selected : android.R.color.transparent);
        btnModeCode.setImageTintList(ColorStateList.valueOf(mode == EditorViewMode.CODE ? cyan : muted));

        btnModeSplit.setBackgroundResource(mode == EditorViewMode.SPLIT
                ? R.drawable.bg_pill_selected : android.R.color.transparent);
        btnModeSplit.setImageTintList(ColorStateList.valueOf(mode == EditorViewMode.SPLIT ? cyan : muted));

        btnModePreview.setBackgroundResource(mode == EditorViewMode.PREVIEW
                ? R.drawable.bg_pill_selected : android.R.color.transparent);
        btnModePreview.setImageTintList(ColorStateList.valueOf(mode == EditorViewMode.PREVIEW ? cyan : muted));
    }

    private void applySplitLayoutOrientation(EditorViewMode mode, boolean isWide) {
        workspaceSplitContainer.setOrientation(isWide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        if (mode == EditorViewMode.CODE) {
            editorPane.setVisibility(View.VISIBLE);
            splitDivider.setVisibility(View.GONE);
            previewPane.setVisibility(View.GONE);
            setPaneWeight(editorPane, 1f, isWide);
        } else if (mode == EditorViewMode.PREVIEW) {
            editorPane.setVisibility(View.GONE);
            splitDivider.setVisibility(View.GONE);
            previewPane.setVisibility(View.VISIBLE);
            setPaneWeight(previewPane, 1f, isWide);
        } else {
            editorPane.setVisibility(View.VISIBLE);
            splitDivider.setVisibility(View.VISIBLE);
            previewPane.setVisibility(View.VISIBLE);
            float density = getResources().getDisplayMetrics().density;
            int dividerPx = Math.max(1, Math.round(1 * density));
            LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) splitDivider.getLayoutParams();
            divLp.width = isWide ? dividerPx : ViewGroup.LayoutParams.MATCH_PARENT;
            divLp.height = isWide ? ViewGroup.LayoutParams.MATCH_PARENT : dividerPx;
            splitDivider.setLayoutParams(divLp);

            setPaneWeight(editorPane, isWide ? 0.55f : 0.52f, isWide);
            setPaneWeight(previewPane, isWide ? 0.45f : 0.48f, isWide);
        }
    }

    private static void setPaneWeight(View pane, float weight, boolean isWide) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pane.getLayoutParams();
        if (isWide) {
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = 0;
        }
        lp.weight = weight;
        pane.setLayoutParams(lp);
    }

    private void renderConsole(IdeUiState state) {
        buildProgress.setVisibility(state.isBuilding ? View.VISIBLE : View.GONE);

        if (state.buildStage != null) {
            tvBuildStage.setVisibility(View.VISIBLE);
            tvBuildStage.setText(state.buildStage.label);
        } else {
            tvBuildStage.setVisibility(View.GONE);
        }

        int cyan = ContextCompat.getColor(this, R.color.sandrix_cyan);
        int muted = ContextCompat.getColor(this, R.color.sandrix_text_secondary);
        int amber = ContextCompat.getColor(this, R.color.sandrix_amber);

        styleConsoleTab(tabConsoleBuild, ivConsoleBuild, tvConsoleBuild,
                state.activeConsoleTab == ConsoleTab.BUILD, cyan, muted);
        styleConsoleTab(tabConsoleProblems, ivConsoleProblems, tvConsoleProblems,
                state.activeConsoleTab == ConsoleTab.PROBLEMS, cyan, muted);
        styleConsoleTab(tabConsoleLogcat, ivConsoleLogcat, tvConsoleLogcat,
                state.activeConsoleTab == ConsoleTab.LOGCAT, cyan, muted);

        if (state.diagnostics.isEmpty()) {
            tvProblemsBadge.setVisibility(View.GONE);
        } else {
            tvProblemsBadge.setVisibility(View.VISIBLE);
            tvProblemsBadge.setText(String.valueOf(state.diagnostics.size()));
        }

        consoleBody.setVisibility(state.consoleExpanded ? View.VISIBLE : View.GONE);
        btnConsoleToggle.setImageResource(
                state.consoleExpanded ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
        btnConsoleToggle.setContentDescription(
                state.consoleExpanded ? "Collapse console" : "Expand console");

        btnLogcatPause.setVisibility(
                state.consoleExpanded && state.activeConsoleTab == ConsoleTab.LOGCAT
                        ? View.VISIBLE : View.GONE);
        btnLogcatPause.setImageResource(state.logcatPaused ? R.drawable.ic_play : R.drawable.ic_pause);
        btnLogcatPause.setImageTintList(ColorStateList.valueOf(state.logcatPaused ? amber : muted));

        btnConsoleClear.setVisibility(
                state.consoleExpanded && state.activeConsoleTab != ConsoleTab.PROBLEMS
                        ? View.VISIBLE : View.GONE);

        consolePageBuild.setVisibility(state.activeConsoleTab == ConsoleTab.BUILD ? View.VISIBLE : View.GONE);
        consolePageProblems.setVisibility(state.activeConsoleTab == ConsoleTab.PROBLEMS ? View.VISIBLE : View.GONE);
        consolePageLogcat.setVisibility(state.activeConsoleTab == ConsoleTab.LOGCAT ? View.VISIBLE : View.GONE);

        // Build logs list
        tvEmptyBuildLogs.setVisibility(state.buildLogs.isEmpty() ? View.VISIBLE : View.GONE);
        buildLogsAdapter.submit(state.buildLogs);
        if (!state.buildLogs.isEmpty() && state.buildLogs.size() != lastBuildLogsSize) {
            lastBuildLogsSize = state.buildLogs.size();
            rvBuildLogs.scrollToPosition(state.buildLogs.size() - 1);
        } else if (state.buildLogs.isEmpty()) {
            lastBuildLogsSize = 0;
        }

        // Problems list
        tvEmptyProblems.setVisibility(state.diagnostics.isEmpty() ? View.VISIBLE : View.GONE);
        problemsAdapter.submit(state.diagnostics);

        // Logcat filter chips + search query + list
        syncLogcatFilterChip(state.logcatLevelFilter);
        String query = state.logcatSearchQuery != null
                ? state.logcatSearchQuery.trim().toLowerCase(Locale.ROOT) : "";
        List<LogcatStreamReader.LogEntry> filtered =
                filterLogcatCached(state.logcatEntries, state.logcatLevelFilter, query);
        lastFilteredLogcat = filtered;
        logcatAdapter.submit(filtered);
        if (!state.logcatPaused && !filtered.isEmpty() && filtered.size() != lastLogcatSize) {
            lastLogcatSize = filtered.size();
            rvLogcat.scrollToPosition(filtered.size() - 1);
        }
    }

    /**
     * Returns the logcat entries matching the level + search filter. Recomputed only when the
     * source buffer, filter or query actually changes, so unrelated UI state updates stay cheap.
     */
    private List<LogcatStreamReader.LogEntry> filterLogcatCached(
            List<LogcatStreamReader.LogEntry> source, String level, String query) {
        int count = source != null ? source.size() : 0;
        LogcatStreamReader.LogEntry first = count > 0 ? source.get(0) : null;
        LogcatStreamReader.LogEntry last = count > 0 ? source.get(count - 1) : null;
        String key = level + "\u0000" + query;
        if (key.equals(lastLogcatFilterKey)
                && count == lastLogcatSourceCount
                && first == lastLogcatFirst
                && last == lastLogcatLast) {
            return lastLogcatResult;
        }
        List<LogcatStreamReader.LogEntry> out = new ArrayList<>();
        if (source != null) {
            for (LogcatStreamReader.LogEntry entry : source) {
                boolean levelOk = "ALL".equals(level) || level.equals(entry.level);
                if (!levelOk) continue;
                if (!query.isEmpty()) {
                    boolean match = (entry.tag != null && entry.tag.toLowerCase(Locale.ROOT).contains(query))
                            || (entry.message != null && entry.message.toLowerCase(Locale.ROOT).contains(query))
                            || (entry.timestamp != null && entry.timestamp.toLowerCase(Locale.ROOT).contains(query));
                    if (!match) continue;
                }
                out.add(entry);
            }
        }
        lastLogcatFilterKey = key;
        lastLogcatSourceCount = count;
        lastLogcatFirst = first;
        lastLogcatLast = last;
        lastLogcatResult = out;
        return out;
    }

    private void styleConsoleTab(
            View container, ImageView icon, TextView text, boolean selected, int cyan, int muted) {
        container.setBackgroundResource(selected ? R.drawable.bg_pill_selected : android.R.color.transparent);
        icon.setColorFilter(selected ? cyan : muted);
        text.setTextColor(selected ? cyan : muted);
    }

    private void syncLogcatFilterChip(String filter) {
        int targetId;
        if ("I".equals(filter)) {
            targetId = R.id.chip_log_info;
        } else if ("W".equals(filter)) {
            targetId = R.id.chip_log_warn;
        } else if ("E".equals(filter)) {
            targetId = R.id.chip_log_error;
        } else {
            targetId = R.id.chip_log_all;
        }
        if (cgLogcatFilter.getCheckedChipId() != targetId) {
            suppressLogcatChipCallback = true;
            cgLogcatFilter.check(targetId);
            suppressLogcatChipCallback = false;
        }
    }

    private void renderDrawer(IdeUiState state) {
        int storageColor = ContextCompat.getColor(this,
                state.hasExternalStorageAccess ? R.color.sandrix_emerald : R.color.sandrix_amber);
        ivStorageStatus.setColorFilter(storageColor);
        tvStorageStatus.setText(state.hasExternalStorageAccess
                ? R.string.storage_connected
                : R.string.storage_sandbox);
        tvWorkspacePath.setText(state.workspacePath);
        btnGrantStorage.setVisibility(state.hasExternalStorageAccess ? View.GONE : View.VISIBLE);

        if (state.parsedConfig != null) {
            drawerConfigRow.setVisibility(View.VISIBLE);
            tvDrawerBuildSummary.setText("SDK " + state.parsedConfig.minSdk
                    + "-" + state.parsedConfig.targetSdk
                    + " • " + state.parsedConfig.dependencies.size() + " deps");
        } else {
            drawerConfigRow.setVisibility(View.GONE);
        }

        // Rebuild project switcher chips only when projects or active project changed
        StringBuilder keyBuilder = new StringBuilder();
        for (File p : state.projects) {
            keyBuilder.append(p.getAbsolutePath()).append('|');
        }
        String projectsKey = keyBuilder.toString();
        String activePath = state.activeProject != null ? state.activeProject.getAbsolutePath() : "";
        if (!projectsKey.equals(lastProjectsKey) || !activePath.equals(lastActiveProjectPath)) {
            lastProjectsKey = projectsKey;
            lastActiveProjectPath = activePath;
            chipGroupProjects.removeAllViews();
            for (File proj : state.projects) {
                Chip chip = new Chip(this);
                chip.setText(proj.getName());
                chip.setCheckable(true);
                chip.setChecked(proj.getAbsolutePath().equals(activePath));
                chip.setOnClickListener(v -> {
                    ideViewModel.selectProject(proj);
                    drawerLayout.closeDrawer(GravityCompat.START);
                });
                chipGroupProjects.addView(chip);
            }
        }

        EditorTab activeTab = state.getActiveTab();
        fileTreeAdapter.submit(
                state.fileTree,
                activeTab != null ? activeTab.file.getAbsolutePath() : null);
    }

    private void requestWorkspaceStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                manageAllFilesLauncher.launch(intent);
            } catch (Exception e) {
                manageAllFilesLauncher.launch(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            legacyStorageLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            });
        }
    }

    private static String buildDynamicSuggestionSignature(IdeUiState state) {
        StringBuilder sb = new StringBuilder();
        sb.append(state.stringResources.keySet()).append('|');
        sb.append(state.colorResources.keySet()).append('|');
        sb.append(state.xmlResourceIds).append('|');
        for (File f : state.drawableFiles) {
            sb.append(f.getName()).append(',');
        }
        return sb.toString();
    }

    private static List<CodeEditorView.SuggestionItem> buildDynamicSuggestions(IdeUiState state) {
        List<CodeEditorView.SuggestionItem> list = new ArrayList<>();
        for (String key : state.stringResources.keySet()) {
            list.add(new CodeEditorView.SuggestionItem("RES", "@string/" + key, "@string/" + key, 0));
            list.add(new CodeEditorView.SuggestionItem("RES", "R.string." + key, "R.string." + key, 0));
        }
        for (String key : state.colorResources.keySet()) {
            list.add(new CodeEditorView.SuggestionItem("RES", "@color/" + key, "@color/" + key, 0));
            list.add(new CodeEditorView.SuggestionItem("RES", "R.color." + key, "R.color." + key, 0));
        }
        for (File f : state.drawableFiles) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String resName = dot > 0 ? name.substring(0, dot) : name;
            list.add(new CodeEditorView.SuggestionItem("RES", "@drawable/" + resName, "@drawable/" + resName, 0));
            list.add(new CodeEditorView.SuggestionItem("RES", "R.drawable." + resName, "R.drawable." + resName, 0));
        }
        for (String id : state.xmlResourceIds) {
            list.add(new CodeEditorView.SuggestionItem("RES", "@id/" + id, "@id/" + id, 0));
            list.add(new CodeEditorView.SuggestionItem("RES", "R.id." + id, "R.id." + id, 0));
        }
        return list;
    }
}
