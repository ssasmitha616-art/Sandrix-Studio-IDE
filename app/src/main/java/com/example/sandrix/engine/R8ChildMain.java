package com.example.sandrix.engine;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.Origin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point for running R8 in its own OS process (started with app_process), so an
 * out-of-memory failure kills only this child and never the IDE app.
 * Protocol (stdout): SANDRIX_R8_STARTED, then log lines, then SANDRIX_R8_OK or
 * SANDRIX_R8_RESOURCE. Exit codes: 0 ok, 1 R8 error, 3 memory/stack exhaustion.
 */
public final class R8ChildMain {

    private R8ChildMain() {}

    public static void main(String[] args) {
        int code = 1;
        try {
            System.out.println("SANDRIX_R8_STARTED");
            System.out.flush();
            int minSdk = 24, threads = 2;
            boolean release = true, obfuscate = true, shrink = true, optimize = false;
            File androidJar = null, dexOut = null, mapping = null;
            List<Path> rules = new ArrayList<>();
            List<Path> classpath = new ArrayList<>();
            List<Path> inputs = new ArrayList<>();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(args[0]), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq);
                    String v = line.substring(eq + 1);
                    switch (k) {
                        case "minSdk": minSdk = Integer.parseInt(v); break;
                        case "threads": threads = Integer.parseInt(v); break;
                        case "release": release = Boolean.parseBoolean(v); break;
                        case "obfuscate": obfuscate = Boolean.parseBoolean(v); break;
                        case "shrink": shrink = Boolean.parseBoolean(v); break;
                        case "optimize": optimize = Boolean.parseBoolean(v); break;
                        case "androidJar": androidJar = new File(v); break;
                        case "dexOut": dexOut = new File(v); break;
                        case "mapping": mapping = new File(v); break;
                        case "rule": rules.add(new File(v).toPath()); break;
                        case "cp": classpath.add(new File(v).toPath()); break;
                        case "in": inputs.add(new File(v).toPath()); break;
                        default: break;
                    }
                }
            }

            DiagnosticsHandler handler = new DiagnosticsHandler() {
                @Override public void error(Diagnostic d) {
                    System.out.println("[R8 ERROR] " + d.getDiagnosticMessage());
                }
                @Override public void warning(Diagnostic d) {
                    System.out.println("[R8 WARN] " + d.getDiagnosticMessage());
                }
                @Override public void info(Diagnostic d) {
                    System.out.println("[R8] " + d.getDiagnosticMessage());
                }
            };

            R8Command.Builder b = R8Command.builder(handler)
                    .setMinApiLevel(Math.max(1, minSdk))
                    .setMode(release ? CompilationMode.RELEASE : CompilationMode.DEBUG)
                    .setOutput(dexOut.toPath(), OutputMode.DexIndexed)
                    .setProguardMapOutputPath(mapping.toPath())
                    .setDisableTreeShaking(!shrink)
                    .setDisableMinification(!obfuscate)
                    .addProguardConfiguration(
                            R8Optimizer.buildDefaultAndroidRules(obfuscate, shrink, optimize),
                            Origin.unknown());
            if (androidJar != null && androidJar.isFile()) b.addLibraryFiles(androidJar.toPath());
            for (Path p : classpath) b.addClasspathFiles(p);
            for (Path p : inputs) b.addProgramFiles(p);
            if (!rules.isEmpty()) b.addProguardConfigurationFiles(rules);

            final AtomicInteger idx = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), run -> {
                Thread t = new Thread(null, run, "SandrixR8c-" + idx.incrementAndGet(), 32L * 1024L * 1024L);
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, e) -> { });
                return t;
            });
            try {
                R8.run(b.build(), pool);
            } finally {
                pool.shutdownNow();
            }
            System.out.println("SANDRIX_R8_OK");
            code = 0;
        } catch (Throwable t) {
            boolean resource = false;
            Throwable root = t;
            for (int i = 0; i < 10; i++) {
                if (root instanceof OutOfMemoryError || root instanceof StackOverflowError) resource = true;
                Throwable next = root.getCause();
                if (next == null || next == root) break;
                root = next;
            }
            if (resource) {
                System.out.println("SANDRIX_R8_RESOURCE");
                code = 3;
            } else {
                String msg = root.getMessage() != null ? root.getMessage() : root.toString();
                System.out.println("[R8 ERROR] " + root.getClass().getSimpleName() + ": " + msg);
                code = 1;
            }
        }
        System.out.flush();
        System.exit(code);
    }
}
