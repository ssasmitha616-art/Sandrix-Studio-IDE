package com.example.sandrix.engine;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.android.apksig.ApkSigner;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.ide.BuildLogLine;
import com.example.sandrix.xml.XmlPatcher;

import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.Main;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Asynchronous Sandrix APK Build Engine running off the main thread.
 *
 * <p>Pipeline stages:
 * <ol>
 *     <li>Parse {@code build.sd}</li>
 *     <li>Sync {@code AndroidManifest.xml} and layout XML namespaces</li>
 *     <li>Static XML / Java syntax pre-flight diagnostics</li>
 *     <li>{@code aapt2 compile} + {@code aapt2 link} (generates {@code R.java} &amp; {@code resources.ap_})</li>
 *     <li>Java compilation (native {@code javac}, dexed {@code ecj.jar}, or built-in ECJ {@link BatchCompiler})</li>
 *     <li>DEX compilation (native {@code d8}, dexed {@code d8.jar}, or built-in {@link D8})</li>
 *     <li>APK packaging &amp; signing (native {@code apksigner}, dexed {@code apksigner.jar}, or built-in {@link ApkSigner})</li>
 * </ol>
 */
public final class BuildEngineTask {

    private static final Pattern TOOL_ERROR_PATTERN =
            Pattern.compile("^(.+?\\.(?:java|xml|sd)):(\\d+):\\s*(?:error|Error|ERROR|warning|Warning)?:?\\s*(.+)$");

    private static final String ASSET_DEBUG_KEY = "sandrix_engine/keys/sandrix_debug.pk8";
    private static final String ASSET_DEBUG_CERT = "sandrix_engine/keys/sandrix_debug.x509.pem";

    private static final long LOG_FLUSH_INTERVAL_MS = 65L;
    private static final int LOG_BATCH_LIMIT = 24;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(null, r, "SandrixBuildEngine", 32L * 1024L * 1024L));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object logLock = new Object();
    private final List<BuildLogLine> pendingLogBatch = new ArrayList<>();
    private long lastLogFlushMs = 0L;
    private boolean logFlushScheduled = false;
    private volatile boolean cancelled = false;

    public BuildEngineTask(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void cancel() {
        this.cancelled = true;
    }

    public void shutdown() {
        this.cancelled = true;
        executor.shutdownNow();
    }

    public void execute(File projectDir, boolean autoInstallApk, Callback callback) {
        execute(projectDir, new BuildOptions(autoInstallApk, false, false, false, false, false), callback);
    }

    public void execute(File projectDir, boolean autoInstallApk, boolean releaseBuild, Callback callback) {
        execute(projectDir, new BuildOptions(autoInstallApk, releaseBuild, false, false, false, false), callback);
    }

    public void execute(File projectDir, boolean autoInstallApk, boolean releaseBuild, boolean cleanBuild, Callback callback) {
        execute(projectDir, new BuildOptions(autoInstallApk, releaseBuild, cleanBuild, false, false, false), callback);
    }

    public void execute(File projectDir, BuildOptions options, Callback callback) {
        BuildOptions effective = options != null
                ? options
                : new BuildOptions(false, false, false, false, false, false);
        this.cancelled = false;
        synchronized (logLock) {
            pendingLogBatch.clear();
            lastLogFlushMs = 0L;
            logFlushScheduled = false;
        }
        executor.execute(() -> runPipeline(projectDir, effective, callback));
    }

    public static File resolveEffectiveProjectRoot(File projectDir) {
        if (projectDir == null || !projectDir.exists()) return projectDir;
        File rootManifest = new File(projectDir, "AndroidManifest.xml");
        File srcMainManifest = new File(projectDir, "src/main/AndroidManifest.xml");
        File rootBuildSd = new File(projectDir, "build.sd");
        if (rootManifest.exists() || srcMainManifest.exists() || rootBuildSd.exists()) {
            return projectDir;
        }
        File appDir = new File(projectDir, "app");
        if (appDir.isDirectory() && (new File(appDir, "src/main/AndroidManifest.xml").exists()
                || new File(appDir, "AndroidManifest.xml").exists()
                || new File(appDir, "build.gradle").exists()
                || new File(appDir, "build.sd").exists())) {
            return appDir;
        }
        return projectDir;
    }

    private void runPipeline(File rawProjectDir, BuildOptions options, Callback callback) {
        boolean autoInstallApk = options.autoInstallApk && !options.buildAab;
        boolean releaseBuild = options.releaseBuild;
        boolean cleanBuild = options.cleanBuild;
        boolean buildAab = options.buildAab;
        long startMs = System.currentTimeMillis();
        timingStage = null;
        timingBuildStartMs = startMs;
        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        if (rawProjectDir != null) {
            String artifactLabel = buildAab ? (releaseBuild ? "release AAB" : "debug AAB") : (releaseBuild ? "release APK" : "debug APK");
            SandrixBuildService.startBuild(appContext, "Preparing " + artifactLabel + ": " + rawProjectDir.getName());
        }

        try {
            if (rawProjectDir == null || !rawProjectDir.exists()) {
                postLog(callback, "[SANDRIX] Project directory does not exist.", true);
                flushLogsNow(callback);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "Project directory missing."));
                return;
            }

            File projectDir = resolveEffectiveProjectRoot(rawProjectDir);
            if (!projectDir.getAbsolutePath().equals(rawProjectDir.getAbsolutePath())) {
                postLog(callback, "[SANDRIX] Using module root: " + projectDir.getName() + "/", false);
            }
            postLog(callback, "[SANDRIX] Build target: " + (buildAab ? "AAB Bundle" : "APK")
                    + " | Variant: " + (releaseBuild ? "RELEASE" : "DEBUG")
                    + (cleanBuild ? " (CLEAN)" : ""), false);

            // Stage 1: build.sd / build.gradle
            postStage(callback, Stage.CONFIG);
            File buildSdFile = new File(projectDir, "build.sd");
            File configSourceFile = buildSdFile.exists()
                    ? buildSdFile
                    : (new File(projectDir, "build.gradle").exists() ? new File(projectDir, "build.gradle") : buildSdFile);
            BuildSdParser.BuildSdConfig config = BuildSdParser.parseProjectConfig(projectDir);
            if (configSourceFile.exists()) {
                postLog(callback, "[CONFIG] Parsed " + configSourceFile.getName() + " -> " + config.toSummary(), false);
                for (BuildSdParser.ParseWarning w : config.warnings) {
                    BuildDiagnostic diag = new BuildDiagnostic(
                            BuildDiagnostic.Severity.WARNING,
                            configSourceFile.getAbsolutePath(),
                            w.lineNumber,
                            w.message,
                            configSourceFile.getName());
                    diagnostics.add(diag);
                    postDiagnostic(callback, diag);
                    postLog(callback, "[CONFIG WARN] " + configSourceFile.getName() + ":" + w.lineNumber + ": " + w.message, false);
                }
            } else {
                postLog(callback, "[CONFIG] No build.sd found, using defaults -> " + config.toSummary(), false);
            }

            if (cancelled) return;

            // Stage 2: Sync & Patch Manifest + Layout XML
            postStage(callback, Stage.SYNC_PATCH);
            File manifestFile = findManifestFile(projectDir);
            File mainActivityFile = findMainActivityFile(projectDir);

            if (manifestFile != null && manifestFile.exists()) {
                XmlPatcher.PatchReport manifestReport =
                        XmlPatcher.syncManifestPackage(mainActivityFile, manifestFile, config);
                postLog(callback, "[PATCHER] " + manifestReport.summary, false);
                for (String act : manifestReport.actions) {
                    postLog(callback, "  + " + act, false);
                }
            } else {
                BuildDiagnostic diag = new BuildDiagnostic(
                        BuildDiagnostic.Severity.ERROR,
                        new File(projectDir, "AndroidManifest.xml").getAbsolutePath(),
                        1,
                        "AndroidManifest.xml not found in project root or src/main/.",
                        "XmlPatcher");
                diagnostics.add(diag);
                postDiagnostic(callback, diag);
                postLog(callback, "[PATCHER ERROR] AndroidManifest.xml is missing.", true);
            }

            List<File> xmlFiles = collectFilesByExtension(findResDir(projectDir), ".xml");
            for (File xmlFile : xmlFiles) {
                File parent = xmlFile.getParentFile();
                if (parent != null && parent.getName().startsWith("layout")) {
                    XmlPatcher.PatchReport report = XmlPatcher.ensureAndroidNamespace(xmlFile);
                    for (String act : report.actions) {
                        postLog(callback, "[PATCHER] " + xmlFile.getName() + ": " + act, false);
                    }
                }
            }

            if (cancelled) return;

            // Stage 3: Static Diagnostics
            postStage(callback, Stage.STATIC_CHECK);
            runStaticDiagnostics(projectDir, manifestFile, xmlFiles, diagnostics, callback);

            boolean hasErrors = false;
            for (BuildDiagnostic d : diagnostics) {
                if (d.severity == BuildDiagnostic.Severity.ERROR) {
                    hasErrors = true;
                    break;
                }
            }
            if (hasErrors) {
                postLog(callback, "[SANDRIX] Build aborted due to source/XML errors.", true);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "Fix source or XML errors before compiling."));
                return;
            }

            if (cancelled) return;

            // Stage 4: Check external required toolchain (aapt2 + android.jar)
            SandrixToolchain toolchain = SandrixToolchain.inspect(appContext);
            File androidJar = toolchain.findAndroidJar(config.compileSdk);
            if (!toolchain.isFullNativePipelineReady(config.compileSdk)) {
                postLog(callback, "", false);
                postLog(callback, "[SANDRIX ENGINE] Pre-flight checks, build.sd parse, and XML patching passed!", false);
                postLog(callback, "[SANDRIX ENGINE] Missing required external files in: "
                        + toolchain.engineRoot.getAbsolutePath(), true);
                for (SandrixToolchain.ToolStatus status : toolchain.getStatusList(config.compileSdk)) {
                    postLog(callback,
                            "  - " + status.name + ": "
                                    + (status.available ? "READY" : "MISSING (" + status.path + ")"),
                            !status.available);
                }
                postLog(callback, "[TIP] Open Toolchain Status in the top bar to import your Sandrix Engine bundle (.zip), or use Preview mode to test layouts immediately.", false);
                postStage(callback, Stage.COMPLETE);
                postFinish(callback, new BuildResult(false, true, true, null, diagnostics,
                        elapsed(startMs), "Validated & synced (aapt2 or android.jar not installed yet)."));
                return;
            }

            // Prepare build directories (preserving AAPT2 flat cache unless cleanBuild requested)
            File buildDir = new File(projectDir, "build");
            File genDir = new File(buildDir, "generated/source");
            File flatDir = new File(buildDir, "intermediates/res_flat");
            File classesDir = new File(buildDir, "intermediates/classes");
            File dexDir = new File(buildDir, "intermediates/dex");
            File apkOutDir = new File(buildDir, "outputs/apk");
            cleanDirectory(genDir);
            if (cleanBuild) {
                cleanDirectory(flatDir);
            }
            cleanDirectory(classesDir);
            cleanDirectory(dexDir);
            mkdirs(genDir);
            mkdirs(flatDir);
            mkdirs(classesDir);
            mkdirs(dexDir);
            mkdirs(apkOutDir);

            // Stage 4.5: Resolve & download build.sd Maven/AAR dependencies
            postStage(callback, Stage.DEPENDENCIES);
            MavenDependencyResolver.ResolvedDependencies resolvedDeps;
            try {
                File mavenCache = new File(toolchain.engineRoot, "maven_cache");
                MavenDependencyResolver resolver = new MavenDependencyResolver(mavenCache, config.repositories);
                resolvedDeps = resolver.resolve(projectDir, config, new MavenDependencyResolver.ProgressListener() {
                    @Override
                    public void onLog(String message, boolean isError) {
                        postLog(callback, message, isError);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled;
                    }
                });
            } catch (MavenDependencyResolver.ResolutionException re) {
                int lineNum = re.lineNumber;
                String diagPath = configSourceFile.exists() ? configSourceFile.getAbsolutePath() : projectDir.getAbsolutePath();
                BuildDiagnostic diag = new BuildDiagnostic(
                        BuildDiagnostic.Severity.ERROR,
                        diagPath,
                        lineNum,
                        re.getMessage(),
                        "Dependencies");
                diagnostics.add(diag);
                postDiagnostic(callback, diag);
                postLog(callback, "[DEPS ERROR] " + re.getMessage(), true);
                flushLogsNow(callback);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "Dependency resolution failed."));
                return;
            }

            if (cancelled) return;

            String manifestPkg = MavenDependencyResolver.readPackageFromManifest(manifestFile);
            String effectiveAppId = config.effectiveApplicationId(manifestPkg, releaseBuild);
            String effectiveVersionName = config.effectiveVersionName(releaseBuild);

            // Merge AAR manifests into a build-intermediate AndroidManifest.xml
            File effectiveManifest = manifestFile;
            if (!resolvedDeps.aarManifests.isEmpty() && manifestFile != null && manifestFile.exists()) {
                File mergedManifestOut = new File(buildDir, "intermediates/merged_manifest/AndroidManifest.xml");
                XmlPatcher.ManifestMergeResult mergeRes = XmlPatcher.mergeManifests(
                        manifestFile,
                        resolvedDeps.aarManifests,
                        mergedManifestOut,
                        effectiveAppId);
                for (String action : mergeRes.actions) {
                    postLog(callback, "[MANIFEST] " + action, false);
                }
                if (!mergeRes.success || !mergeRes.conflicts.isEmpty()) {
                    for (String conflict : mergeRes.conflicts) {
                        BuildDiagnostic diag = new BuildDiagnostic(
                                BuildDiagnostic.Severity.ERROR,
                                manifestFile.getAbsolutePath(),
                                1,
                                conflict,
                                "ManifestMerger");
                        diagnostics.add(diag);
                        postDiagnostic(callback, diag);
                        postLog(callback, "[MANIFEST CONFLICT] " + conflict, true);
                    }
                    flushLogsNow(callback);
                    postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                            elapsed(startMs), "Manifest merge failed with conflict(s)."));
                    return;
                }
                effectiveManifest = mergeRes.mergedManifestFile;
            }

            // Stage 5: AAPT2 compile (with incremental flat caching) + link
            postStage(callback, Stage.AAPT2);
            List<String> aarFlatArchives = new ArrayList<>();
            File aarFlatDir = new File(flatDir, "aars");
            mkdirs(aarFlatDir);
            for (int i = 0; i < resolvedDeps.aarResDirs.size(); i++) {
                File aarResDir = resolvedDeps.aarResDirs.get(i);
                String hashKey = Integer.toHexString(aarResDir.getAbsolutePath().hashCode());
                File aarZip = new File(aarFlatDir, "aar_" + hashKey + ".zip");
                long latestResMtime = latestFileModified(aarResDir);
                if (!cleanBuild && aarZip.exists() && aarZip.length() > 0 && aarZip.lastModified() >= latestResMtime) {
                    aarFlatArchives.add(aarZip.getAbsolutePath());
                    continue;
                }
                List<String> aarCompileCmd = new ArrayList<>();
                aarCompileCmd.add(toolchain.aapt2Binary.getAbsolutePath());
                aarCompileCmd.add("compile");
                aarCompileCmd.add("--dir");
                aarCompileCmd.add(aarResDir.getAbsolutePath());
                aarCompileCmd.add("-o");
                aarCompileCmd.add(aarZip.getAbsolutePath());
                if (runProcess(aarCompileCmd, projectDir, toolchain, "AAPT2", diagnostics, callback) != 0) {
                    postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                            elapsed(startMs), "AAPT2 library resource compilation failed."));
                    return;
                }
                if (aarZip.exists() && aarZip.length() > 0) {
                    aarFlatArchives.add(aarZip.getAbsolutePath());
                }
            }

            File resDir = findResDir(projectDir);
            List<File> resFiles = collectAllFiles(resDir);
            List<String> flatFiles = new ArrayList<>();
            File projectFlatDir = new File(flatDir, "app");
            mkdirs(projectFlatDir);
            if (!resFiles.isEmpty()) {
                String resFingerprint = computeFilesFingerprint(resFiles);
                File resStamp = new File(flatDir, "app_res.stamp");
                boolean useCachedProjectRes = false;
                if (!cleanBuild && resStamp.exists()) {
                    String prevFingerprint = readFileTrimmed(resStamp);
                    if (resFingerprint.equals(prevFingerprint)) {
                        File[] existingFlats = projectFlatDir.listFiles();
                        if (existingFlats != null && existingFlats.length > 0) {
                            for (File f : existingFlats) {
                                if (f.getName().endsWith(".flat")) {
                                    flatFiles.add(f.getAbsolutePath());
                                }
                            }
                            useCachedProjectRes = !flatFiles.isEmpty();
                            if (useCachedProjectRes) {
                                postLog(callback, "[AAPT2] Reusing " + flatFiles.size() + " cached .flat resource(s).", false);
                            }
                        }
                    }
                }
                if (!useCachedProjectRes) {
                    cleanDirectory(projectFlatDir);
                    mkdirs(projectFlatDir);
                    List<String> compileCmd = new ArrayList<>();
                    compileCmd.add(toolchain.aapt2Binary.getAbsolutePath());
                    compileCmd.add("compile");
                    compileCmd.add("-o");
                    compileCmd.add(projectFlatDir.getAbsolutePath());
                    for (File rf : resFiles) {
                        compileCmd.add(rf.getAbsolutePath());
                    }
                    if (runProcess(compileCmd, projectDir, toolchain, "AAPT2", diagnostics, callback) != 0) {
                        postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                                elapsed(startMs), "AAPT2 resource compilation failed."));
                        return;
                    }
                    File[] compiled = projectFlatDir.listFiles();
                    if (compiled != null) {
                        for (File f : compiled) {
                            if (f.getName().endsWith(".flat")) {
                                flatFiles.add(f.getAbsolutePath());
                            }
                        }
                    }
                    writeUtf8Quietly(resStamp, resFingerprint);
                }
            }

            boolean useR8 = options.forceR8Optimize || (releaseBuild && config.isMinifyEnabledForVariant(true));
            boolean shrinkResources = options.shrinkResources || (useR8 && config.releaseShrinkResources);
            File aaptProguardFile = new File(buildDir, "intermediates/aapt_proguard_rules.pro");
            File resourcesAp = new File(buildDir, buildAab ? "intermediates/resources.proto.ap_" : "intermediates/resources.ap_");
            List<String> linkCmd = new ArrayList<>();
            linkCmd.add(toolchain.aapt2Binary.getAbsolutePath());
            linkCmd.add("link");
            if (buildAab) {
                linkCmd.add("--proto-format");
            }
            linkCmd.add("-I");
            linkCmd.add(androidJar.getAbsolutePath());
            XmlPatcher.qualifyComponentNames(effectiveManifest, manifestPkg);

            linkCmd.add("--manifest");
            linkCmd.add(effectiveManifest.getAbsolutePath());
            linkCmd.add("--java");
            linkCmd.add(genDir.getAbsolutePath());
            if (useR8) {
                linkCmd.add("--proguard");
                linkCmd.add(aaptProguardFile.getAbsolutePath());
            }
            linkCmd.add("--min-sdk-version");
            linkCmd.add(String.valueOf(config.minSdk));
            linkCmd.add("--target-sdk-version");
            linkCmd.add(String.valueOf(config.targetSdk));
            linkCmd.add("--version-code");
            linkCmd.add(String.valueOf(config.versionCode));
            linkCmd.add("--version-name");
            linkCmd.add(effectiveVersionName);
            if (effectiveAppId != null && !effectiveAppId.isEmpty() && !effectiveAppId.equals(manifestPkg)) {
                linkCmd.add("--rename-manifest-package");
                linkCmd.add(effectiveAppId);
            }
            String customPkg = (config.namespace != null && !config.namespace.isEmpty()) ? config.namespace : manifestPkg;
            if (customPkg != null && !customPkg.isEmpty()) {
                linkCmd.add("--custom-package");
                linkCmd.add(customPkg);
            }
            linkCmd.add("-o");
            linkCmd.add(resourcesAp.getAbsolutePath());
            linkCmd.add("--auto-add-overlay");

            String appPkg = manifestPkg;
            Set<String> extraPkgs = new LinkedHashSet<>();
            for (String p : resolvedDeps.aarPackages) {
                if (p != null && !p.isEmpty() && !p.equals(appPkg)) {
                    extraPkgs.add(p);
                }
            }
            if (!extraPkgs.isEmpty()) {
                StringBuilder joinedPkgs = new StringBuilder();
                for (String p : extraPkgs) {
                    if (joinedPkgs.length() > 0) joinedPkgs.append(':');
                    joinedPkgs.append(p);
                }
                linkCmd.add("--extra-packages");
                linkCmd.add(joinedPkgs.toString());
            }

            File projectAssets = findAssetsDir(projectDir);
            if (projectAssets != null && projectAssets.exists()) {
                linkCmd.add("-A");
                linkCmd.add(projectAssets.getAbsolutePath());
            }
            for (File aarAssets : resolvedDeps.aarAssetsDirs) {
                linkCmd.add("-A");
                linkCmd.add(aarAssets.getAbsolutePath());
            }

            for (String aarZip : aarFlatArchives) {
                linkCmd.add("-R");
                linkCmd.add(aarZip);
            }
            for (String flat : flatFiles) {
                linkCmd.add("-R");
                linkCmd.add(flat);
            }
            if (runProcess(linkCmd, projectDir, toolchain, "AAPT2", diagnostics, callback) != 0) {
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "AAPT2 resource linking failed."));
                return;
            }

            String buildConfigPkg = (config.namespace != null && !config.namespace.isEmpty())
                    ? config.namespace
                    : (appPkg != null && !appPkg.isEmpty() ? appPkg : effectiveAppId);
            generateBuildConfig(genDir, buildConfigPkg, effectiveAppId, releaseBuild, config.versionCode,
                    effectiveVersionName, config.buildConfigFields);

            if (cancelled) return;

            // Stage 6: Java Compilation (Java sources only)
            File srcDir = findSrcDir(projectDir);
            List<File> forbiddenKotlin = collectFilesByExtension(srcDir, ".kt");
            forbiddenKotlin.addAll(collectFilesByExtension(srcDir, ".kts"));
            if (!forbiddenKotlin.isEmpty()) {
                postLog(callback, "[COMPILER] Only Java (.java) sources are supported.", true);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "Non-Java source files found; use Java (.java) sources."));
                return;
            }

            List<File> javaFiles = new ArrayList<>();
            javaFiles.addAll(collectFilesByExtension(srcDir, ".java"));
            javaFiles.addAll(collectFilesByExtension(genDir, ".java"));

            if (javaFiles.isEmpty()) {
                postLog(callback, "[COMPILER] No .java source files found.", true);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "No Java sources to compile."));
                return;
            }

            String classpath = buildClasspath(androidJar, resolvedDeps.compileJars);
            File lambdaStubsJar = toolchain.ensureLambdaStubsJar();
            String bootClasspath = (lambdaStubsJar != null && lambdaStubsJar.exists())
                    ? androidJar.getAbsolutePath() + File.pathSeparator + lambdaStubsJar.getAbsolutePath()
                    : androidJar.getAbsolutePath();

            postStage(callback, Stage.JAVAC);
            boolean debugLineInfo = !releaseBuild || !useR8;
            int javacExit = runJavacOrEcj(
                    toolchain, projectDir, config.javaVersion, bootClasspath, classpath,
                    classesDir, javaFiles, debugLineInfo, diagnostics, callback);
            if (javacExit != 0) {
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "Java compilation failed."));
                return;
            }

            if (cancelled) return;

            // Stage 7: R8 Full Optimization OR D8 Dexing (sanitize classes + pre-dex cache + multi-dex)
            List<File> classFiles = collectFilesByExtension(classesDir, ".class");
            if (classFiles.isEmpty()) {
                postLog(callback, "[D8] No .class files generated.", true);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "No compiled classes to dex."));
                return;
            }

            int sanitizedAppClasses = ClassFileSanitizer.sanitizeClassFilesInPlace(classFiles);
            if (sanitizedAppClasses > 0) {
                postLog(callback, "[D8-SANITIZE] Cleaned MethodParameters metadata in "
                        + sanitizedAppClasses + " compiled class(es).", false);
            }

            List<String> dedupLogs = new ArrayList<>();
            File dedupDir = new File(buildDir, "intermediates/dedup_jars");
            List<File> projectJarLibs = deduplicateJarLibsForD8(
                    classesDir, dedupDir, resolvedDeps.dexJars, classFiles, dedupLogs);
            for (String logLine : dedupLogs) {
                postLog(callback, logLine, false);
            }

            List<File> compileOnlyJars = computeCompileOnlyClasspath(resolvedDeps.compileJars, resolvedDeps.dexJars);
            boolean lowMemoryMode = DexCacheManager.isLowMemoryDevice(appContext);
            List<File> rawJarLibs = new ArrayList<>(projectJarLibs);
            File r8MappingFile = null;

            if (useR8) {
                postStage(callback, Stage.R8);
                File r8SanitizeDir = new File(buildDir, "intermediates/sanitized_r8_jars");
                List<File> r8InputJars = ensureSanitizedJarsBeforeD8(rawJarLibs, r8SanitizeDir, callback);
                File mappingDir = new File(buildDir, "outputs/mapping/" + (releaseBuild ? "release" : "debug"));
                File targetMappingFile = new File(mappingDir, "mapping.txt");
                R8Optimizer.init(appContext);
                R8Optimizer.OptimizationResult r8Res = R8Optimizer.optimize(
                        projectDir,
                        config,
                        config.minSdk,
                        releaseBuild,
                        true,
                        true,
                        config.optimizeEnabled,
                        androidJar,
                        dexDir,
                        targetMappingFile,
                        aaptProguardFile,
                        classFiles,
                        r8InputJars,
                        compileOnlyJars,
                        resolvedDeps.proguardRuleFiles,
                        diagnostics,
                        (msg, isErr) -> postLog(callback, msg, isErr));
                if (!r8Res.success && r8Res.resourceFailure) {
                    postLog(callback, "[R8] Not enough memory for R8 on this device; falling back to D8 so the build still completes.", false);
                    cleanDirectory(dexDir);
                    useR8 = false;
                } else if (!r8Res.success) {
                    flushLogsNow(callback);
                    postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                            elapsed(startMs), "R8 optimization failed: " + r8Res.errorSummary));
                    return;
                } else {
                    r8MappingFile = r8Res.mappingFile;
                    if (shrinkResources) {
                        postLog(callback, "[R8] Resource shrinker pass: kept active manifest/layout IDs via AAPT2 keep rules.", false);
                    }
                }
            }
            if (!useR8) {
                postStage(callback, Stage.D8);
                boolean preDexUsed = false;
            if (!projectJarLibs.isEmpty()) {
                File dexCacheDir = new File(toolchain.engineRoot, "dex_cache");
                DexCacheManager cacheManager = new DexCacheManager(dexCacheDir);
                DiagnosticsHandler cacheDiagnostics = new DiagnosticsHandler() {
                    @Override
                    public void error(Diagnostic error) {
                        postLog(callback, "[D8-CACHE ERROR] " + error.getDiagnosticMessage(), true);
                    }

                    @Override
                    public void warning(Diagnostic warning) {
                        postLog(callback, "[D8-CACHE WARN] " + warning.getDiagnosticMessage(), false);
                    }

                    @Override
                    public void info(Diagnostic info) {
                    }
                };
                DexCacheManager.PreDexResult preDexResult = cacheManager.preparePreDexedLibraries(
                        projectJarLibs,
                        projectJarLibs,
                        config.minSdk,
                        androidJar,
                        lowMemoryMode,
                        cacheDiagnostics,
                        (msg, isErr) -> postLog(callback, msg, isErr),
                        () -> cancelled);
                projectJarLibs = preDexResult.cachedDexFiles;
                preDexUsed = true;
            }

            // Guarantee any non-dexed JAR reaching D8 is sanitized against R8 4.0.x MethodParameters NPE
            File fallbackSanitizeDir = new File(buildDir, "intermediates/sanitized_jars");
            projectJarLibs = ensureSanitizedJarsBeforeD8(projectJarLibs, fallbackSanitizeDir, callback);

            if (cancelled) return;

            int d8Exit;
            if (toolchain.hasNativeD8() || toolchain.hasDexedD8Jar()) {
                List<String> d8Cmd = new ArrayList<>();
                if (toolchain.hasNativeD8()) {
                    d8Cmd.add(toolchain.d8Binary.getAbsolutePath());
                } else {
                    d8Cmd.add("/system/bin/dalvikvm");
                    if (lowMemoryMode) {
                        d8Cmd.add("-Xms16m");
                        d8Cmd.add("-Xmx192m");
                    }
                    d8Cmd.add("-cp");
                    d8Cmd.add(toolchain.d8Jar.getAbsolutePath());
                    d8Cmd.add("com.android.tools.r8.D8");
                }
                if (releaseBuild) {
                    d8Cmd.add("--release");
                }
                d8Cmd.add("--min-api");
                d8Cmd.add(String.valueOf(config.minSdk));
                d8Cmd.add("--lib");
                d8Cmd.add(androidJar.getAbsolutePath());
                for (File cpJar : compileOnlyJars) {
                    d8Cmd.add("--classpath");
                    d8Cmd.add(cpJar.getAbsolutePath());
                }
                d8Cmd.add("--output");
                d8Cmd.add(dexDir.getAbsolutePath());
                for (File cf : classFiles) {
                    d8Cmd.add(cf.getAbsolutePath());
                }
                for (File jl : projectJarLibs) {
                    d8Cmd.add(jl.getAbsolutePath());
                }
                d8Exit = runProcess(d8Cmd, projectDir, toolchain, "D8", diagnostics, callback);
            } else {
                if (toolchain.d8Jar.exists()) {
                    postLog(callback,
                            "[D8] Note: " + toolchain.d8Jar.getName()
                                    + " has no classes.dex; using built-in Sandrix D8.",
                            false);
                } else {
                    postLog(callback, "[D8] Using built-in Sandrix D8 compiler.", false);
                }
                d8Exit = runBuiltinD8(config.minSdk, releaseBuild, androidJar, dexDir, classFiles,
                        projectJarLibs, compileOnlyJars, diagnostics, callback);
            }

            // If pre-dexed archive merge failed, immediately fall back to direct D8 from sanitized JARs
            if (d8Exit != 0 && preDexUsed) {
                postLog(callback,
                        "[D8] Pre-dexed archive merge failed (" + d8Exit
                                + "); retrying D8 directly from sanitized library archives...",
                        false);
                cleanDirectory(dexDir);
                List<File> directJarLibs = ensureSanitizedJarsBeforeD8(rawJarLibs, fallbackSanitizeDir, callback);
                if (toolchain.hasNativeD8() || toolchain.hasDexedD8Jar()) {
                    List<String> retryCmd = new ArrayList<>();
                    if (toolchain.hasNativeD8()) {
                        retryCmd.add(toolchain.d8Binary.getAbsolutePath());
                    } else {
                        retryCmd.add("/system/bin/dalvikvm");
                        if (lowMemoryMode) {
                            retryCmd.add("-Xms16m");
                            retryCmd.add("-Xmx192m");
                        }
                        retryCmd.add("-cp");
                        retryCmd.add(toolchain.d8Jar.getAbsolutePath());
                        retryCmd.add("com.android.tools.r8.D8");
                    }
                    if (releaseBuild) {
                        retryCmd.add("--release");
                    }
                    retryCmd.add("--min-api");
                    retryCmd.add(String.valueOf(config.minSdk));
                    retryCmd.add("--lib");
                    retryCmd.add(androidJar.getAbsolutePath());
                    for (File cpJar : compileOnlyJars) {
                        retryCmd.add("--classpath");
                        retryCmd.add(cpJar.getAbsolutePath());
                    }
                    retryCmd.add("--output");
                    retryCmd.add(dexDir.getAbsolutePath());
                    for (File cf : classFiles) {
                        retryCmd.add(cf.getAbsolutePath());
                    }
                    for (File jl : directJarLibs) {
                        retryCmd.add(jl.getAbsolutePath());
                    }
                    d8Exit = runProcess(retryCmd, projectDir, toolchain, "D8-DIRECT", diagnostics, callback);
                } else {
                    d8Exit = runBuiltinD8(config.minSdk, releaseBuild, androidJar, dexDir, classFiles,
                            directJarLibs, compileOnlyJars, diagnostics, callback);
                }
            }

            if (d8Exit != 0) {
                    flushLogsNow(callback);
                    postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                            elapsed(startMs), "D8 dexing failed."));
                    return;
                }
            }

            if (cancelled) return;

            // Stage 8: Either package & sign Android App Bundle (.aab) OR package & sign APK (.apk)
            ReleaseSigningConfig releaseConfig = null;
            if (releaseBuild) {
                try {
                    releaseConfig = ReleaseSigningConfig.load(projectDir, rawProjectDir);
                } catch (Exception e) {
                    postLog(callback, "[RELEASE SIGNING ERROR] " + e.getMessage(), true);
                    postFinish(callback, new BuildResult(false, true, false, null, diagnostics,
                            elapsed(startMs), "Release signing configuration is invalid."));
                    return;
                }
            }

            if (buildAab) {
                postStage(callback, Stage.AAB);
                File aabOutDir = new File(buildDir, "outputs/bundle/" + (releaseBuild ? "release" : "debug"));
                if (!aabOutDir.exists() && !aabOutDir.mkdirs()) {
                    throw new IOException("Cannot create AAB output directory: " + aabOutDir.getAbsolutePath());
                }
                File unsignedAab = new File(buildDir, "intermediates/app-unsigned.aab");
                File signedAab = new File(aabOutDir, releaseBuild ? "app-release.aab" : "app-debug.aab");
                try {
                    postLog(callback, "[AAB] Assembling base module & BundleConfig.pb...", false);
                    AabBuilder.buildUnsignedAab(
                            resourcesAp,
                            dexDir,
                            rawJarLibs,
                            resolvedDeps.jniDirs,
                            r8MappingFile,
                            unsignedAab);
                    SigningMaterial material;
                    String alias;
                    if (releaseBuild) {
                        material = loadReleaseSigningMaterial(releaseConfig);
                        alias = releaseConfig.keyAlias;
                    } else {
                        material = loadSigningMaterial(toolchain, callback);
                        alias = "SandrixDebug";
                    }
                    AabBuilder.signAabJarV1(unsignedAab, signedAab, alias, material.privateKey, material.certificate);
                    postLog(callback, "[AAB] Signed AAB bundle -> " + signedAab.getAbsolutePath()
                            + " (" + (signedAab.length() / 1024L) + " KB)", false);
                } catch (Throwable t) {
                    postLog(callback, "[AAB ERROR] " + t.getClass().getSimpleName() + ": " + t.getMessage(), true);
                    flushLogsNow(callback);
                    postFinish(callback, new BuildResult(false, false, false, null, null, r8MappingFile,
                            diagnostics, elapsed(startMs), "AAB packaging/signing failed: " + t.getMessage()));
                    return;
                }

                postStage(callback, Stage.COMPLETE);
                long totalMs = elapsed(startMs);
                postLog(callback, "[SANDRIX] " + (releaseBuild ? "RELEASE" : "DEBUG") + " AAB built successfully in "
                        + totalMs + "ms -> " + signedAab.getAbsolutePath(), false);
                flushLogsNow(callback);
                postFinish(callback, new BuildResult(
                        true,
                        true,
                        false,
                        null,
                        signedAab,
                        r8MappingFile,
                        diagnostics,
                        totalMs,
                        (releaseBuild ? "Release AAB signed: " : "Debug AAB signed: ") + signedAab.getName()));
                return;
            }

            postStage(callback, Stage.APKSIGNER);
            File unsignedApk = new File(buildDir, "intermediates/app-unsigned.apk");
            File classesDex = new File(dexDir, "classes.dex");
            packageUnsignedApk(resourcesAp, dexDir, classesDex, resolvedDeps.dexJars, resolvedDeps.jniDirs, unsignedApk);

            File alignedUnsignedApk = new File(buildDir, "intermediates/app-aligned-unsigned.apk");
            int alignExit = alignApkFile(toolchain, unsignedApk, alignedUnsignedApk, projectDir, diagnostics, callback);
            if (alignExit != 0) {
                flushLogsNow(callback);
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "APK zipalign failed."));
                return;
            }

            File signedApk = new File(apkOutDir, releaseBuild ? "app-release.apk" : "app-debug.apk");
            int signExit;
            if (releaseBuild) {
                signExit = signReleaseApk(toolchain, config.minSdk, projectDir, alignedUnsignedApk, signedApk,
                        releaseConfig, diagnostics, callback);
            } else if (toolchain.hasNativeApksigner() || toolchain.hasDexedApksignerJar()) {
                List<String> signCmd = new ArrayList<>();
                if (toolchain.hasNativeApksigner()) {
                    signCmd.add(toolchain.apksignerBinary.getAbsolutePath());
                } else {
                    signCmd.add("/system/bin/dalvikvm");
                    signCmd.add("-cp");
                    signCmd.add(toolchain.apksignerJar.getAbsolutePath());
                    signCmd.add("com.android.apksigner.ApkSignerTool");
                }
                signCmd.add("sign");
                if (toolchain.debugKeystore.exists()) {
                    signCmd.add("--ks");
                    signCmd.add(toolchain.debugKeystore.getAbsolutePath());
                    signCmd.add("--ks-pass");
                    signCmd.add("pass:android");
                }
                signCmd.add("--out");
                signCmd.add(signedApk.getAbsolutePath());
                signCmd.add(alignedUnsignedApk.getAbsolutePath());
                signExit = runProcess(signCmd, projectDir, toolchain, "APKSIGNER", diagnostics, callback);
            } else {
                if (toolchain.apksignerJar.exists()) {
                    postLog(callback,
                            "[APKSIGNER] Note: " + toolchain.apksignerJar.getName()
                                    + " has no classes.dex; using built-in Sandrix ApkSigner.",
                            false);
                } else {
                    postLog(callback, "[APKSIGNER] Using built-in Sandrix ApkSigner (v1/v2/v3).", false);
                }
                signExit = runBuiltinApkSigner(toolchain, config.minSdk, alignedUnsignedApk, signedApk, callback);
            }

            if (signExit != 0) {
                postFinish(callback, new BuildResult(false, false, false, null, diagnostics,
                        elapsed(startMs), "APK signing failed."));
                return;
            }

            postStage(callback, Stage.COMPLETE);
            long totalMs = elapsed(startMs);
            postLog(callback, "[SANDRIX] " + (releaseBuild ? "RELEASE" : "DEBUG") + " APK built successfully in " + totalMs + "ms -> "
                    + signedApk.getAbsolutePath(), false);
            flushLogsNow(callback);

            if (autoInstallApk) {
                mainHandler.post(() -> launchApkInstaller(appContext, signedApk));
            }

            postFinish(callback, new BuildResult(
                    true,
                    true,
                    false,
                    signedApk,
                    null,
                    r8MappingFile,
                    diagnostics,
                    totalMs,
                    (releaseBuild ? "Release APK signed: " : "Debug APK signed: ")
                            + signedApk.getName() + (useR8 ? " (R8)" : "")));
        } catch (Throwable t) {
            Throwable root = (t.getCause() != null) ? t.getCause() : t;
            String msg = root.getMessage() != null ? root.getMessage() : root.toString();
            postLog(callback, "[SANDRIX FATAL] " + t.getClass().getSimpleName() + ": " + msg, true);
            flushLogsNow(callback);
            postFinish(callback, new BuildResult(
                    false,
                    false,
                    false,
                    null,
                    diagnostics,
                    elapsed(startMs),
                    "Build failed: " + msg));
        } finally {
            flushLogsNow(callback);
            SandrixBuildService.stopBuild(appContext);
        }
    }

    private static int parseJavaMajorVersion(String javaVersion) {
        if (javaVersion == null) return 8;
        String v = javaVersion.trim();
        if (v.startsWith("1.")) {
            v = v.substring(2);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private List<String> buildCompilerArgs(
            String bootClasspath,
            String classpath,
            File classesDir,
            List<File> files,
            String javaVersion,
            boolean includeDebugInfo,
            boolean forBuiltinEcj) {
        String ver = (javaVersion != null && !javaVersion.trim().isEmpty()) ? javaVersion.trim() : "1.8";
        int major = parseJavaMajorVersion(ver);
        List<String> compilerArgs = new ArrayList<>();
        compilerArgs.add("-nowarn");
        compilerArgs.add("-proc:none");
        if (includeDebugInfo) {
            compilerArgs.add("-g");
        }
        if (forBuiltinEcj) {
            if (major >= 9) {
                compilerArgs.add("-" + major);
            } else {
                compilerArgs.add("-1." + major);
            }
        }
        compilerArgs.add("-source");
        compilerArgs.add(ver);
        compilerArgs.add("-target");
        compilerArgs.add(ver);
        if (!forBuiltinEcj && major < 9 && bootClasspath != null && !bootClasspath.isEmpty()) {
            compilerArgs.add("-bootclasspath");
            compilerArgs.add(bootClasspath);
        }
        compilerArgs.add("-cp");
        compilerArgs.add(classpath);
        compilerArgs.add("-d");
        compilerArgs.add(classesDir.getAbsolutePath());
        for (File f : files) {
            compilerArgs.add(f.getAbsolutePath());
        }
        return compilerArgs;
    }

    private int runJavacOrEcj(
            SandrixToolchain toolchain,
            File projectDir,
            String javaVersion,
            String bootClasspath,
            String classpath,
            File classesDir,
            List<File> javaFiles,
            boolean includeDebugInfo,
            List<BuildDiagnostic> diagnostics,
            Callback callback) throws IOException, InterruptedException {
        if (toolchain.hasNativeJavac()) {
            List<String> javacCmd = new ArrayList<>();
            javacCmd.add(toolchain.javacBinary.getAbsolutePath());
            javacCmd.addAll(buildCompilerArgs(
                    bootClasspath, classpath, classesDir, javaFiles, javaVersion, includeDebugInfo, false));
            return runProcess(javacCmd, projectDir, toolchain, "JAVAC", diagnostics, callback);
        } else if (toolchain.hasDexedEcjJar()) {
            List<String> ecjCmd = new ArrayList<>();
            ecjCmd.add("/system/bin/dalvikvm");
            ecjCmd.add("-cp");
            File compilerApiJar = toolchain.ensureJavaCompilerApiJar();
            String ecjCp = (compilerApiJar != null && compilerApiJar.exists())
                    ? toolchain.ecjJar.getAbsolutePath() + File.pathSeparator + compilerApiJar.getAbsolutePath()
                    : toolchain.ecjJar.getAbsolutePath();
            ecjCmd.add(ecjCp);
            ecjCmd.add("org.eclipse.jdt.internal.compiler.batch.Main");
            ecjCmd.addAll(buildCompilerArgs(
                    bootClasspath, classpath, classesDir, javaFiles, javaVersion, includeDebugInfo, true));
            return runProcess(ecjCmd, projectDir, toolchain, "JAVAC", diagnostics, callback);
        } else {
            String ver = (javaVersion != null && !javaVersion.isEmpty()) ? javaVersion : "17";
            if (toolchain.ecjJar.exists()) {
                postLog(callback,
                        "[JAVAC] Note: " + toolchain.ecjJar.getName()
                                + " has no classes.dex; using built-in Sandrix ECJ 3.33 (Java " + ver + ").",
                        false);
            } else {
                postLog(callback, "[JAVAC] Using built-in Sandrix ECJ 3.33 (Java " + ver + ") compiler.", false);
            }
            List<String> builtinArgs = buildCompilerArgs(
                    bootClasspath, classpath, classesDir, javaFiles, javaVersion, includeDebugInfo, true);
            return runBuiltinEcj(builtinArgs, bootClasspath, diagnostics, callback);
        }
    }

    /**
     * Runs ECJ 3.33 {@link Main} with an isolated Android bootclasspath override so Java 9-17
     * compilation uses {@code android.jar} (and optional lambda stubs) without triggering ECJ's
     * {@code -bootclasspath} rejection or JDK {@code jrt:/java.base} {@code SplitPackageBinding} crashes.
     */
    public static boolean compileWithAndroidEcj(
            List<String> args,
            String bootClasspath,
            PrintWriter outPw,
            PrintWriter errPw) {
        Main main = new Main(outPw, errPw, false, null, null) {
            @Override
            protected ArrayList<FileSystem.Classpath> handleBootclasspath(
                    ArrayList<String> bootclasspaths,
                    String customEncoding) {
                if (bootClasspath != null && !bootClasspath.trim().isEmpty()) {
                    ArrayList<FileSystem.Classpath> entries = new ArrayList<>();
                    String[] parts = bootClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator));
                    for (String part : parts) {
                        if (part == null) continue;
                        String trimmed = part.trim();
                        if (trimmed.isEmpty()) continue;
                        FileSystem.Classpath cp = FileSystem.getClasspath(trimmed, customEncoding, null, null, null);
                        if (cp != null) {
                            entries.add(cp);
                        }
                    }
                    if (!entries.isEmpty()) {
                        return entries;
                    }
                }
                return super.handleBootclasspath(bootclasspaths, customEncoding);
            }

            @Override
            protected ArrayList<FileSystem.Classpath> handleExtdirs(ArrayList<String> extdirs) {
                return new ArrayList<>();
            }

            @Override
            protected ArrayList<FileSystem.Classpath> handleEndorseddirs(ArrayList<String> endorseddirs) {
                return new ArrayList<>();
            }
        };
        return main.compile(args.toArray(new String[0]));
    }

    private int runBuiltinEcj(
            List<String> args,
            String bootClasspath,
            List<BuildDiagnostic> diagnostics,
            Callback callback) {
        postLog(callback, "[JAVAC] $ ecj-builtin " + joinCommand(args), false);
        LineDispatchWriter outWriter = new LineDispatchWriter(line -> processToolLine("JAVAC", line, diagnostics, callback));
        LineDispatchWriter errWriter = new LineDispatchWriter(line -> processToolLine("JAVAC", line, diagnostics, callback));
        try (PrintWriter outPw = new PrintWriter(outWriter, true);
             PrintWriter errPw = new PrintWriter(errWriter, true)) {
            boolean ok = compileWithAndroidEcj(args, bootClasspath, outPw, errPw);
            outWriter.flushLine();
            errWriter.flushLine();
            return ok ? 0 : 1;
        } catch (Throwable t) {
            Throwable root = (t.getCause() != null) ? t.getCause() : t;
            String msg = root.getMessage() != null ? root.getMessage() : root.toString();
            postLog(callback, "[JAVAC ERROR] " + t.getClass().getSimpleName() + ": " + msg, true);
            return 1;
        }
    }

    private int runBuiltinD8(
            int minSdk,
            boolean releaseBuild,
            File androidJar,
            File dexOutDir,
            List<File> classFiles,
            List<File> jarLibs,
            List<File> compileOnlyJars,
            List<BuildDiagnostic> diagnostics,
            Callback callback) {
        postLog(callback, "[D8] $ d8-builtin --min-api " + minSdk
                + " (" + classFiles.size() + " app classes, " + jarLibs.size() + " library archives)", false);
        try {
            DiagnosticsHandler d8Diagnostics = new DiagnosticsHandler() {
                @Override
                public void error(Diagnostic error) {
                    String msg = error.getDiagnosticMessage();
                    postLog(callback, "[D8 ERROR] " + msg, true);
                    BuildDiagnostic diag = new BuildDiagnostic(
                            BuildDiagnostic.Severity.ERROR,
                            error.getOrigin() != null ? error.getOrigin().toString() : "D8",
                            1,
                            msg,
                            "D8");
                    diagnostics.add(diag);
                    postDiagnostic(callback, diag);
                }

                @Override
                public void warning(Diagnostic warning) {
                    postLog(callback, "[D8 WARN] " + warning.getDiagnosticMessage(), false);
                }

                @Override
                public void info(Diagnostic info) {
                    postLog(callback, "[D8] " + info.getDiagnosticMessage(), false);
                }
            };

            D8Command.Builder builder = D8Command.builder(d8Diagnostics)
                    .setMinApiLevel(minSdk)
                    .setMode(releaseBuild ? com.android.tools.r8.CompilationMode.RELEASE
                            : com.android.tools.r8.CompilationMode.DEBUG)
                    .addLibraryFiles(androidJar.toPath())
                    .setOutput(dexOutDir.toPath(), OutputMode.DexIndexed);

            if (compileOnlyJars != null) {
                for (File cpJar : compileOnlyJars) {
                    if (cpJar != null && cpJar.exists()) {
                        builder.addClasspathFiles(cpJar.toPath());
                    }
                }
            }
            for (File cf : classFiles) {
                builder.addProgramFiles(cf.toPath());
            }
            for (File jl : jarLibs) {
                builder.addProgramFiles(jl.toPath());
            }

            D8.run(builder.build());
            File[] dexOutputs = dexOutDir.listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexOutputs == null || dexOutputs.length == 0) {
                postLog(callback, "[D8] Built-in D8 completed without producing classes.dex.", true);
                return 1;
            }
            Arrays.sort(dexOutputs);
            long totalBytes = 0L;
            for (File f : dexOutputs) totalBytes += f.length();
            postLog(callback, "[D8] Generated " + dexOutputs.length + " dex file(s) ("
                    + totalBytes + " bytes total)", false);
            return 0;
        } catch (Throwable t) {
            Throwable root = (t.getCause() != null) ? t.getCause() : t;
            String msg = root.getMessage() != null ? root.getMessage() : root.toString();
            postLog(callback, "[D8 ERROR] " + root.getClass().getSimpleName() + ": " + msg, true);
            return 1;
        }
    }

    private int alignApkFile(
            SandrixToolchain toolchain,
            File inputApk,
            File outputApk,
            File projectDir,
            List<BuildDiagnostic> diagnostics,
            Callback callback) {
        if (toolchain != null && toolchain.hasZipalign()) {
            List<String> alignCmd = new ArrayList<>();
            alignCmd.add(toolchain.zipalignBinary.getAbsolutePath());
            alignCmd.add("-f");
            alignCmd.add("-p");
            alignCmd.add("4");
            alignCmd.add(inputApk.getAbsolutePath());
            alignCmd.add(outputApk.getAbsolutePath());
            postLog(callback, "[ZIPALIGN] $ zipalign -f -p 4 " + inputApk.getName() + " " + outputApk.getName(), false);
            try {
                int exit = runProcess(alignCmd, projectDir, toolchain, "ZIPALIGN", diagnostics, callback);
                if (exit == 0 && outputApk.exists() && outputApk.length() > 0 && ZipAligner.isApkAligned(outputApk, 4)) {
                    postLog(callback, "[ZIPALIGN] Verified 4-byte APK alignment via native zipalign.", false);
                    return 0;
                }
            } catch (Throwable ignored) {}
            postLog(callback, "[ZIPALIGN] Native zipalign unavailable or non-verifying; using built-in Sandrix ZipAligner.", false);
        } else {
            postLog(callback, "[ZIPALIGN] Using built-in Sandrix ZipAligner (4-byte resources + 4096-byte .so alignment).", false);
        }
        try {
            ZipAligner.alignApk(inputApk, outputApk, ZipAligner.DEFAULT_ALIGNMENT);
            postLog(callback, "[ZIPALIGN] Aligned APK successfully -> " + outputApk.getName(), false);
            return 0;
        } catch (Throwable t) {
            postLog(callback, "[ZIPALIGN ERROR] " + t.getClass().getSimpleName() + ": " + t.getMessage(), true);
            return 1;
        }
    }

    private int signReleaseApk(
            SandrixToolchain toolchain,
            int minSdk,
            File projectDir,
            File unsignedApk,
            File signedApk,
            ReleaseSigningConfig config,
            List<BuildDiagnostic> diagnostics,
            Callback callback) {
        try {
            if (signedApk.exists() && !signedApk.delete()) {
                throw new IOException("Cannot replace existing release APK: " + signedApk.getAbsolutePath());
            }
            File signingInputApk = unsignedApk;
            if (!ZipAligner.isApkAligned(unsignedApk, 4)) {
                File alignedApk = new File(projectDir, "build/intermediates/app-release-aligned-unsigned.apk");
                File alignedParent = alignedApk.getParentFile();
                if (alignedParent != null && !alignedParent.exists() && !alignedParent.mkdirs()) {
                    throw new IOException("Cannot create release intermediates directory.");
                }
                int alignCode = alignApkFile(toolchain, unsignedApk, alignedApk, projectDir, diagnostics, callback);
                if (alignCode != 0) {
                    throw new IOException("zipalign failed; release APK was not signed.");
                }
                signingInputApk = alignedApk;
            }
            if (toolchain.hasNativeApksigner() || toolchain.hasDexedApksignerJar()) {
                List<String> cmd = new ArrayList<>();
                if (toolchain.hasNativeApksigner()) {
                    cmd.add(toolchain.apksignerBinary.getAbsolutePath());
                } else {
                    cmd.add("/system/bin/dalvikvm");
                    cmd.add("-cp");
                    cmd.add(toolchain.apksignerJar.getAbsolutePath());
                    cmd.add("com.android.apksigner.ApkSignerTool");
                }
                cmd.add("sign");
                cmd.add("--ks");
                cmd.add(config.keystore.getAbsolutePath());
                cmd.add("--ks-type");
                cmd.add(config.storeType);
                cmd.add("--ks-key-alias");
                cmd.add(config.keyAlias);
                cmd.add("--ks-pass");
                cmd.add("pass:" + config.storePassword);
                cmd.add("--key-pass");
                cmd.add("pass:" + config.keyPassword);
                cmd.add("--v1-signing-enabled");
                cmd.add("true");
                cmd.add("--v2-signing-enabled");
                cmd.add("true");
                cmd.add("--v3-signing-enabled");
                cmd.add("true");
                cmd.add("--out");
                cmd.add(signedApk.getAbsolutePath());
                cmd.add(signingInputApk.getAbsolutePath());
                postLog(callback, "[RELEASE SIGNING] Signing with supplied release keystore (passwords hidden).", false);
                return runProcess(cmd, projectDir, toolchain, "RELEASE SIGNING", diagnostics, callback, true);
            }

            SigningMaterial material = loadReleaseSigningMaterial(config);
            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                    config.keyAlias, material.privateKey, Collections.singletonList(material.certificate)).build();
            if (signedApk.exists()) {
                //noinspection ResultOfMethodCallIgnored
                signedApk.delete();
            }
            ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setInputApk(signingInputApk)
                    .setOutputApk(signedApk)
                    .setMinSdkVersion(Math.max(1, minSdk))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setCreatedBy("Sandrix Studio IDE Release")
                    .build();
            signer.sign();
            postLog(callback, "[RELEASE SIGNING] Signed APK (v1+v2+v3) -> " + signedApk.getName(), false);
            return 0;
        } catch (Throwable t) {
            postLog(callback, "[RELEASE SIGNING ERROR] " + t.getClass().getSimpleName() + ": " + t.getMessage(), true);
            return 1;
        }
    }

    private SigningMaterial loadReleaseSigningMaterial(ReleaseSigningConfig config) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(config.storeType);
        try (FileInputStream input = new FileInputStream(config.keystore)) {
            keyStore.load(input, config.storePassword.toCharArray());
        }
        if (!keyStore.containsAlias(config.keyAlias) || !keyStore.isKeyEntry(config.keyAlias)) {
            throw new IOException("keyAlias is missing or is not a private-key entry in the keystore.");
        }
        java.security.Key key = keyStore.getKey(config.keyAlias, config.keyPassword.toCharArray());
        Certificate cert = keyStore.getCertificate(config.keyAlias);
        if (!(key instanceof PrivateKey) || !(cert instanceof X509Certificate)) {
            throw new IOException("The selected alias does not contain a supported private key and X.509 certificate.");
        }
        return new SigningMaterial((PrivateKey) key, (X509Certificate) cert);
    }

    private static final class ReleaseSigningConfig {
        final File keystore;
        final String storeType;
        final String storePassword;
        final String keyAlias;
        final String keyPassword;

        ReleaseSigningConfig(File keystore, String storeType, String storePassword,
                             String keyAlias, String keyPassword) {
            this.keystore = keystore;
            this.storeType = storeType;
            this.storePassword = storePassword;
            this.keyAlias = keyAlias;
            this.keyPassword = keyPassword;
        }

        static ReleaseSigningConfig load(File projectDir) throws Exception {
            return load(projectDir, projectDir);
        }

        static ReleaseSigningConfig load(File projectDir, File fallbackRootDir) throws Exception {
            File propertiesFile = new File(projectDir, "release-signing.properties");
            File baseDir = projectDir;
            if (!propertiesFile.isFile() && fallbackRootDir != null) {
                File rootProp = new File(fallbackRootDir, "release-signing.properties");
                if (rootProp.isFile()) {
                    propertiesFile = rootProp;
                    baseDir = fallbackRootDir;
                }
            }
            if (!propertiesFile.isFile()) {
                throw new IOException("Missing release-signing.properties. Open Release Signing Manager or copy release-signing.properties.example.");
            }
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(propertiesFile)) {
                properties.load(input);
            }
            String path = required(properties, "storeFile");
            File keyFile = new File(path);
            if (!keyFile.isAbsolute()) keyFile = new File(baseDir, path);
            keyFile = keyFile.getCanonicalFile();
            if (!keyFile.isFile()) throw new IOException("Keystore file not found: " + keyFile.getAbsolutePath());
            String storePassword = required(properties, "storePassword");
            String keyAlias = required(properties, "keyAlias");
            String keyPassword = properties.getProperty("keyPassword", storePassword).trim();
            String storeType = properties.getProperty("storeType", "PKCS12").trim();
            if (keyPassword.isEmpty()) keyPassword = storePassword;
            if (storeType.isEmpty()) storeType = "PKCS12";
            return new ReleaseSigningConfig(keyFile, storeType, storePassword, keyAlias, keyPassword);
        }

        private static String required(Properties properties, String name) throws IOException {
            String value = properties.getProperty(name);
            if (value == null || value.trim().isEmpty()) throw new IOException("Missing '" + name + "' in release-signing.properties.");
            return value.trim();
        }
    }

    private int runBuiltinApkSigner(
            SandrixToolchain toolchain,
            int minSdk,
            File unsignedApk,
            File signedApk,
            Callback callback) {
        postLog(callback, "[APKSIGNER] $ apksig-builtin sign --out " + signedApk.getName()
                + " " + unsignedApk.getName(), false);
        try {
            SigningMaterial material = loadSigningMaterial(toolchain, callback);
            ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                    "SandrixDebug",
                    material.privateKey,
                    Collections.singletonList(material.certificate))
                    .build();

            if (signedApk.exists()) {
                //noinspection ResultOfMethodCallIgnored
                signedApk.delete();
            }

            ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setInputApk(unsignedApk)
                    .setOutputApk(signedApk)
                    .setMinSdkVersion(Math.max(1, minSdk))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .setCreatedBy("Sandrix Studio IDE")
                    .build();
            signer.sign();
            postLog(callback, "[APKSIGNER] Signed APK (v1+v2+v3) -> " + signedApk.getName(), false);
            return 0;
        } catch (Throwable t) {
            postLog(callback, "[APKSIGNER ERROR] " + t.getClass().getSimpleName() + ": " + t.getMessage(), true);
            return 1;
        }
    }

    private SigningMaterial loadSigningMaterial(SandrixToolchain toolchain, Callback callback) throws Exception {
        if (toolchain.debugKeystore != null && toolchain.debugKeystore.exists()) {
            for (String type : new String[]{"PKCS12", "BKS"}) {
                try (FileInputStream fis = new FileInputStream(toolchain.debugKeystore)) {
                    KeyStore ks = KeyStore.getInstance(type);
                    ks.load(fis, "android".toCharArray());
                    Enumeration<String> aliases = ks.aliases();
                    while (aliases.hasMoreElements()) {
                        String alias = aliases.nextElement();
                        if (ks.isKeyEntry(alias)) {
                            PrivateKey key = (PrivateKey) ks.getKey(alias, "android".toCharArray());
                            Certificate cert = ks.getCertificate(alias);
                            if (key != null && cert instanceof X509Certificate) {
                                return new SigningMaterial(key, (X509Certificate) cert);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            postLog(callback,
                    "[APKSIGNER] External keystore could not be loaded as PKCS12/BKS; using built-in Sandrix debug certificate.",
                    false);
        }

        byte[] keyBytes;
        try (InputStream is = appContext.getAssets().open(ASSET_DEBUG_KEY)) {
            keyBytes = readAllBytesCompat(is);
        }
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        X509Certificate certificate;
        try (InputStream is = appContext.getAssets().open(ASSET_DEBUG_CERT)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(is);
        }
        return new SigningMaterial(privateKey, certificate);
    }

    private static byte[] readAllBytesCompat(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void runStaticDiagnostics(
            File projectDir,
            File manifestFile,
            List<File> xmlFiles,
            List<BuildDiagnostic> diagnostics,
            Callback callback) throws IOException {
        if (manifestFile != null && manifestFile.exists()) {
            String xml = XmlPatcher.readFileUtf8(manifestFile);
            XmlPatcher.XmlValidationResult res = XmlPatcher.validateXml(xml);
            if (!res.valid) {
                BuildDiagnostic diag = new BuildDiagnostic(
                        BuildDiagnostic.Severity.ERROR,
                        manifestFile.getAbsolutePath(),
                        res.errorLine,
                        "Manifest XML error: " + res.errorMessage,
                        "XML");
                diagnostics.add(diag);
                postDiagnostic(callback, diag);
                postLog(callback, "[XML ERROR] AndroidManifest.xml:" + res.errorLine + ": " + res.errorMessage, true);
            }
        }

        for (File xmlFile : xmlFiles) {
            String xml = XmlPatcher.readFileUtf8(xmlFile);
            XmlPatcher.XmlValidationResult res = XmlPatcher.validateXml(xml);
            if (!res.valid) {
                BuildDiagnostic diag = new BuildDiagnostic(
                        BuildDiagnostic.Severity.ERROR,
                        xmlFile.getAbsolutePath(),
                        res.errorLine,
                        res.errorMessage != null ? res.errorMessage : "Malformed XML",
                        "XML");
                diagnostics.add(diag);
                postDiagnostic(callback, diag);
                postLog(callback, "[XML ERROR] " + xmlFile.getName() + ":" + res.errorLine + ": " + res.errorMessage, true);
            } else {
                checkLayoutAttributes(xmlFile, xml, diagnostics, callback);
            }
        }

    }

    private void checkLayoutAttributes(
            File xmlFile,
            String xml,
            List<BuildDiagnostic> diagnostics,
            Callback callback) {
        if (xml.contains("android:") && !xml.contains("xmlns:android")) {
            BuildDiagnostic diag = new BuildDiagnostic(
                    BuildDiagnostic.Severity.ERROR,
                    xmlFile.getAbsolutePath(),
                    1,
                    "Missing xmlns:android declaration.",
                    "XML");
            diagnostics.add(diag);
            postDiagnostic(callback, diag);
            postLog(callback, "[XML ERROR] " + xmlFile.getName() + ":1: Missing xmlns:android declaration.", true);
        }
    }

    private int runProcess(
            List<String> command,
            File workDir,
            SandrixToolchain toolchain,
            String toolTag,
            List<BuildDiagnostic> diagnostics,
            Callback callback) throws IOException, InterruptedException {
        return runProcess(command, workDir, toolchain, toolTag, diagnostics, callback, false);
    }

    private int runProcess(
            List<String> command,
            File workDir,
            SandrixToolchain toolchain,
            String toolTag,
            List<BuildDiagnostic> diagnostics,
            Callback callback,
            boolean redactSigningSecrets) throws IOException, InterruptedException {
        postLog(callback, "[" + toolTag + "] $ "
                + (redactSigningSecrets ? joinCommandRedacted(command) : joinCommand(command)), false);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("TMPDIR", appContext.getCacheDir().getAbsolutePath());
        env.put("HOME", toolchain.engineRoot.getAbsolutePath());
        env.put("LD_LIBRARY_PATH", toolchain.buildLdLibraryPath());

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            File bin = new File(command.get(0));
            String details = SandrixToolchain.describeBinaryType(bin);
            postLog(callback, "[" + toolTag + " EXEC INFO] Binary details: " + details, true);
            throw e;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    process.destroy();
                    return -1;
                }
                processToolLine(toolTag, line, diagnostics, callback);
            }
        }
        return process.waitFor();
    }

    private void processToolLine(
            String toolTag,
            String line,
            List<BuildDiagnostic> diagnostics,
            Callback callback) {
        if (line == null) return;
        String lower = line.toLowerCase(Locale.ROOT);
        boolean isErr = lower.contains("error")
                || lower.contains("exception")
                || lower.contains("failed");
        postLog(callback, "[" + toolTag + "] " + line, isErr);

        Matcher m = TOOL_ERROR_PATTERN.matcher(line);
        if (m.find()) {
            String filePath = m.group(1);
            String lineStr = m.group(2);
            String msg = m.group(3);
            int lineNum = lineStr != null ? Integer.parseInt(lineStr) : 1;
            BuildDiagnostic.Severity sev = lower.contains("warning")
                    ? BuildDiagnostic.Severity.WARNING
                    : BuildDiagnostic.Severity.ERROR;
            BuildDiagnostic diag = new BuildDiagnostic(sev, filePath, lineNum, msg, toolTag);
            diagnostics.add(diag);
            postDiagnostic(callback, diag);
        }
    }

    private static void packageUnsignedApk(
            File resourcesAp,
            File dexDir,
            File primaryDex,
            List<File> jarLibs,
            List<File> jniDirs,
            File unsignedApk) throws IOException {
        byte[] buffer = new byte[8192];
        Set<String> seenEntries = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk))) {
            if (resourcesAp != null && resourcesAp.exists()) {
                try (ZipFile zf = new ZipFile(resourcesAp)) {
                    Enumeration<? extends ZipEntry> entries = zf.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        if (!seenEntries.add(entryName)) {
                            continue;
                        }
                        ZipEntry copy = new ZipEntry(entryName);
                        copy.setTime(entry.getTime());
                        if (entry.getMethod() == ZipEntry.STORED) {
                            copy.setMethod(ZipEntry.STORED);
                            copy.setSize(entry.getSize());
                            copy.setCompressedSize(entry.getCompressedSize());
                            copy.setCrc(entry.getCrc());
                        } else {
                            copy.setMethod(ZipEntry.DEFLATED);
                        }
                        zos.putNextEntry(copy);
                        try (InputStream is = zf.getInputStream(entry)) {
                            int len;
                            while ((len = is.read(buffer)) != -1) {
                                zos.write(buffer, 0, len);
                            }
                        }
                        zos.closeEntry();
                    }
                }
            }

            List<File> dexFiles = new ArrayList<>();
            if (dexDir != null && dexDir.exists()) {
                File[] found = dexDir.listFiles((dir, name) -> name.endsWith(".dex"));
                if (found != null) {
                    Arrays.sort(found);
                    Collections.addAll(dexFiles, found);
                }
            }
            if (dexFiles.isEmpty() && primaryDex != null && primaryDex.exists()) {
                dexFiles.add(primaryDex);
            }
            for (File dexFile : dexFiles) {
                if (!seenEntries.add(dexFile.getName())) continue;
                zos.putNextEntry(new ZipEntry(dexFile.getName()));
                try (FileInputStream fis = new FileInputStream(dexFile)) {
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }

            if (jniDirs != null) {
                for (File jniRoot : jniDirs) {
                    if (jniRoot == null || !jniRoot.exists() || !jniRoot.isDirectory()) continue;
                    List<File> soFiles = collectAllFiles(jniRoot);
                    Collections.sort(soFiles);
                    String rootPrefix = jniRoot.getAbsolutePath();
                    if (!rootPrefix.endsWith(File.separator)) {
                        rootPrefix += File.separator;
                    }
                    for (File so : soFiles) {
                        if (!so.isFile()) continue;
                        String rel = so.getAbsolutePath().substring(rootPrefix.length()).replace('\\', '/');
                        String entryName = "lib/" + rel;
                        if (!seenEntries.add(entryName)) continue;
                        // Store .so entries uncompressed (STORED) with CRC32 so that 4096-byte page alignment
                        // succeeds in ZipAligner and android:extractNativeLibs="false" can mmap them directly.
                        ZipEntry soEntry = new ZipEntry(entryName);
                        soEntry.setMethod(ZipEntry.STORED);
                        long soLen = so.length();
                        soEntry.setSize(soLen);
                        soEntry.setCompressedSize(soLen);
                        java.util.zip.CRC32 soCrc = new java.util.zip.CRC32();
                        try (FileInputStream fis = new FileInputStream(so)) {
                            int len;
                            while ((len = fis.read(buffer)) != -1) {
                                soCrc.update(buffer, 0, len);
                            }
                        }
                        soEntry.setCrc(soCrc.getValue());
                        zos.putNextEntry(soEntry);
                        try (FileInputStream fis = new FileInputStream(so)) {
                            int len;
                            while ((len = fis.read(buffer)) != -1) {
                                zos.write(buffer, 0, len);
                            }
                        }
                        zos.closeEntry();
                    }
                }
            }

            if (jarLibs != null) {
                for (File jar : jarLibs) {
                    if (jar == null || !jar.exists()) continue;
                    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            String name = entry.getName();
                            if (entry.isDirectory() || shouldSkipJarResource(name) || !seenEntries.add(name)) {
                                zis.closeEntry();
                                continue;
                            }
                            zos.putNextEntry(new ZipEntry(name));
                            int len;
                            while ((len = zis.read(buffer)) != -1) {
                                zos.write(buffer, 0, len);
                            }
                            zos.closeEntry();
                            zis.closeEntry();
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public static List<File> deduplicateJarLibsForD8(
            File classesDir,
            File dedupOutDir,
            List<File> jarLibs,
            List<File> appClassFiles,
            List<String> logsOut) throws IOException {
        if (jarLibs == null || jarLibs.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> seenClasses = new HashSet<>();
        if (classesDir != null && appClassFiles != null) {
            String rootPrefix = classesDir.getAbsolutePath();
            if (!rootPrefix.endsWith(File.separator)) {
                rootPrefix += File.separator;
            }
            for (File cf : appClassFiles) {
                if (cf == null) continue;
                String abs = cf.getAbsolutePath();
                if (abs.startsWith(rootPrefix)) {
                    seenClasses.add(abs.substring(rootPrefix.length()).replace('\\', '/'));
                }
            }
        }

        List<File> orderedJars = new ArrayList<>(jarLibs.size());
        for (File jar : jarLibs) {
            if (jar != null && jar.exists()) {
                orderedJars.add(jar);
            }
        }

        if (dedupOutDir != null) {
            cleanDirectory(dedupOutDir);
            //noinspection ResultOfMethodCallIgnored
            dedupOutDir.mkdirs();
        }

        List<File> result = new ArrayList<>(orderedJars.size());
        byte[] buffer = new byte[8192];
        int jarIndex = 0;

        for (File jar : orderedJars) {
            jarIndex++;
            boolean needsRewrite = false;
            List<String> jarClassEntries = new ArrayList<>();
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.endsWith(".class")) {
                        if (isIgnoredMultiReleaseOrModuleClass(name) || seenClasses.contains(name)) {
                            needsRewrite = true;
                        } else {
                            jarClassEntries.add(name);
                        }
                    }
                    zis.closeEntry();
                }
            }

            if (!needsRewrite) {
                seenClasses.addAll(jarClassEntries);
                result.add(jar);
                continue;
            }

            if (dedupOutDir == null) {
                continue;
            }

            File filteredJar = new File(dedupOutDir, jarIndex + "_" + jar.getName());
            int skippedClasses = 0;
            int keptClasses = 0;
            int keptResources = 0;
            Set<String> localEntries = new HashSet<>();
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar));
                 ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(filteredJar))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !localEntries.add(name)) {
                        zis.closeEntry();
                        continue;
                    }
                    if (name.endsWith(".class")) {
                        if (isIgnoredMultiReleaseOrModuleClass(name) || !seenClasses.add(name)) {
                            skippedClasses++;
                            zis.closeEntry();
                            continue;
                        }
                        keptClasses++;
                    } else if (!ClassFileSanitizer.isSignatureFile(name)) {
                        keptResources++;
                    } else {
                        zis.closeEntry();
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(name));
                    int len;
                    while ((len = zis.read(buffer)) != -1) {
                        zos.write(buffer, 0, len);
                    }
                    zos.closeEntry();
                    zis.closeEntry();
                }
            }

            if (keptClasses > 0 || keptResources > 0) {
                result.add(filteredJar);
            } else {
                //noinspection ResultOfMethodCallIgnored
                filteredJar.delete();
            }
            if (logsOut != null && skippedClasses > 0) {
                logsOut.add("[D8] Deduplicated " + skippedClasses
                        + " overlapping class(es) from " + jar.getName());
            }
        }
        return result;
    }

    private static List<File> computeCompileOnlyClasspath(List<File> compileJars, List<File> dexJars) {
        if (compileJars == null || compileJars.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> dexPaths = new HashSet<>();
        if (dexJars != null) {
            for (File d : dexJars) {
                if (d != null) dexPaths.add(d.getAbsolutePath());
            }
        }
        List<File> out = new ArrayList<>();
        for (File c : compileJars) {
            if (c != null && c.exists() && !dexPaths.contains(c.getAbsolutePath())) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<File> ensureSanitizedJarsBeforeD8(
            List<File> archives,
            File sanitizeWorkDir,
            Callback callback) throws IOException {
        if (archives == null || archives.isEmpty()) {
            return Collections.emptyList();
        }
        cleanDirectory(sanitizeWorkDir);
        mkdirs(sanitizeWorkDir);
        List<File> result = new ArrayList<>(archives.size());
        for (int i = 0; i < archives.size(); i++) {
            File f = archives.get(i);
            if (f == null || !f.exists()) continue;
            File outTarget = new File(sanitizeWorkDir, i + "_" + f.getName());
            ClassFileSanitizer.ArchiveSanitizeStats stats = ClassFileSanitizer.sanitizeJarIfNeeded(f, outTarget);
            if (stats.rewritten && stats.patchedClasses > 0 && callback != null) {
                // Will be logged synchronously before D8 runs
                callback.onLogLine("[D8-SANITIZE] Patched " + stats.patchedClasses
                        + " class(es) in " + f.getName() + " before final D8.", false);
            }
            result.add(stats.effectiveFile);
        }
        return result;
    }

    private static void generateBuildConfig(
            File genDir,
            String pkg,
            String appId,
            boolean releaseBuild,
            int versionCode,
            String versionName,
            List<BuildSdParser.BuildConfigFieldSpec> customFields) throws IOException {
        if (pkg == null || !pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            return;
        }
        File pkgDir = new File(genDir, pkg.replace('.', '/'));
        mkdirs(pkgDir);
        File buildConfigFile = new File(pkgDir, "BuildConfig.java");
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public final class BuildConfig {\n");
        sb.append("    public static final boolean DEBUG = ").append(!releaseBuild).append(";\n");
        sb.append("    public static final String APPLICATION_ID = \"")
                .append(escapeJavaString(appId != null ? appId : pkg)).append("\";\n");
        sb.append("    public static final String BUILD_TYPE = \"")
                .append(releaseBuild ? "release" : "debug").append("\";\n");
        sb.append("    public static final int VERSION_CODE = ").append(versionCode).append(";\n");
        sb.append("    public static final String VERSION_NAME = \"")
                .append(escapeJavaString(versionName != null ? versionName : "1.0")).append("\";\n");
        if (customFields != null) {
            Set<String> emitted = new HashSet<>();
            emitted.add("DEBUG");
            emitted.add("APPLICATION_ID");
            emitted.add("BUILD_TYPE");
            emitted.add("VERSION_CODE");
            emitted.add("VERSION_NAME");
            for (BuildSdParser.BuildConfigFieldSpec field : customFields) {
                if (field != null && emitted.add(field.name)) {
                    sb.append("    public static final ").append(field.type)
                            .append(" ").append(field.name).append(" = ").append(field.value).append(";\n");
                }
            }
        }
        sb.append("}\n");
        try (FileOutputStream fos = new FileOutputStream(buildConfigFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String escapeJavaString(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long latestFileModified(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long latest = dir.lastModified();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                long m = c.isDirectory() ? latestFileModified(c) : c.lastModified();
                if (m > latest) latest = m;
            }
        }
        return latest;
    }

    private static String computeFilesFingerprint(List<File> files) {
        List<File> sorted = new ArrayList<>(files);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        sb.append(sorted.size()).append(':');
        for (File f : sorted) {
            sb.append(f.getAbsolutePath()).append('@').append(f.lastModified()).append(':').append(f.length()).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode()) + "_" + sorted.size();
    }

    private static String readFileTrimmed(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            return new String(readAllBytesCompat(fis), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeUtf8Quietly(File f, String content) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private static boolean isIgnoredMultiReleaseOrModuleClass(String entryName) {
        return "module-info.class".equals(entryName)
                || entryName.endsWith("/module-info.class")
                || entryName.startsWith("META-INF/versions/");
    }

    private static boolean shouldSkipJarResource(String name) {
        if (name == null || name.isEmpty()) return true;
        if (name.endsWith(".class")) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF")) return true;
        if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.endsWith(".KOTLIN_MODULE"))) {
            return true;
        }
        return false;
    }

    public static boolean launchApkInstaller(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String buildClasspath(File androidJar, List<File> compileJars) {
        StringBuilder cp = new StringBuilder(androidJar.getAbsolutePath());
        if (compileJars != null) {
            for (File jar : compileJars) {
                if (jar != null && jar.exists()) {
                    cp.append(File.pathSeparator).append(jar.getAbsolutePath());
                }
            }
        }
        return cp.toString();
    }

    private static File findAssetsDir(File projectDir) {
        File mainAssets = new File(projectDir, "src/main/assets");
        if (mainAssets.exists() && mainAssets.isDirectory()) return mainAssets;
        File rootAssets = new File(projectDir, "assets");
        return rootAssets.exists() && rootAssets.isDirectory() ? rootAssets : null;
    }

    private static File findManifestFile(File projectDir) {
        File rootManifest = new File(projectDir, "AndroidManifest.xml");
        if (rootManifest.exists()) return rootManifest;
        File mainManifest = new File(projectDir, "src/main/AndroidManifest.xml");
        return mainManifest.exists() ? mainManifest : rootManifest;
    }

    private static File findSrcDir(File projectDir) {
        File srcMainJava = new File(projectDir, "src/main/java");
        if (srcMainJava.exists()) return srcMainJava;
        return new File(projectDir, "src");
    }

    private static File findResDir(File projectDir) {
        File mainRes = new File(projectDir, "src/main/res");
        if (mainRes.exists()) return mainRes;
        return new File(projectDir, "res");
    }

    private static File findMainActivityFile(File projectDir) {
        List<File> all = collectFilesByExtension(findSrcDir(projectDir), "MainActivity.java");
        if (!all.isEmpty()) return all.get(0);
        return null;
    }

    private static List<File> collectFilesByExtension(File dir, String suffix) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.exists()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                out.addAll(collectFilesByExtension(f, suffix));
            } else if (f.getName().endsWith(suffix)) {
                out.add(f);
            }
        }
        return out;
    }

    private static List<File> collectAllFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.exists()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isDirectory()) {
                out.addAll(collectAllFiles(f));
            } else {
                out.add(f);
            }
        }
        return out;
    }

    private static void cleanDirectory(File dir) {
        if (dir != null && dir.exists()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (c.isDirectory()) {
                        cleanDirectory(c);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    c.delete();
                }
            }
        }
    }

    private static void mkdirs(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private static String joinCommandRedacted(List<String> command) {
        List<String> safe = new ArrayList<>(command);
        for (int i = 0; i < safe.size() - 1; i++) {
            String arg = safe.get(i);
            if ("--ks-pass".equals(arg) || "--key-pass".equals(arg)) {
                safe.set(i + 1, "pass:********");
                i++;
            }
        }
        return joinCommand(safe);
    }

    private static String joinCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(cmd.get(i));
        }
        return sb.toString();
    }

    private static long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    private volatile Stage timingStage;
    private volatile long timingStageStartMs;
    private volatile long timingBuildStartMs;

    /** Logs how long the previous stage took so slow stages are visible in the build log. */
    private void logStageTiming(Callback callback, boolean finished) {
        long now = System.currentTimeMillis();
        Stage prev = timingStage;
        if (prev != null) {
            postLog(callback, String.format(Locale.US, "[TIMING] %s: %.1fs",
                    prev.label, (now - timingStageStartMs) / 1000.0), false);
        }
        if (finished && timingBuildStartMs > 0) {
            postLog(callback, String.format(Locale.US, "[TIMING] TOTAL: %.1fs",
                    (now - timingBuildStartMs) / 1000.0), false);
            timingStage = null;
        }
    }

    private void postStage(Callback callback, Stage stage) {
        logStageTiming(callback, false);
        timingStage = stage;
        timingStageStartMs = System.currentTimeMillis();
        SandrixBuildService.updateStage(appContext, stage.label);
        flushLogsNow(callback);
        mainHandler.post(() -> callback.onStageChanged(stage));
    }

    private void postLog(Callback callback, String line, boolean isError) {
        if (line != null && !line.trim().isEmpty()) {
            com.example.sandrix.logcat.SandrixLogcatBus.log(isError ? "E" : "I", "SandrixBuild", line);
        }
        List<BuildLogLine> immediateBatch = null;
        boolean needSchedule = false;
        synchronized (logLock) {
            pendingLogBatch.add(new BuildLogLine(line, isError));
            long now = System.currentTimeMillis();
            if (isError || pendingLogBatch.size() >= LOG_BATCH_LIMIT
                    || (now - lastLogFlushMs) >= LOG_FLUSH_INTERVAL_MS) {
                immediateBatch = new ArrayList<>(pendingLogBatch);
                pendingLogBatch.clear();
                lastLogFlushMs = now;
                logFlushScheduled = false;
            } else if (!logFlushScheduled) {
                logFlushScheduled = true;
                needSchedule = true;
            }
        }
        if (immediateBatch != null) {
            final List<BuildLogLine> dispatch = immediateBatch;
            mainHandler.post(() -> callback.onLogBatch(dispatch));
        } else if (needSchedule) {
            mainHandler.postDelayed(() -> flushLogsNow(callback), LOG_FLUSH_INTERVAL_MS);
        }
    }

    private void flushLogsNow(Callback callback) {
        List<BuildLogLine> batch = null;
        synchronized (logLock) {
            logFlushScheduled = false;
            if (!pendingLogBatch.isEmpty()) {
                batch = new ArrayList<>(pendingLogBatch);
                pendingLogBatch.clear();
                lastLogFlushMs = System.currentTimeMillis();
            }
        }
        if (batch != null) {
            final List<BuildLogLine> dispatch = batch;
            mainHandler.post(() -> callback.onLogBatch(dispatch));
        }
    }

    private void postDiagnostic(Callback callback, BuildDiagnostic diagnostic) {
        mainHandler.post(() -> callback.onDiagnostic(diagnostic));
    }

    private void postFinish(Callback callback, BuildResult result) {
        logStageTiming(callback, true);
        flushLogsNow(callback);
        mainHandler.post(() -> callback.onFinished(result));
    }

    private static final class SigningMaterial {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        SigningMaterial(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    private interface LineConsumer {
        void onLine(String line);
    }

    private static final class LineDispatchWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final LineConsumer consumer;

        LineDispatchWriter(LineConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            for (int i = 0; i < len; i++) {
                char c = cbuf[off + i];
                if (c == '\n') {
                    flushLine();
                } else if (c != '\r') {
                    buffer.append(c);
                }
            }
        }

        void flushLine() {
            if (buffer.length() > 0) {
                consumer.onLine(buffer.toString());
                buffer.setLength(0);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            flushLine();
        }
    }

    public enum Stage {
        CONFIG("Parsing build.sd"),
        SYNC_PATCH("Syncing Manifest & XML"),
        STATIC_CHECK("Pre-flight Diagnostics"),
        DEPENDENCIES("Resolving Dependencies"),
        AAPT2("AAPT2 Resource Link"),
        JAVAC("Java Compilation (ECJ 17)"),
        R8("R8 Optimization & Shrinking"),
        D8("Dexing (d8)"),
        AAB("Packaging Signed AAB"),
        APKSIGNER("Signing APK (apksigner)"),
        COMPLETE("Build Complete");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    public static final class BuildOptions {
        public final boolean autoInstallApk;
        public final boolean releaseBuild;
        public final boolean cleanBuild;
        public final boolean buildAab;
        public final boolean forceR8Optimize;
        public final boolean shrinkResources;

        public BuildOptions(
                boolean autoInstallApk,
                boolean releaseBuild,
                boolean cleanBuild,
                boolean buildAab,
                boolean forceR8Optimize,
                boolean shrinkResources) {
            this.autoInstallApk = autoInstallApk;
            this.releaseBuild = releaseBuild;
            this.cleanBuild = cleanBuild;
            this.buildAab = buildAab;
            this.forceR8Optimize = forceR8Optimize;
            this.shrinkResources = shrinkResources;
        }
    }

    public static final class BuildDiagnostic {
        public enum Severity {
            ERROR,
            WARNING
        }

        public final Severity severity;
        public final String filePath;
        public final int lineNumber;
        public final String message;
        public final String sourceTool;

        public BuildDiagnostic(
                Severity severity,
                String filePath,
                int lineNumber,
                String message,
                String sourceTool) {
            this.severity = severity;
            this.filePath = filePath;
            this.lineNumber = Math.max(1, lineNumber);
            this.message = message;
            this.sourceTool = sourceTool;
        }
    }

    public static final class BuildResult {
        public final boolean apkBuilt;
        public final boolean validationPassed;
        public final boolean nativeToolchainMissing;
        public final File apkFile;
        public final File aabFile;
        public final File mappingFile;
        public final List<BuildDiagnostic> diagnostics;
        public final long elapsedMs;
        public final String summary;

        public BuildResult(
                boolean apkBuilt,
                boolean validationPassed,
                boolean nativeToolchainMissing,
                File apkFile,
                List<BuildDiagnostic> diagnostics,
                long elapsedMs,
                String summary) {
            this(apkBuilt, validationPassed, nativeToolchainMissing, apkFile, null, null, diagnostics, elapsedMs, summary);
        }

        public BuildResult(
                boolean apkBuilt,
                boolean validationPassed,
                boolean nativeToolchainMissing,
                File apkFile,
                File aabFile,
                File mappingFile,
                List<BuildDiagnostic> diagnostics,
                long elapsedMs,
                String summary) {
            this.apkBuilt = apkBuilt;
            this.validationPassed = validationPassed;
            this.nativeToolchainMissing = nativeToolchainMissing;
            this.apkFile = apkFile;
            this.aabFile = aabFile;
            this.mappingFile = mappingFile;
            this.diagnostics = diagnostics;
            this.elapsedMs = elapsedMs;
            this.summary = summary;
        }
    }

    public interface Callback {
        void onStageChanged(Stage stage);
        void onLogLine(String line, boolean isError);
        default void onLogBatch(List<BuildLogLine> lines) {
            if (lines != null) {
                for (BuildLogLine l : lines) {
                    onLogLine(l.text, l.isError);
                }
            }
        }
        void onDiagnostic(BuildDiagnostic diagnostic);
        void onFinished(BuildResult result);
    }
}
