package com.example.sandrix;

import com.android.bundle.Config;
import com.android.tools.build.bundletool.commands.ValidateBundleCommand;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.debug.DebugModels;
import com.example.sandrix.debug.DebugSessionController;
import com.example.sandrix.debug.JdwpClient;
import com.example.sandrix.debug.LocalJavaDebugEngine;
import com.example.sandrix.engine.AabBuilder;
import com.example.sandrix.engine.ApkAnalyzer;
import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.engine.KeystoreGenerator;
import com.example.sandrix.engine.R8Optimizer;

import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/**
 * End-to-end verification suite for Phase 5 Sandrix Studio features:
 * 1. Java 17 (ECJ 3.33.0 + D8 8.7.18) records, sealed interfaces, switch expressions, text blocks
 * 2. R8 Full Optimization (shrinking dead code, obfuscation, optimization passes, mapping.txt)
 * 3. Signed Android App Bundle (.aab) packaging, BundleConfig.pb, bundletool validation & JarFile verification
 * 4. Advanced Debugger (Local Step VM conditional breakpoints, watches, variable edits, step controls + JDWP TCP client)
 */
public class Phase5AdvancedFeaturesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File resolveAndroidJar() {
        File sdkJar = new File("/opt/android/sdk/platforms/android-36/android.jar");
        if (sdkJar.isFile()) return sdkJar;
        String sdkRoot = System.getenv("ANDROID_SDK_ROOT");
        if (sdkRoot != null) {
            File alt = new File(sdkRoot, "platforms/android-36/android.jar");
            if (alt.isFile()) return alt;
        }
        return null;
    }

    private static File resolveAapt2() {
        File aapt2 = new File("/opt/android/sdk/build-tools/35.0.0/aapt2");
        if (aapt2.isFile()) return aapt2;
        return null;
    }

    @Test
    public void buildSdParserParsesJava17AndR8Directives() throws Exception {
        File proj = tmp.newFolder("cfg_proj");
        String sd = ""
                + "compileSdk = 36\n"
                + "minSdk = 26\n"
                + "targetSdk = 35\n"
                + "javaVersion = 17\n"
                + "namespace = \"com.sandrix.java17\"\n"
                + "applicationId = \"com.sandrix.java17.app\"\n"
                + "buildTypes {\n"
                + "    debug {\n"
                + "        minifyEnabled = false\n"
                + "    }\n"
                + "    release {\n"
                + "        minifyEnabled = true\n"
                + "        shrinkResources = true\n"
                + "        r8FullMode = true\n"
                + "        proguardFiles(\"proguard-rules.pro\", \"consumer.pro\")\n"
                + "    }\n"
                + "}\n";
        Files.write(new File(proj, "build.sd").toPath(), sd.getBytes(StandardCharsets.UTF_8));

        BuildSdParser.BuildSdConfig cfg = BuildSdParser.parseProjectConfig(proj);
        assertEquals("17", cfg.javaVersion);
        assertFalse(cfg.debugMinifyEnabled);
        assertTrue(cfg.releaseMinifyEnabled);
        assertTrue(cfg.releaseShrinkResources);
        assertTrue(cfg.optimizeEnabled);
        assertTrue(cfg.isMinifyEnabledForVariant(true));
        assertFalse(cfg.isMinifyEnabledForVariant(false));
        assertTrue(cfg.proguardFiles.contains("proguard-rules.pro"));
        assertTrue(cfg.proguardFiles.contains("consumer.pro"));
        assertEquals("17", BuildSdParser.normalizeJavaVersionToken("VERSION_17"));
        assertEquals("11", BuildSdParser.normalizeJavaVersionToken("11"));
    }

    @Test
    public void ecj33CompilesJava17ConstructsAndD8DexesCleanly() throws Exception {
        File srcDir = tmp.newFolder("j17_src");
        File outDir = tmp.newFolder("j17_classes");
        File dexDir = tmp.newFolder("j17_dex");

        File pkgDir = new File(srcDir, "com/sandrix/j17");
        assertTrue(pkgDir.mkdirs());

        String code = ""
                + "package com.sandrix.j17;\n"
                + "public class Java17Sample {\n"
                + "    public sealed interface Shape permits Circle, Rect {\n"
                + "        double area();\n"
                + "    }\n"
                + "    public static final record Circle(double radius) implements Shape {\n"
                + "        @Override public double area() { return Math.PI * radius * radius; }\n"
                + "    }\n"
                + "    public static final record Rect(double w, double h) implements Shape {\n"
                + "        @Override public double area() { return w * h; }\n"
                + "    }\n"
                + "    public static String describe(int code) {\n"
                + "        var json = \"\"\"\n"
                + "                {\"status\": \"ok\", \"java\": 17}\n"
                + "                \"\"\";\n"
                + "        String label = switch (code) {\n"
                + "            case 1 -> \"ONE\";\n"
                + "            case 2, 3 -> {\n"
                + "                yield \"SMALL:\" + code;\n"
                + "            }\n"
                + "            default -> \"OTHER\";\n"
                + "        };\n"
                + "        return label + \":\" + new Circle(2.0).area() + \":\" + json.trim();\n"
                + "    }\n"
                + "}\n";
        Files.write(new File(pkgDir, "Java17Sample.java").toPath(), code.getBytes(StandardCharsets.UTF_8));

        File androidJar = resolveAndroidJar();
        List<String> args = new ArrayList<>();
        args.add("-17");
        args.add("-source");
        args.add("17");
        args.add("-target");
        args.add("17");
        args.add("-g");
        args.add("-d");
        args.add(outDir.getAbsolutePath());
        args.add(new File(pkgDir, "Java17Sample.java").getAbsolutePath());

        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();
        boolean ok = BuildEngineTask.compileWithAndroidEcj(
                args,
                androidJar != null ? androidJar.getAbsolutePath() : null,
                new PrintWriter(outSw),
                new PrintWriter(errSw));
        assertTrue("ECJ 17 compilation should succeed. Err: " + errSw, ok);

        List<File> classes = collectFiles(outDir, ".class");
        assertTrue("Expected compiled Java 17 record/sealed classes >= 4", classes.size() >= 4);

        D8Command.Builder d8 = D8Command.builder()
                .setMinApiLevel(26)
                .setMode(CompilationMode.DEBUG)
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed);
        if (androidJar != null) {
            d8.addLibraryFiles(androidJar.toPath());
        }
        for (File cf : classes) {
            d8.addProgramFiles(cf.toPath());
        }
        D8.run(d8.build());

        File dexFile = new File(dexDir, "classes.dex");
        assertTrue("D8 must output classes.dex from Java 17 classfiles", dexFile.isFile() && dexFile.length() > 0);
    }

    @Test
    public void ecjCompileSucceedsUnderSimulatedAndroidArtWithoutJavaHome() throws Exception {
        File androidJar = resolveAndroidJar();
        assertNotNull("android.jar required", androidJar);
        File srcDir = tmp.newFolder("art_src");
        File outDir = tmp.newFolder("art_out");
        File javaFile = new File(srcDir, "App.java");
        Files.write(javaFile.toPath(),
                ("package com.arttest;\n"
                        + "public class App {\n"
                        + "    public record Item(String name, int qty) {}\n"
                        + "    public static String test() {\n"
                        + "        var item = new Item(\"Pen\", 2);\n"
                        + "        return switch (item.qty()) { case 2 -> item.name(); default -> \"x\"; };\n"
                        + "    }\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        String prevHome = System.getProperty("java.home");
        String prevVer = System.getProperty("java.version");
        try {
            System.setProperty("java.home", "/system");
            System.setProperty("java.version", "0");
            StringWriter outSw = new StringWriter();
            StringWriter errSw = new StringWriter();
            boolean ok = BuildEngineTask.compileWithAndroidEcj(
                    Arrays.asList(
                            "-nowarn", "-proc:none", "-17", "-source", "17", "-target", "17",
                            "-d", outDir.getAbsolutePath(),
                            javaFile.getAbsolutePath()),
                    androidJar.getAbsolutePath(),
                    new PrintWriter(outSw),
                    new PrintWriter(errSw));
            assertTrue("ECJ must succeed on Android ART (java.home=/system, java.version=0). Err: " + errSw, ok);
        } finally {
            if (prevHome != null) System.setProperty("java.home", prevHome);
            if (prevVer != null) System.setProperty("java.version", prevVer);
        }
    }

    @Test
    public void r8OptimizerShrinksDeadCodeAndEmitsMappingTxt() throws Exception {
        File projDir = tmp.newFolder("r8_proj");
        File srcDir = new File(projDir, "src/com/sandrix/r8test");
        assertTrue(srcDir.mkdirs());
        File classesDir = tmp.newFolder("r8_classes");
        File dexOutDir = tmp.newFolder("r8_dex");
        File mappingFile = new File(tmp.getRoot(), "mapping.txt");
        File aaptRules = new File(tmp.getRoot(), "aapt_rules.pro");

        String activityCode = ""
                + "package com.sandrix.r8test;\n"
                + "import android.app.Activity;\n"
                + "import android.os.Bundle;\n"
                + "public class MainActivity extends Activity {\n"
                + "    @Override protected void onCreate(Bundle b) {\n"
                + "        super.onCreate(b);\n"
                + "        KeptHelper h = new KeptHelper();\n"
                + "        System.out.println(h.computeSecretValue());\n"
                + "    }\n"
                + "}\n";
        String keptHelper = ""
                + "package com.sandrix.r8test;\n"
                + "public class KeptHelper {\n"
                + "    public String computeSecretValue() { return \"ALIVE_TOKEN_42\"; }\n"
                + "    public void unusedHelperMethod() { System.out.println(\"NEVER_CALLED_METHOD_99\"); }\n"
                + "}\n";
        String deadHelper = ""
                + "package com.sandrix.r8test;\n"
                + "public class DeadUnusedClass {\n"
                + "    public static String unusedBanner() { return \"DEAD_CLASS_SHOULD_SHRINK\"; }\n"
                + "}\n";

        File f1 = new File(srcDir, "MainActivity.java");
        File f2 = new File(srcDir, "KeptHelper.java");
        File f3 = new File(srcDir, "DeadUnusedClass.java");
        Files.write(f1.toPath(), activityCode.getBytes(StandardCharsets.UTF_8));
        Files.write(f2.toPath(), keptHelper.getBytes(StandardCharsets.UTF_8));
        Files.write(f3.toPath(), deadHelper.getBytes(StandardCharsets.UTF_8));

        Files.write(aaptRules.toPath(),
                "-keep class com.sandrix.r8test.MainActivity { <init>(); }\n".getBytes(StandardCharsets.UTF_8));

        File androidJar = resolveAndroidJar();
        assertNotNull("android.jar required for R8 test", androidJar);

        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();
        boolean compiled = BuildEngineTask.compileWithAndroidEcj(
                Arrays.asList(
                        "-17", "-source", "17", "-target", "17", "-g",
                        "-d", classesDir.getAbsolutePath(),
                        f1.getAbsolutePath(), f2.getAbsolutePath(), f3.getAbsolutePath()
                ),
                androidJar.getAbsolutePath(),
                new PrintWriter(outSw),
                new PrintWriter(errSw));
        assertTrue("ECJ compile must succeed: " + errSw, compiled);

        List<File> classFiles = collectFiles(classesDir, ".class");
        assertEquals(3, classFiles.size());

        List<BuildEngineTask.BuildDiagnostic> diags = new ArrayList<>();
        R8Optimizer.OptimizationResult res = R8Optimizer.optimize(
                projDir,
                null,
                26,
                true,
                true,
                true,
                true,
                androidJar,
                dexOutDir,
                mappingFile,
                aaptRules,
                classFiles,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                diags,
                null);

        assertTrue("R8 optimization must succeed: " + res.errorSummary, res.success);
        File dex = new File(dexOutDir, "classes.dex");
        assertTrue("R8 must emit classes.dex", dex.isFile() && dex.length() > 0);
        assertTrue("R8 must emit non-empty mapping.txt", mappingFile.isFile() && mappingFile.length() > 0);

        String dexStrings = new String(Files.readAllBytes(dex.toPath()), StandardCharsets.ISO_8859_1);
        assertTrue("MainActivity must be kept in DEX", dexStrings.contains("MainActivity"));
        assertFalse("DeadUnusedClass must be removed by R8 shrinking", dexStrings.contains("DeadUnusedClass"));
        assertFalse("DEAD_CLASS_SHOULD_SHRINK string must be removed", dexStrings.contains("DEAD_CLASS_SHOULD_SHRINK"));
        assertFalse("Unused method must be removed by R8 shrinking", dexStrings.contains("NEVER_CALLED_METHOD_99"));

        String mapText = new String(Files.readAllBytes(mappingFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("mapping.txt should map KeptHelper or MainActivity",
                mapText.contains("KeptHelper") || mapText.contains("MainActivity"));
    }

    @Test
    public void aabBuilderProducesValidSignedBundleVerifiedByBundletoolAndJarFile() throws Exception {
        File aapt2 = resolveAapt2();
        File androidJar = resolveAndroidJar();
        assertNotNull("aapt2 required for AAB proto test", aapt2);
        assertNotNull("android.jar required for AAB proto test", androidJar);

        File work = tmp.newFolder("aab_work");
        File resDir = new File(work, "res/values");
        assertTrue(resDir.mkdirs());
        Files.write(new File(resDir, "strings.xml").toPath(),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources><string name=\"app_name\">BundleDemo</string></resources>\n"
                        .getBytes(StandardCharsets.UTF_8));

        File manifest = new File(work, "AndroidManifest.xml");
        Files.write(manifest.toPath(),
                ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    package=\"com.sandrix.bundledemo\"\n"
                        + "    android:versionCode=\"7\"\n"
                        + "    android:versionName=\"1.7.0\">\n"
                        + "    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"35\" />\n"
                        + "    <application android:label=\"@string/app_name\" />\n"
                        + "</manifest>\n").getBytes(StandardCharsets.UTF_8));

        File compiledZip = new File(work, "compiled_res.zip");
        runCheckedProcess(aapt2.getAbsolutePath(), "compile", "--dir", new File(work, "res").getAbsolutePath(),
                "-o", compiledZip.getAbsolutePath());

        File protoApk = new File(work, "resources-proto.ap_");
        runCheckedProcess(aapt2.getAbsolutePath(), "link",
                "--proto-format",
                "-I", androidJar.getAbsolutePath(),
                "--manifest", manifest.getAbsolutePath(),
                "-o", protoApk.getAbsolutePath(),
                compiledZip.getAbsolutePath());
        assertTrue("aapt2 --proto-format must produce proto APK", protoApk.isFile());

        // Minimal DEX
        File srcDir = new File(work, "src/com/sandrix/bundledemo");
        assertTrue(srcDir.mkdirs());
        File stubJava = new File(srcDir, "Demo.java");
        Files.write(stubJava.toPath(),
                "package com.sandrix.bundledemo; public class Demo { public static int one() { return 1; } }\n"
                        .getBytes(StandardCharsets.UTF_8));
        File classesDir = new File(work, "classes");
        assertTrue(classesDir.mkdirs());
        assertTrue(BatchCompiler.compile(
                new String[]{"-17", "-source", "17", "-target", "17", "-d", classesDir.getAbsolutePath(), stubJava.getAbsolutePath()},
                new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()),
                null));
        File dexDir = new File(work, "dex");
        assertTrue(dexDir.mkdirs());
        D8.run(D8Command.builder()
                .setMinApiLevel(24)
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                .addProgramFiles(new File(classesDir, "com/sandrix/bundledemo/Demo.class").toPath())
                .build());

        File mapping = new File(work, "mapping.txt");
        Files.write(mapping.toPath(), "com.sandrix.bundledemo.Demo -> a.a:\n".getBytes(StandardCharsets.UTF_8));

        File unsignedAab = new File(work, "app-unsigned.aab");
        AabBuilder.buildUnsignedAab(protoApk, dexDir, Collections.emptyList(), Collections.emptyList(), mapping, unsignedAab);
        assertTrue(unsignedAab.isFile());

        // Generate real RSA keystore & sign JAR v1
        File ksFile = new File(work, "release.p12");
        char[] pass = "sandrix123".toCharArray();
        X509Certificate cert = KeystoreGenerator.generatePkcs12Keystore(
                ksFile, "sandrix123", "sandrix", "sandrix123",
                "Sandrix Demo", "Sandrix", "US", 25);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, pass);
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey("sandrix", pass);

        File signedAab = new File(work, "app-release.aab");
        AabBuilder.signAabJarV1(unsignedAab, signedAab, "sandrix", privateKey, cert);
        assertTrue(signedAab.isFile() && signedAab.length() > 0);

        // 1. Validate BundleConfig.pb via Google bundletool protobuf schema
        try (ZipFile zf = new ZipFile(signedAab)) {
            ZipEntry cfgEntry = zf.getEntry("BundleConfig.pb");
            assertNotNull("BundleConfig.pb must exist", cfgEntry);
            try (InputStream is = zf.getInputStream(cfgEntry)) {
                Config.BundleConfig parsedCfg = Config.BundleConfig.parseFrom(is);
                assertEquals(AabBuilder.BUNDLETOOL_VERSION, parsedCfg.getBundletool().getVersion());
                assertEquals(3, parsedCfg.getOptimizations().getSplitsConfig().getSplitDimensionCount());
            }
            assertNotNull("base/manifest/AndroidManifest.xml missing", zf.getEntry("base/manifest/AndroidManifest.xml"));
            assertNotNull("base/resources.pb missing", zf.getEntry("base/resources.pb"));
            assertNotNull("base/dex/classes.dex missing", zf.getEntry("base/dex/classes.dex"));
            assertNotNull("proguard.map metadata missing", zf.getEntry(AabBuilder.METADATA_PROGUARD_MAP));
        }

        // 2. Validate bundle with official bundletool ValidateBundleCommand
        ValidateBundleCommand.builder()
                .setBundlePath(signedAab.toPath())
                .build()
                .execute();

        // 3. Verify strict JAR v1 signature on every archive entry via java.util.jar.JarFile(..., true)
        int verifiedEntries = 0;
        try (JarFile jar = new JarFile(signedAab, true)) {
            byte[] buf = new byte[4096];
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                if (je.isDirectory() || je.getName().startsWith("META-INF/")) continue;
                try (InputStream is = jar.getInputStream(je)) {
                    while (is.read(buf) != -1) {
                        // consume stream so JarFile verifies digest + PKCS#7 signature
                    }
                }
                assertNotNull("Missing verified CodeSigners for " + je.getName(), je.getCodeSigners());
                assertTrue(je.getCodeSigners().length > 0);
                verifiedEntries++;
            }
        }
        assertTrue("Must verify >= 4 signed entries in AAB", verifiedEntries >= 4);

        // 4. Verify ApkAnalyzer also validates AAB signatures
        ApkAnalyzer.ApkReport report = ApkAnalyzer.analyze(signedAab);
        assertTrue("ApkAnalyzer must verify signed .aab", report.signing.verified);
        assertTrue("ApkAnalyzer v1Verified must be true", report.signing.v1Verified);
        assertEquals(1, report.dexFiles.size());
    }

    @Test
    public void localStepVmSupportsConditionalBreakpointsSteppingWatchesAndEditing() throws Exception {
        File proj = tmp.newFolder("dbg_proj");
        File srcDir = new File(proj, "src/com/sandrix/dbg");
        assertTrue(srcDir.mkdirs());

        String code = ""
                + "package com.sandrix.dbg;\n"
                + "public class MainActivity {\n"
                + "    private int total = 0;\n"
                + "    public void onCreate() {\n"
                + "        int limit = 4;\n"
                + "        for (int i = 0; i < limit; i++) {\n"
                + "            int step = addBonus(i);\n"
                + "            total = total + step;\n"
                + "        }\n"
                + "        System.out.println(\"DONE:\" + total);\n"
                + "    }\n"
                + "    private int addBonus(int val) {\n"
                + "        int doubled = val * 2;\n"
                + "        return doubled + 1;\n"
                + "    }\n"
                + "}\n";
        File mainFile = new File(srcDir, "MainActivity.java");
        Files.write(mainFile.toPath(), code.getBytes(StandardCharsets.UTF_8));

        // Line 7 is `int step = addBonus(i);` inside loop
        int loopBodyLine = 7;

        LocalJavaDebugEngine engine = new LocalJavaDebugEngine();
        CountDownLatch pausedLatch = new CountDownLatch(1);
        CountDownLatch stepIntoLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Integer> lastPausedLine = new AtomicReference<>(-1);
        AtomicReference<List<DebugModels.StackFrameInfo>> lastFrames = new AtomicReference<>();
        AtomicReference<String> termReason = new AtomicReference<>("");
        final List<String> consoleOut = Collections.synchronizedList(new ArrayList<>());

        DebugModels.BreakpointSpec condBp = new DebugModels.BreakpointSpec(
                mainFile.getAbsolutePath(), "MainActivity.java", loopBodyLine, "i == 2", true, 0);

        engine.start(
                proj,
                mainFile,
                (path, name, line) -> line == loopBodyLine ? condBp : null,
                new LocalJavaDebugEngine.SessionListener() {
                    private int pauseCount = 0;

                    @Override
                    public void onPaused(String reason, String filePath, String fileName, int lineNumber,
                                         List<DebugModels.StackFrameInfo> frames, int preferredFrameIndex) {
                        pauseCount++;
                        lastPausedLine.set(lineNumber);
                        lastFrames.set(frames);
                        if (pauseCount == 1) {
                            pausedLatch.countDown();
                        } else if (pauseCount == 2) {
                            stepIntoLatch.countDown();
                        }
                    }

                    @Override public void onResumed() {}
                    @Override public void onConsoleOutput(String line) { consoleOut.add(line); }
                    @Override public void onBreakpointHitUpdated(String filePath, int lineNumber) {}
                    @Override public void onTerminated(String reason) {
                        termReason.set(reason);
                        doneLatch.countDown();
                    }
                });

        assertTrue("Must pause on conditional breakpoint i == 2", pausedLatch.await(5, TimeUnit.SECONDS));
        assertEquals(loopBodyLine, (int) lastPausedLine.get());

        // Check variables & watch evaluation
        List<DebugModels.DebugVariable> vars = engine.inspectVariables(0);
        boolean foundI2 = false;
        for (DebugModels.DebugVariable v : vars) {
            if ("i".equals(v.name) && "2".equals(v.value)) {
                foundI2 = true;
            }
        }
        assertTrue("Expected loop variable i == 2 at breakpoint pause", foundI2);

        DebugModels.WatchItem eval = engine.evaluateWatch(0, "i + limit");
        assertFalse(eval.error);
        assertEquals("6", eval.value);

        // Live edit variable `total` to 100
        assertTrue(engine.setVariableValue(0, "total", "100"));
        DebugModels.WatchItem totalEval = engine.evaluateWatch(0, "total");
        assertEquals("100", totalEval.value);

        // Step Into addBonus(i)
        engine.sendCommand(LocalJavaDebugEngine.StepCommand.STEP_INTO);
        assertTrue("Must pause inside addBonus after Step Into", stepIntoLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Call stack depth must be >= 2 inside addBonus", lastFrames.get().size() >= 2);
        assertEquals("addBonus", lastFrames.get().get(0).methodName);

        // Resume to completion
        engine.sendCommand(LocalJavaDebugEngine.StepCommand.RESUME);
        assertTrue("Step VM should terminate cleanly", doneLatch.await(5, TimeUnit.SECONDS));
        assertFalse("Console should contain output", consoleOut.isEmpty());
    }

    @Test
    public void jdwpClientConnectsHandshakesAndReadsRemoteFramesOverTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket sock = server.accept()) {
                    DataInputStream in = new DataInputStream(sock.getInputStream());
                    DataOutputStream out = new DataOutputStream(sock.getOutputStream());

                    byte[] hs = new byte[14];
                    in.readFully(hs);
                    assertEquals("JDWP-Handshake", new String(hs, StandardCharsets.US_ASCII));
                    out.write(hs);
                    out.flush();

                    // Read packets & respond until client closes
                    while (true) {
                        int len;
                        try {
                            len = in.readInt();
                        } catch (Exception eof) {
                            break;
                        }
                        int id = in.readInt();
                        int flags = in.readUnsignedByte();
                        int cmdSet = in.readUnsignedByte();
                        int cmd = in.readUnsignedByte();
                        byte[] payload = new byte[Math.max(0, len - 11)];
                        in.readFully(payload);

                        ByteArrayOutputStream replyBody = new ByteArrayOutputStream();
                        DataOutputStream rOut = new DataOutputStream(replyBody);
                        if (cmdSet == 1 && cmd == 7) { // IDSizes
                            rOut.writeInt(8); // field
                            rOut.writeInt(8); // method
                            rOut.writeInt(8); // object
                            rOut.writeInt(8); // referenceType
                            rOut.writeInt(8); // frame
                        } else if (cmdSet == 1 && cmd == 1) { // Version
                            writeJdwpString(rOut, "Sandrix-Test-VM 17");
                            rOut.writeInt(17);
                            rOut.writeInt(0);
                            writeJdwpString(rOut, "17.0.1");
                            writeJdwpString(rOut, "SandrixTestVM");
                        } else if (cmdSet == 1 && cmd == 4) { // AllThreads
                            rOut.writeInt(1);
                            rOut.writeLong(101L);
                        } else if (cmdSet == 11 && cmd == 6) { // Frames
                            rOut.writeInt(1);
                            rOut.writeLong(9001L);
                            rOut.writeByte(1); // typeTag
                            rOut.writeLong(2001L); // classID
                            rOut.writeLong(3001L); // methodID
                            rOut.writeLong(4L); // codeIndex
                        } else if (cmdSet == 6 && cmd == 1) { // Method/LineTable
                            rOut.writeLong(0L);
                            rOut.writeLong(10L);
                            rOut.writeInt(1);
                            rOut.writeLong(0L);
                            rOut.writeInt(42);
                        } else if (cmdSet == 6 && cmd == 2) { // Method/VariableTable
                            rOut.writeInt(1);
                            rOut.writeInt(1);
                            rOut.writeLong(0L);
                            writeJdwpString(rOut, "counter");
                            writeJdwpString(rOut, "I");
                            rOut.writeInt(10);
                            rOut.writeInt(0);
                        } else if (cmdSet == 16 && cmd == 1) { // StackFrame/GetValues
                            rOut.writeInt(1);
                            rOut.writeByte('I');
                            rOut.writeInt(77);
                        }
                        byte[] body = replyBody.toByteArray();
                        out.writeInt(11 + body.length);
                        out.writeInt(id);
                        out.writeByte(0x80); // reply flag
                        out.writeShort(0); // error code NONE
                        out.write(body);
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            try (JdwpClient client = new JdwpClient()) {
                client.connect("127.0.0.1", port, 3000);
                assertTrue(client.isConnected());
                assertEquals("SandrixTestVM (17.0.1, JDWP 17.0) - Sandrix-Test-VM 17", client.fetchVmVersion());

                List<Long> threads = client.fetchAllThreads();
                assertEquals(Collections.singletonList(101L), threads);

                List<JdwpClient.RemoteFrame> frames = client.fetchThreadFrames(101L, 0, 5);
                assertEquals(1, frames.size());
                assertEquals(9001L, frames.get(0).frameId);

                List<JdwpClient.LineEntry> lines = client.fetchLineTable(2001L, 3001L);
                assertEquals(42, lines.get(0).lineNumber);

                List<JdwpClient.VariableSlot> slots = client.fetchVariableTable(2001L, 3001L);
                assertEquals(1, slots.size());
                assertEquals("counter", slots.get(0).name);

                List<String> values = client.fetchFrameValues(101L, 9001L, slots);
                assertEquals(Collections.singletonList("77"), values);
            }
        }
    }

    private static void writeJdwpString(DataOutputStream out, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static void runCheckedProcess(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (InputStream is = p.getInputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        int exit = p.waitFor();
        assertEquals("Process failed: " + Arrays.toString(cmd) + "\n" + out, 0, exit);
    }

    private static List<File> collectFiles(File dir, String ext) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return out;
        for (File f : children) {
            if (f.isDirectory()) {
                out.addAll(collectFiles(f, ext));
            } else if (f.getName().endsWith(ext)) {
                out.add(f);
            }
        }
        return out;
    }
}
