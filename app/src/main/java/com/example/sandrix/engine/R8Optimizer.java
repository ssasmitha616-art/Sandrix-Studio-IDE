package com.example.sandrix.engine;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.Origin;
import com.example.sandrix.config.BuildSdParser;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full R8 Shrinking, Obfuscation, and Optimization driver for Sandrix Studio IDE.
 *
 * <p>Combines default Android R8 rules, AAPT2-generated component keep rules
 * ({@code --proguard}), AAR consumer ProGuard rules, and user-defined project
 * {@code proguard-rules.pro}, invoking R8 8.7.18 to emit shrunk/obfuscated DEX
 * files plus {@code mapping.txt}.</p>
 */
public final class R8Optimizer {

    private R8Optimizer() {}

    private static final int CHILD_OK = 0;
    private static final int CHILD_ERROR = 1;
    private static final int CHILD_RESOURCE = 2;
    private static final int CHILD_UNAVAILABLE = 3;

    private static volatile Context appContext;

    /** Must be called once so R8 can be launched in its own process (crash isolation). */
    public static void init(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public interface LogListener {
        void onLog(String message, boolean isError);
    }

    public static final class OptimizationResult {
        public final boolean success;
        public final int exitCode;
        public final File dexOutDir;
        public final File mappingFile;
        public final long inputBytecodeBytes;
        public final long outputDexBytes;
        public final int keepRuleFilesCount;
        public final String errorSummary;
        /** True when R8 failed from memory/stack exhaustion; the caller should fall back to D8. */
        public boolean resourceFailure;

        public OptimizationResult(
                boolean success,
                int exitCode,
                File dexOutDir,
                File mappingFile,
                long inputBytecodeBytes,
                long outputDexBytes,
                int keepRuleFilesCount,
                String errorSummary) {
            this.success = success;
            this.exitCode = exitCode;
            this.dexOutDir = dexOutDir;
            this.mappingFile = mappingFile;
            this.inputBytecodeBytes = inputBytecodeBytes;
            this.outputDexBytes = outputDexBytes;
            this.keepRuleFilesCount = keepRuleFilesCount;
            this.errorSummary = errorSummary;
        }
    }

    public static OptimizationResult optimize(
            File projectDir,
            BuildSdParser.BuildSdConfig config,
            int minSdk,
            boolean releaseBuild,
            boolean obfuscate,
            boolean shrinkCode,
            boolean optimizeBytecode,
            File androidJar,
            File dexOutDir,
            File mappingFile,
            File aaptKeepRulesFile,
            List<File> classFiles,
            List<File> rawLibraryJars,
            List<File> compileOnlyJars,
            List<File> consumerProguardFiles,
            List<BuildEngineTask.BuildDiagnostic> diagnosticsOut,
            LogListener logListener) {

        long maxMem = Runtime.getRuntime().maxMemory();
        long inputBytes = computeTotalSize(classFiles) + computeTotalSize(rawLibraryJars);
        if (maxMem < 160L * 1024L * 1024L) {
            if (logListener != null) {
                logListener.onLog("[R8] Only " + (maxMem / (1024L * 1024L))
                        + " MB heap available; skipping R8 and using D8 instead.", false);
            }
            OptimizationResult skip = new OptimizationResult(false, 1, dexOutDir, mappingFile,
                    inputBytes, 0L, 0, "Not enough memory for R8.");
            skip.resourceFailure = true;
            return skip;
        }

        // Memory-adaptive: small heaps skip the optimizer pass and use fewer worker threads.
        boolean lowMem = maxMem < 600L * 1024L * 1024L;
        boolean optimizeFirst = optimizeBytecode && !lowMem;
        if (lowMem && logListener != null) {
            logListener.onLog("[R8] Low-memory mode (" + (maxMem / (1024L * 1024L))
                    + " MB heap): optimizer pass disabled, fewer threads.", false);
        }
        OptimizationResult result = optimizeOnce(projectDir, config, minSdk, releaseBuild, obfuscate,
                shrinkCode, optimizeFirst, androidJar, dexOutDir, mappingFile, aaptKeepRulesFile,
                classFiles, rawLibraryJars, compileOnlyJars, consumerProguardFiles, diagnosticsOut,
                logListener, lowMem ? 2 : 4);

        if (!result.success && result.resourceFailure && optimizeFirst) {
            if (logListener != null) {
                logListener.onLog("[R8] Ran out of memory; retrying without the optimizer pass...", false);
            }
            clearOutputs(dexOutDir, mappingFile);
            result = optimizeOnce(projectDir, config, minSdk, releaseBuild, obfuscate,
                    shrinkCode, false, androidJar, dexOutDir, mappingFile, aaptKeepRulesFile,
                    classFiles, rawLibraryJars, compileOnlyJars, consumerProguardFiles, diagnosticsOut,
                    logListener, 1);
        }
        if (!result.success) {
            clearOutputs(dexOutDir, mappingFile);
        }
        return result;
    }

    /**
     * Runs R8 in a separate OS process (own heap). Returns CHILD_UNAVAILABLE when no process
     * could be started so the caller can fall back to running R8 in-process.
     */
    private static int runR8Child(
            int minSdk, boolean release, boolean obfuscate, boolean shrink, boolean optimize,
            File androidJar, File dexOutDir, File mappingFile, List<Path> rulePaths,
            List<File> compileOnlyJars, List<File> classFiles, List<File> libJars, int threads,
            List<BuildEngineTask.BuildDiagnostic> diagnosticsOut, LogListener logListener,
            String[] errorOut) {
        Context ctx = appContext;
        if (ctx == null) return CHILD_UNAVAILABLE;
        File appProcess = new File("/system/bin/app_process");
        if (!appProcess.exists()) return CHILD_UNAVAILABLE;
        String apk = ctx.getPackageCodePath();
        if (apk == null || !new File(apk).isFile()) return CHILD_UNAVAILABLE;

        File job = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("minSdk=").append(minSdk).append('\n');
            sb.append("release=").append(release).append('\n');
            sb.append("obfuscate=").append(obfuscate).append('\n');
            sb.append("shrink=").append(shrink).append('\n');
            sb.append("optimize=").append(optimize).append('\n');
            sb.append("threads=").append(threads).append('\n');
            if (androidJar != null) sb.append("androidJar=").append(androidJar.getAbsolutePath()).append('\n');
            sb.append("dexOut=").append(dexOutDir.getAbsolutePath()).append('\n');
            sb.append("mapping=").append(mappingFile.getAbsolutePath()).append('\n');
            for (Path p : rulePaths) sb.append("rule=").append(p.toString()).append('\n');
            if (compileOnlyJars != null) {
                for (File f : compileOnlyJars) {
                    if (f != null && f.isFile()) sb.append("cp=").append(f.getAbsolutePath()).append('\n');
                }
            }
            if (classFiles != null) {
                for (File f : classFiles) {
                    if (f != null && f.isFile()) sb.append("in=").append(f.getAbsolutePath()).append('\n');
                }
            }
            if (libJars != null) {
                for (File f : libJars) {
                    if (f != null && f.isFile()) sb.append("in=").append(f.getAbsolutePath()).append('\n');
                }
            }
            job = File.createTempFile("r8job", ".txt", ctx.getCacheDir());
            writeUtf8(job, sb.toString());

            // Child heap: ~60% of currently free RAM, clamped to a sane range.
            long heapMb = 384L;
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(mi);
                    heapMb = Math.max(192L, Math.min(1536L, (long) (mi.availMem * 0.6 / (1024L * 1024L))));
                }
            } catch (Throwable ignored) {
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(appProcess.getAbsolutePath());
            cmd.add("-Xmx" + heapMb + "m");
            cmd.add("/system/bin");
            cmd.add("com.example.sandrix.engine.R8ChildMain");
            cmd.add(job.getAbsolutePath());

            if (logListener != null) {
                logListener.onLog("[R8] Starting isolated R8 process (heap " + heapMb + " MB)...", false);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("CLASSPATH", apk);
            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                return CHILD_UNAVAILABLE;
            }

            boolean started = false;
            boolean ok = false;
            boolean resource = false;
            String lastError = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("SANDRIX_R8_STARTED")) { started = true; continue; }
                    if (line.equals("SANDRIX_R8_OK")) { ok = true; continue; }
                    if (line.equals("SANDRIX_R8_RESOURCE")) { resource = true; continue; }
                    if (line.contains("OutOfMemoryError")) resource = true;
                    boolean isError = line.startsWith("[R8 ERROR]");
                    if (isError) {
                        lastError = line.substring("[R8 ERROR]".length()).trim();
                        if (diagnosticsOut != null) {
                            diagnosticsOut.add(new BuildEngineTask.BuildDiagnostic(
                                    BuildEngineTask.BuildDiagnostic.Severity.ERROR, "R8", 1, lastError, "R8"));
                        }
                    }
                    if (logListener != null) logListener.onLog(line, isError);
                }
            }
            int rc = proc.waitFor();

            if (!started) {
                // The child never reached our code (exec/classpath problem): let the caller fall back.
                return CHILD_UNAVAILABLE;
            }
            if (ok && rc == 0) return CHILD_OK;
            if (resource || rc >= 128 || rc == 3) return CHILD_RESOURCE;
            errorOut[0] = lastError != null ? lastError : "R8 failed (exit " + rc + ").";
            return CHILD_ERROR;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            errorOut[0] = "R8 interrupted.";
            return CHILD_ERROR;
        } catch (Throwable t) {
            return CHILD_UNAVAILABLE;
        } finally {
            if (job != null) job.delete();
        }
    }

    private static void clearOutputs(File dexOutDir, File mappingFile) {
        File[] old = dexOutDir != null ? dexOutDir.listFiles() : null;
        if (old != null) {
            for (File f : old) {
                if (f.isFile()) f.delete();
            }
        }
        if (mappingFile != null && mappingFile.isFile()) mappingFile.delete();
    }

    private static OptimizationResult optimizeOnce(
            File projectDir,
            BuildSdParser.BuildSdConfig config,
            int minSdk,
            boolean releaseBuild,
            boolean obfuscate,
            boolean shrinkCode,
            boolean optimizeBytecode,
            File androidJar,
            File dexOutDir,
            File mappingFile,
            File aaptKeepRulesFile,
            List<File> classFiles,
            List<File> rawLibraryJars,
            List<File> compileOnlyJars,
            List<File> consumerProguardFiles,
            List<BuildEngineTask.BuildDiagnostic> diagnosticsOut,
            LogListener logListener,
            int r8Threads) {

        long inputBytes = computeTotalSize(classFiles) + computeTotalSize(rawLibraryJars);
        List<Path> rulePaths = new ArrayList<>();
        int ruleFileCount = 0;

        try {
            if (!dexOutDir.exists() && !dexOutDir.mkdirs()) {
                return new OptimizationResult(false, 1, dexOutDir, mappingFile, inputBytes, 0L, 0,
                        "Unable to create R8 output directory.");
            }
            File mapParent = mappingFile.getParentFile();
            if (mapParent != null && !mapParent.exists() && !mapParent.mkdirs()) {
                return new OptimizationResult(false, 1, dexOutDir, mappingFile, inputBytes, 0L, 0,
                        "Unable to create R8 mapping directory.");
            }

            if (aaptKeepRulesFile != null && aaptKeepRulesFile.isFile() && aaptKeepRulesFile.length() > 0) {
                rulePaths.add(aaptKeepRulesFile.toPath());
                ruleFileCount++;
            }

            if (consumerProguardFiles != null) {
                for (File pg : consumerProguardFiles) {
                    if (pg != null && pg.isFile() && pg.length() > 0) {
                        rulePaths.add(pg.toPath());
                        ruleFileCount++;
                    }
                }
            }

            // Include user proguard files configured in build.sd or default proguard-rules.pro
            boolean foundProjectPro = false;
            if (config != null && config.proguardFiles != null) {
                for (String rel : config.proguardFiles) {
                    if (rel == null || rel.trim().isEmpty()) continue;
                    File f = new File(rel);
                    if (!f.isAbsolute() && projectDir != null) {
                        f = new File(projectDir, rel);
                    }
                    if (f.isFile() && f.length() > 0) {
                        rulePaths.add(f.toPath());
                        ruleFileCount++;
                        foundProjectPro = true;
                    }
                }
            }
            if (!foundProjectPro && projectDir != null) {
                File defaultPro = new File(projectDir, "proguard-rules.pro");
                if (defaultPro.isFile() && defaultPro.length() > 0) {
                    rulePaths.add(defaultPro.toPath());
                    ruleFileCount++;
                }
            }

            List<String> defaultRules = buildDefaultAndroidRules(obfuscate, shrinkCode, optimizeBytecode);

            if (logListener != null) {
                logListener.onLog("[R8] Running Full R8 ("
                        + "shrink=" + shrinkCode
                        + ", obfuscate=" + obfuscate
                        + ", optimize=" + optimizeBytecode
                        + ", ruleFiles=" + ruleFileCount + ")...", false);
            }

            boolean ranInChild = false;
            {
                String[] childErr = new String[1];
                int childRc = runR8Child(minSdk, releaseBuild, obfuscate, shrinkCode, optimizeBytecode,
                        androidJar, dexOutDir, mappingFile, rulePaths, compileOnlyJars, classFiles,
                        rawLibraryJars, r8Threads, diagnosticsOut, logListener, childErr);
                if (childRc == CHILD_OK) {
                    ranInChild = true;
                } else if (childRc == CHILD_RESOURCE) {
                    OptimizationResult res = new OptimizationResult(false, 3, dexOutDir, mappingFile,
                            inputBytes, 0L, ruleFileCount, "R8 ran out of memory.");
                    res.resourceFailure = true;
                    return res;
                } else if (childRc == CHILD_ERROR) {
                    return new OptimizationResult(false, 1, dexOutDir, mappingFile, inputBytes, 0L,
                            ruleFileCount, childErr[0] != null ? childErr[0] : "R8 failed.");
                }
                // CHILD_UNAVAILABLE: could not start a separate process; run in-process below.
            }

            if (!ranInChild) {
            DiagnosticsHandler r8Diagnostics = new DiagnosticsHandler() {
                @Override
                public void error(Diagnostic error) {
                    String msg = error.getDiagnosticMessage();
                    if (logListener != null) {
                        logListener.onLog("[R8 ERROR] " + msg, true);
                    }
                    if (diagnosticsOut != null) {
                        diagnosticsOut.add(new BuildEngineTask.BuildDiagnostic(
                                BuildEngineTask.BuildDiagnostic.Severity.ERROR,
                                error.getOrigin() != null ? error.getOrigin().toString() : "R8",
                                1,
                                msg,
                                "R8"));
                    }
                }

                @Override
                public void warning(Diagnostic warning) {
                    if (logListener != null) {
                        logListener.onLog("[R8 WARN] " + warning.getDiagnosticMessage(), false);
                    }
                }

                @Override
                public void info(Diagnostic info) {
                    if (logListener != null) {
                        logListener.onLog("[R8] " + info.getDiagnosticMessage(), false);
                    }
                }
            };

            R8Command.Builder builder = R8Command.builder(r8Diagnostics)
                    .setMinApiLevel(Math.max(1, minSdk))
                    .setMode(releaseBuild ? CompilationMode.RELEASE : CompilationMode.DEBUG)
                    .setOutput(dexOutDir.toPath(), OutputMode.DexIndexed)
                    .setProguardMapOutputPath(mappingFile.toPath())
                    .setDisableTreeShaking(!shrinkCode)
                    .setDisableMinification(!obfuscate)
                    .addProguardConfiguration(defaultRules, Origin.unknown());

            if (androidJar != null && androidJar.isFile()) {
                builder.addLibraryFiles(androidJar.toPath());
            }

            if (compileOnlyJars != null) {
                for (File cp : compileOnlyJars) {
                    if (cp != null && cp.isFile()) {
                        builder.addClasspathFiles(cp.toPath());
                    }
                }
            }

            if (classFiles != null) {
                for (File cf : classFiles) {
                    if (cf != null && cf.isFile()) {
                        builder.addProgramFiles(cf.toPath());
                    }
                }
            }

            if (rawLibraryJars != null) {
                for (File jar : rawLibraryJars) {
                    if (jar != null && jar.isFile()) {
                        builder.addProgramFiles(jar.toPath());
                    }
                }
            }

            if (!rulePaths.isEmpty()) {
                builder.addProguardConfigurationFiles(rulePaths);
            }

            long r8StartMs = System.currentTimeMillis();
            ExecutorService r8Pool = newR8Executor(r8Threads);
            try {
                R8.run(builder.build(), r8Pool);
            } finally {
                r8Pool.shutdownNow();
            }
            if (logListener != null) {
                logListener.onLog(String.format(Locale.US, "[R8] Finished in %.1fs",
                        (System.currentTimeMillis() - r8StartMs) / 1000.0), false);
            }
            }

            File[] dexOutputs = dexOutDir.listFiles((dir, name) -> name.endsWith(".dex"));
            if (dexOutputs == null || dexOutputs.length == 0) {
                return new OptimizationResult(false, 1, dexOutDir, mappingFile, inputBytes, 0L, ruleFileCount,
                        "R8 completed without producing classes.dex.");
            }
            Arrays.sort(dexOutputs);
            long outputBytes = 0L;
            for (File d : dexOutputs) {
                outputBytes += d.length();
            }

            if (!mappingFile.exists()) {
                writeUtf8(mappingFile, "# R8 generated mapping (no symbols obfuscated)\n");
            }

            if (logListener != null) {
                int reductionPct = inputBytes > 0
                        ? (int) Math.max(0L, Math.round(((inputBytes - outputBytes) * 100.0) / inputBytes))
                        : 0;
                logListener.onLog(String.format(Locale.US,
                        "[R8] Success: %d dex file(s), input %.1f KB -> DEX %.1f KB (%d%% smaller), mapping -> %s",
                        dexOutputs.length,
                        inputBytes / 1024.0,
                        outputBytes / 1024.0,
                        reductionPct,
                        mappingFile.getName()), false);
            }

            return new OptimizationResult(true, 0, dexOutDir, mappingFile, inputBytes, outputBytes, ruleFileCount, null);
        } catch (Throwable t) {
            Throwable root = t;
            boolean resource = false;
            for (int i = 0; i < 10; i++) {
                if (root instanceof OutOfMemoryError || root instanceof StackOverflowError) {
                    resource = true;
                }
                Throwable next = root.getCause();
                if (next == null || next == root) break;
                root = next;
            }
            String msg = root.getMessage() != null ? root.getMessage() : root.toString();
            if (resource) {
                msg = "R8 ran out of memory/stack.";
            }
            if (logListener != null) {
                logListener.onLog("[R8 ERROR] " + root.getClass().getSimpleName() + ": " + msg, !resource);
            }
            OptimizationResult failed = new OptimizationResult(
                    false, 1, dexOutDir, mappingFile, inputBytes, 0L, ruleFileCount, msg);
            failed.resourceFailure = resource;
            return failed;
        }
    }

    public static List<String> buildDefaultAndroidRules(
            boolean obfuscate,
            boolean shrinkCode,
            boolean optimizeBytecode) {
        List<String> rules = new ArrayList<>();
        if (!shrinkCode) rules.add("-dontshrink");
        if (!obfuscate) rules.add("-dontobfuscate");
        if (!optimizeBytecode) rules.add("-dontoptimize");

        Collections.addAll(rules,
                "-optimizationpasses 1",
                "-allowaccessmodification",
                "-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Record",
                "-renamesourcefileattribute SourceFile",
                "-dontwarn javax.**",
                "-dontwarn sun.misc.**",
                "-dontwarn org.codehaus.**",
                "-dontwarn org.jetbrains.**",
                "-dontwarn kotlin.**",
                "-dontwarn kotlinx.coroutines.**",
                "-dontwarn java.lang.invoke.**",
                "-dontwarn android.os.OutcomeReceiver",
                "-dontwarn android.window.**",
                "-dontwarn android.app.OnActivityResultCaller",
                "-keep public class * extends android.app.Application",
                "-keep public class * extends android.app.Activity",
                "-keep public class * extends android.app.Service",
                "-keep public class * extends android.content.BroadcastReceiver",
                "-keep public class * extends android.content.ContentProvider",
                "-keep public class * extends android.app.backup.BackupAgentHelper",
                "-keep public class * extends android.preference.Preference",
                "-keep public class * extends android.view.View { public <init>(android.content.Context); public <init>(android.content.Context, android.util.AttributeSet); public <init>(android.content.Context, android.util.AttributeSet, int); public void set*(...); }",
                "-keepclasseswithmembers class * { public <init>(android.content.Context, android.util.AttributeSet); }",
                "-keepclasseswithmembers class * { public <init>(android.content.Context, android.util.AttributeSet, int); }",
                "-keepclassmembers class * extends android.content.Context { public void *(android.view.View); }",
                "-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }",
                "-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }",
                "-keep @androidx.annotation.Keep class * { *; }",
                "-keepclassmembers class * { @androidx.annotation.Keep *; }",
                "-keepclasseswithmembers class * { @androidx.annotation.Keep <methods>; }",
                "-keep class **.R$* { public static <fields>; }",
                "-keepclassmembers class * implements java.io.Serializable { static final long serialVersionUID; private void writeObject(java.io.ObjectOutputStream); private void readObject(java.io.ObjectInputStream); java.lang.Object writeReplace(); java.lang.Object readResolve(); }"
        );
        return rules;
    }

    /**
     * R8 worker threads with a large stack. Default pool threads are small on Android and a
     * StackOverflowError there is uncaught, which closes the whole app.
     */
    private static ExecutorService newR8Executor(int maxThreads) {
        int threads = Math.max(1, Math.min(maxThreads, Runtime.getRuntime().availableProcessors()));
        final AtomicInteger idx = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(null, r, "SandrixR8-" + idx.incrementAndGet(), 32L * 1024L * 1024L);
            t.setDaemon(true);
            // Errors are reported to R8's caller through the task Future; never let them kill the app.
            t.setUncaughtExceptionHandler((th, e) -> { });
            return t;
        });
    }

    private static long computeTotalSize(List<File> files) {
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) {
            if (f != null && f.isFile()) {
                total += f.length();
            }
        }
        return total;
    }

    private static void writeUtf8(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String summarizeMappingFile(File mappingFile, int maxLines) {
        if (mappingFile == null || !mappingFile.isFile()) {
            return "No mapping.txt available.";
        }
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        int classMappings = 0;
        byte[] buf = new byte[(int) Math.min(256 * 1024L, mappingFile.length())];
        try (FileInputStream fis = new FileInputStream(mappingFile)) {
            int n = fis.read(buf);
            if (n > 0) {
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                String[] all = text.split("\\r?\\n");
                for (String line : all) {
                    if (!line.startsWith("#") && !line.startsWith(" ") && line.contains(" -> ")) {
                        classMappings++;
                    }
                    if (lines < maxLines) {
                        sb.append(line).append('\n');
                        lines++;
                    }
                }
                if (all.length > maxLines) {
                    sb.append("\n... (").append(all.length - maxLines).append(" more lines, ")
                            .append(classMappings).append(" obfuscated classes)");
                }
            }
        } catch (IOException e) {
            return "Failed to read mapping.txt: " + e.getMessage();
        }
        return sb.toString();
    }
}
