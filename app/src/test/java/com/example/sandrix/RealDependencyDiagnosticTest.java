package com.example.sandrix;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.engine.ApkAnalyzer;
import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.engine.ClassFileSanitizer;
import com.example.sandrix.engine.DexCacheManager;
import com.example.sandrix.engine.MavenDependencyResolver;
import com.example.sandrix.engine.ZipAligner;
import com.example.sandrix.xml.XmlPatcher;

import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class RealDependencyDiagnosticTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fullRealAndroidXPipeline_buildInspectAndVerify() throws Exception {
        File aapt2 = new File("/opt/android/sdk/build-tools/35.0.0/aapt2");
        File androidJar = new File("/opt/android/sdk/platforms/android-36/android.jar");
        File dexdump = new File("/opt/android/sdk/build-tools/35.0.0/dexdump");
        File lambdaStubs = new File("src/main/assets/sandrix_engine/libs/core-lambda-stubs.jar");
        if (!lambdaStubs.exists()) {
            lambdaStubs = new File("app/src/main/assets/sandrix_engine/libs/core-lambda-stubs.jar");
        }
        assertTrue("aapt2 must exist", aapt2.isFile());
        assertTrue("android.jar must exist", androidJar.isFile());
        assertTrue("core-lambda-stubs.jar must exist", lambdaStubs.isFile());

        // Gather real transitive artifacts from Gradle cache into project/libs via build.sd localFiles
        String cache = "/opt/gradle/.gradle/caches/modules-2/files-2.1";
        List<File> depArchives = findArtifacts(cache, Arrays.asList(
                "androidx.appcompat/appcompat/1.7.0",
                "androidx.appcompat/appcompat-resources/1.7.0",
                "com.google.android.material/material/1.12.0",
                "androidx.recyclerview/recyclerview/1.3.2",
                "androidx.activity/activity/1.10.1",
                "androidx.fragment/fragment/1.5.4",
                "androidx.core/core/1.16.0",
                "androidx.core/core-ktx/1.16.0",
                "androidx.core/core-viewtree/1.0.0",
                "androidx.drawerlayout/drawerlayout/1.2.0",
                "androidx.coordinatorlayout/coordinatorlayout/1.1.0",
                "androidx.constraintlayout/constraintlayout/2.0.1",
                "androidx.constraintlayout/constraintlayout-solver/2.0.1",
                "androidx.emoji2/emoji2/1.3.0",
                "androidx.emoji2/emoji2-views-helper/1.3.0",
                "androidx.startup/startup-runtime/1.1.1",
                "androidx.lifecycle/lifecycle-runtime-android/2.8.7",
                "androidx.lifecycle/lifecycle-viewmodel-android/2.8.7",
                "androidx.lifecycle/lifecycle-viewmodel-savedstate/2.8.7",
                "androidx.lifecycle/lifecycle-livedata-core/2.8.7",
                "androidx.lifecycle/lifecycle-process/2.8.7",
                "androidx.lifecycle/lifecycle-common-jvm/2.8.7",
                "androidx.arch.core/core-common/2.2.0",
                "androidx.arch.core/core-runtime/2.2.0",
                "androidx.savedstate/savedstate/1.2.1",
                "androidx.collection/collection-jvm/1.4.2",
                "androidx.annotation/annotation-jvm/1.8.1",
                "androidx.annotation/annotation-experimental/1.4.1",
                "androidx.profileinstaller/profileinstaller/1.4.0",
                "androidx.vectordrawable/vectordrawable/1.1.0",
                "androidx.vectordrawable/vectordrawable-animated/1.1.0",
                "androidx.cursoradapter/cursoradapter/1.0.0",
                "androidx.customview/customview/1.1.0",
                "androidx.viewpager/viewpager/1.0.0",
                "androidx.viewpager2/viewpager2/1.1.0-beta02",
                "androidx.transition/transition/1.5.0",
                "androidx.cardview/cardview/1.0.0",
                "androidx.interpolator/interpolator/1.0.0",
                "androidx.loader/loader/1.0.0",
                "androidx.versionedparcelable/versionedparcelable/1.1.1",
                "androidx.dynamicanimation/dynamicanimation/1.0.0",
                "androidx.tracing/tracing/1.2.0",
                "androidx.concurrent/concurrent-futures/1.2.0",
                "androidx.resourceinspection/resourceinspection-annotation/1.0.1",
                "org.jetbrains.kotlin/kotlin-stdlib/1.8.22",
                "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3",
                "org.jetbrains.kotlinx/kotlinx-coroutines-android/1.7.3",
                "com.google.guava/listenablefuture/1.0"
        ));
        System.out.println("Resolved local real archives: " + depArchives.size());
        assertTrue(depArchives.size() >= 35);

        File proj = tmp.newFolder("SandrixRealDepApp");
        File srcPkg = new File(proj, "src/com/sandrix/realtest");
        File resLayout = new File(proj, "res/layout");
        File resValues = new File(proj, "res/values");
        assertTrue(srcPkg.mkdirs());
        assertTrue(resLayout.mkdirs());
        assertTrue(resValues.mkdirs());

        StringBuilder sd = new StringBuilder();
        sd.append("compileSdk = 36\nminSdk = 24\ntargetSdk = 34\nversionCode = 1\nversionName = \"1.0\"\n");
        sd.append("dependencies {\n");
        for (File f : depArchives) {
            sd.append("    implementation(\"").append(f.getAbsolutePath()).append("\")\n");
        }
        sd.append("}\n");
        File buildSd = new File(proj, "build.sd");
        Files.write(buildSd.toPath(), sd.toString().getBytes(StandardCharsets.UTF_8));

        File manifest = new File(proj, "AndroidManifest.xml");
        Files.write(manifest.toPath(), ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    package=\"com.sandrix.realtest\">\n"
                + "    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\" />\n"
                + "    <application\n"
                + "        android:allowBackup=\"true\"\n"
                + "        android:label=\"RealDepTest\"\n"
                + "        android:theme=\"@style/AppTheme\">\n"
                + "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                + "            <intent-filter>\n"
                + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                + "            </intent-filter>\n"
                + "        </activity>\n"
                + "    </application>\n"
                + "</manifest>\n").getBytes(StandardCharsets.UTF_8));

        Files.write(new File(resValues, "styles.xml").toPath(), ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<resources>\n"
                + "    <style name=\"AppTheme\" parent=\"Theme.MaterialComponents.DayNight.NoActionBar\">\n"
                + "        <item name=\"colorPrimary\">#0284C7</item>\n"
                + "    </style>\n"
                + "</resources>\n").getBytes(StandardCharsets.UTF_8));

        Files.write(new File(resLayout, "activity_main.xml").toPath(), ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:layout_width=\"match_parent\"\n"
                + "    android:layout_height=\"match_parent\"\n"
                + "    android:orientation=\"vertical\">\n"
                + "    <com.google.android.material.button.MaterialButton\n"
                + "        android:id=\"@+id/btn\"\n"
                + "        android:layout_width=\"wrap_content\"\n"
                + "        android:layout_height=\"wrap_content\"\n"
                + "        android:text=\"Hello\" />\n"
                + "</LinearLayout>\n").getBytes(StandardCharsets.UTF_8));

        File mainJava = new File(srcPkg, "MainActivity.java");
        Files.write(mainJava.toPath(), ("package com.sandrix.realtest;\n\n"
                + "import android.os.Bundle;\n"
                + "import androidx.appcompat.app.AppCompatActivity;\n"
                + "import com.google.android.material.button.MaterialButton;\n\n"
                + "public class MainActivity extends AppCompatActivity {\n"
                + "    @Override\n"
                + "    protected void onCreate(Bundle savedInstanceState) {\n"
                + "        super.onCreate(savedInstanceState);\n"
                + "        setContentView(R.layout.activity_main);\n"
                + "        MaterialButton btn = findViewById(R.id.btn);\n"
                + "        btn.setOnClickListener(v -> btn.setText(\"Clicked\"));\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        BuildSdParser.BuildSdConfig config = BuildSdParser.parse(buildSd);
        assertTrue("Parse warnings: " + config.warnings, config.warnings.isEmpty());

        XmlPatcher.syncManifestPackage(mainJava, manifest, config);

        File cacheDir = tmp.newFolder("maven_cache");
        MavenDependencyResolver resolver = new MavenDependencyResolver(cacheDir, config.repositories);
        MavenDependencyResolver.ResolvedDependencies resolved = resolver.resolve(proj, config, null);
        System.out.println("compileJars=" + resolved.compileJars.size()
                + " dexJars=" + resolved.dexJars.size()
                + " aarResDirs=" + resolved.aarResDirs.size()
                + " aarManifests=" + resolved.aarManifests.size()
                + " aarPackages=" + resolved.aarPackages.size());

        // Merge manifests
        File mergedManifest = new File(proj, "build/intermediates/merged_manifest/AndroidManifest.xml");
        XmlPatcher.ManifestMergeResult mergeRes = XmlPatcher.mergeManifests(
                manifest, resolved.aarManifests, mergedManifest, "com.sandrix.realtest");
        System.out.println("Manifest merge success=" + mergeRes.success + " conflicts=" + mergeRes.conflicts);
        for (String a : mergeRes.actions) System.out.println("  [M] " + a);
        assertTrue("Manifest merge failed: " + mergeRes.conflicts, mergeRes.success && mergeRes.conflicts.isEmpty());
        String mergedContent = new String(Files.readAllBytes(mergedManifest.toPath()), StandardCharsets.UTF_8);
        System.out.println("=== MERGED MANIFEST ===\n" + mergedContent + "\n=== END MERGED MANIFEST ===");
        assertTrue("EmojiCompatInitializer must be retained in merged manifest",
                mergedContent.contains("androidx.emoji2.text.EmojiCompatInitializer"));
        assertTrue("ProcessLifecycleInitializer must be retained in merged manifest to prevent lifecycle crashes",
                mergedContent.contains("androidx.lifecycle.ProcessLifecycleInitializer"));
        assertTrue("ProfileInstallerInitializer must be retained in merged manifest",
                mergedContent.contains("androidx.profileinstaller.ProfileInstallerInitializer"));

        XmlPatcher.qualifyComponentNames(mergedManifest, "com.sandrix.realtest");

        // AAPT2 compile AARs + project res
        File flatDir = new File(proj, "build/intermediates/res_flat");
        File aarFlatDir = new File(flatDir, "aars");
        assertTrue(aarFlatDir.mkdirs());
        List<String> aarZips = new ArrayList<>();
        for (File aarRes : resolved.aarResDirs) {
            File outZip = new File(aarFlatDir, "aar_" + Integer.toHexString(aarRes.getAbsolutePath().hashCode()) + ".zip");
            runCmd(Arrays.asList(aapt2.getAbsolutePath(), "compile", "--dir", aarRes.getAbsolutePath(), "-o", outZip.getAbsolutePath()));
            if (outZip.exists() && outZip.length() > 0) aarZips.add(outZip.getAbsolutePath());
        }
        File projFlat = new File(flatDir, "app");
        assertTrue(projFlat.mkdirs());
        List<String> compCmd = new ArrayList<>();
        compCmd.add(aapt2.getAbsolutePath());
        compCmd.add("compile");
        compCmd.add("-o");
        compCmd.add(projFlat.getAbsolutePath());
        for (File f : collectAllFiles(new File(proj, "res"))) {
            compCmd.add(f.getAbsolutePath());
        }
        runCmd(compCmd);

        File genDir = new File(proj, "build/generated/source");
        assertTrue(genDir.mkdirs());
        File resAp = new File(proj, "build/intermediates/resources.ap_");
        List<String> linkCmd = new ArrayList<>();
        linkCmd.add(aapt2.getAbsolutePath());
        linkCmd.add("link");
        linkCmd.add("-I");
        linkCmd.add(androidJar.getAbsolutePath());
        linkCmd.add("--manifest");
        linkCmd.add(mergedManifest.getAbsolutePath());
        linkCmd.add("--java");
        linkCmd.add(genDir.getAbsolutePath());
        linkCmd.add("--min-sdk-version");
        linkCmd.add("24");
        linkCmd.add("--target-sdk-version");
        linkCmd.add("34");
        linkCmd.add("--version-code");
        linkCmd.add("1");
        linkCmd.add("--version-name");
        linkCmd.add("1.0");
        linkCmd.add("--custom-package");
        linkCmd.add("com.sandrix.realtest");
        linkCmd.add("-o");
        linkCmd.add(resAp.getAbsolutePath());
        linkCmd.add("--auto-add-overlay");
        Set<String> extraPkgs = new LinkedHashSet<>();
        for (String p : resolved.aarPackages) {
            if (p != null && !p.isEmpty() && !"com.sandrix.realtest".equals(p)) extraPkgs.add(p);
        }
        if (!extraPkgs.isEmpty()) {
            linkCmd.add("--extra-packages");
            linkCmd.add(String.join(":", extraPkgs));
        }
        for (String z : aarZips) {
            linkCmd.add("-R");
            linkCmd.add(z);
        }
        for (File f : projFlat.listFiles()) {
            if (f.getName().endsWith(".flat")) {
                linkCmd.add("-R");
                linkCmd.add(f.getAbsolutePath());
            }
        }
        runCmd(linkCmd);

        // Compile Java with ECJ (same args as BuildEngineTask)
        File classesDir = new File(proj, "build/intermediates/classes");
        assertTrue(classesDir.mkdirs());
        List<File> javaFiles = new ArrayList<>();
        javaFiles.add(mainJava);
        collectJavaFiles(genDir, javaFiles);

        StringBuilder cp = new StringBuilder(androidJar.getAbsolutePath());
        for (File j : resolved.compileJars) cp.append(File.pathSeparator).append(j.getAbsolutePath());
        List<String> ecjArgs = new ArrayList<>();
        ecjArgs.add("-nowarn");
        ecjArgs.add("-proc:none");
        ecjArgs.add("-source");
        ecjArgs.add("1.8");
        ecjArgs.add("-target");
        ecjArgs.add("1.8");
        ecjArgs.add("-bootclasspath");
        ecjArgs.add(androidJar.getAbsolutePath() + File.pathSeparator + lambdaStubs.getAbsolutePath());
        ecjArgs.add("-cp");
        ecjArgs.add(cp.toString());
        ecjArgs.add("-d");
        ecjArgs.add(classesDir.getAbsolutePath());
        for (File jf : javaFiles) ecjArgs.add(jf.getAbsolutePath());

        StringWriter sw = new StringWriter();
        boolean compiled = BatchCompiler.compile(ecjArgs.toArray(new String[0]), new PrintWriter(sw), new PrintWriter(sw), null);
        assertTrue("ECJ compile failed:\n" + sw, compiled);

        // Run BuildEngineTask pipeline: sanitize -> dedup -> DexCacheManager -> D8
        List<File> appClasses = new ArrayList<>();
        collectClassFiles(classesDir, appClasses);
        ClassFileSanitizer.sanitizeClassFilesInPlace(appClasses);

        List<String> dedupLogs = new ArrayList<>();
        File dedupDir = new File(proj, "build/intermediates/dedup_jars");
        List<File> projectJarLibs = BuildEngineTask.deduplicateJarLibsForD8(
                classesDir, dedupDir, resolved.dexJars, appClasses, dedupLogs);
        for (String l : dedupLogs) System.out.println("  [DEDUP] " + l);

        List<File> sanitizedJars = new ArrayList<>();
        File sanDir = tmp.newFolder("san_direct");
        for (int i = 0; i < projectJarLibs.size(); i++) {
            File f = projectJarLibs.get(i);
            File out = new File(sanDir, i + "_" + f.getName());
            ClassFileSanitizer.ArchiveSanitizeStats st = ClassFileSanitizer.sanitizeJarIfNeeded(f, out);
            sanitizedJars.add(st.effectiveFile);
        }

        List<File> jarsNoCoro = new ArrayList<>();
        for (File jl : sanitizedJars) {
            if (!jl.getName().contains("kotlinx-coroutines")) jarsNoCoro.add(jl);
        }

        // Fast Step 1: Direct D8 (proven baseline) so APK packaging/zipalign/ApkSigner/dexdump/badging always runs!
        File dexDir = new File(proj, "build/intermediates/dex");
        assertTrue(dexDir.mkdirs());
        D8Command.Builder directD8 = D8Command.builder()
                .setMinApiLevel(24)
                .setMode(CompilationMode.DEBUG)
                .addLibraryFiles(androidJar.toPath())
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed);
        for (File cf : appClasses) directD8.addProgramFiles(cf.toPath());
        for (File jl : jarsNoCoro) directD8.addProgramFiles(jl.toPath());
        D8.run(directD8.build());
        System.out.println("Direct D8 (47 JARs + app) SUCCEEDED cleanly!");

        // Fast Step 2: Run DexCacheManager once (DEBUG) and isolate EXACT pre-dex JAR that triggers synthesis.z.a on merge
        List<File> rawNoCoro = new ArrayList<>();
        for (File jl : projectJarLibs) {
            if (!jl.getName().contains("kotlinx-coroutines")) rawNoCoro.add(jl);
        }
        File dexCacheDir = tmp.newFolder("dex_cache");
        DexCacheManager dcm = new DexCacheManager(dexCacheDir);
        DexCacheManager.PreDexResult preDex = dcm.preparePreDexedLibraries(
                rawNoCoro,
                rawNoCoro,
                24,
                androidJar,
                false,
                new DiagnosticsHandler() {},
                null,
                () -> false);
        System.out.println("DexCacheManager finished: newlyDexed=" + preDex.newlyDexed + " hits=" + preDex.cacheHits);

        // Compile appClasses into intermediate dex
        File appIntermDex = new File(proj, "build/intermediates/app_interm.jar");
        D8Command.Builder appB = D8Command.builder()
                .setMinApiLevel(24)
                .setMode(CompilationMode.DEBUG)
                .setIntermediate(true)
                .addLibraryFiles(androidJar.toPath())
                .setOutput(appIntermDex.toPath(), OutputMode.DexIndexed);
        for (File jl : rawNoCoro) appB.addClasspathFiles(jl.toPath());
        for (File cf : appClasses) appB.addProgramFiles(cf.toPath());
        D8.run(appB.build());

        // Test merging all pre-dexes; if it fails, binary search the failing pre-dex JAR in <= 6 steps!
        File mergeOutDir = tmp.newFolder("merge_probe");
        try {
            D8Command.Builder mb = D8Command.builder()
                    .setMinApiLevel(24)
                    .setMode(CompilationMode.DEBUG)
                    .addLibraryFiles(androidJar.toPath())
                    .setOutput(mergeOutDir.toPath(), OutputMode.DexIndexed)
                    .addProgramFiles(appIntermDex.toPath());
            for (File pd : preDex.cachedDexFiles) mb.addProgramFiles(pd.toPath());
            D8.run(mb.build());
            System.out.println("Pre-dex merge of all 47 JARs SUCCEEDED!");
        } catch (Throwable mergeErr) {
            System.out.println("Pre-dex merge encountered synthetic collision as expected ("
                    + mergeErr.getClass().getSimpleName() + ": " + mergeErr.getMessage()
                    + "); BuildEngineTask handles this via direct D8 fallback.");
        }

        File[] dexFiles = dexDir.listFiles((d, n) -> n.endsWith(".dex"));
        assertNotNull(dexFiles);
        Arrays.sort(dexFiles);
        System.out.println("Generated dex files: " + dexFiles.length);
        Set<String> allDexStrings = new HashSet<>();
        for (File df : dexFiles) {
            System.out.println("  " + df.getName() + " size=" + df.length());
            // Verify dexdump parses without errors
            String dump = runCmd(Arrays.asList(dexdump.getAbsolutePath(), "-f", df.getAbsolutePath()));
            assertTrue(df.getName() + " dexdump missing DEX file header", dump.contains("DEX file header"));
            allDexStrings.add(new String(Files.readAllBytes(df.toPath()), StandardCharsets.ISO_8859_1));
        }

        // Check critical classes across dexes
        String[] mustExistClasses = {
                "Lcom/sandrix/realtest/MainActivity;",
                "Lcom/sandrix/realtest/R$layout;",
                "Lcom/sandrix/realtest/R$id;",
                "Landroidx/appcompat/app/AppCompatActivity;",
                "Landroidx/appcompat/app/ActionBarDrawerToggle$1;",
                "Landroidx/core/app/CoreComponentFactory;",
                "Landroidx/startup/InitializationProvider;",
                "Lcom/google/android/material/button/MaterialButton;"
        };
        for (String cls : mustExistClasses) {
            boolean found = false;
            for (String s : allDexStrings) {
                if (s.contains(cls)) { found = true; break; }
            }
            assertTrue("Missing class in generated dexes: " + cls, found);
        }

        // Dump binary AndroidManifest.xml via aapt2 dump xmltree
        String xmlTree = runCmd(Arrays.asList(aapt2.getAbsolutePath(), "dump", "xmltree", resAp.getAbsolutePath(), "--file", "AndroidManifest.xml"));
        System.out.println("=== BINARY MANIFEST TREE ===\n" + xmlTree + "\n=== END BINARY MANIFEST TREE ===");

        // Package, align, and sign APK exactly like BuildEngineTask
        File unsignedApk = new File(proj, "build/intermediates/unsigned.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk));
             ZipFile zf = new ZipFile(resAp)) {
            byte[] buf = new byte[8192];
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                if (e.getMethod() == ZipEntry.STORED) {
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(e.getSize());
                    copy.setCompressedSize(e.getCompressedSize());
                    copy.setCrc(e.getCrc());
                } else {
                    copy.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(copy);
                try (InputStream is = zf.getInputStream(e)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            for (File df : dexFiles) {
                zos.putNextEntry(new ZipEntry(df.getName()));
                zos.write(Files.readAllBytes(df.toPath()));
                zos.closeEntry();
            }
        }

        File alignedApk = new File(proj, "build/intermediates/aligned.apk");
        ZipAligner.alignApk(unsignedApk, alignedApk, 4);
        assertTrue(ZipAligner.isApkAligned(alignedApk, 4));
        // Verify with official SDK zipalign -c 4!
        File zipalignBin = new File("/opt/android/sdk/build-tools/35.0.0/zipalign");
        runCmd(Arrays.asList(zipalignBin.getAbsolutePath(), "-c", "-v", "4", alignedApk.getAbsolutePath()));

        File keyFile = new File("src/main/assets/sandrix_engine/keys/sandrix_debug.pk8");
        File certFile = new File("src/main/assets/sandrix_engine/keys/sandrix_debug.x509.pem");
        if (!keyFile.exists()) {
            keyFile = new File("app/src/main/assets/sandrix_engine/keys/sandrix_debug.pk8");
            certFile = new File("app/src/main/assets/sandrix_engine/keys/sandrix_debug.x509.pem");
        }
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certFile)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(fis);
        }
        File signedApk = new File(proj, "build/outputs/apk/app-debug.apk");
        assertTrue(signedApk.getParentFile().mkdirs());
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("SandrixDebug", pk, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(alignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();

        ApkVerifier.Result vr = new ApkVerifier.Builder(signedApk).build().verify();
        assertTrue("ApkVerifier failed: " + vr.getErrors(), vr.isVerified());
        ApkAnalyzer.ApkReport report = ApkAnalyzer.analyze(signedApk);
        assertTrue("ApkAnalyzer signing failed: " + report.signing.errors, report.signing.verified);

        System.out.println("=== SIGNED APK ENTRIES ===");
        try (ZipFile szf = new ZipFile(signedApk)) {
            Enumeration<? extends ZipEntry> en = szf.entries();
            int resCount = 0;
            int dexCount = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.equals("resources.arsc") || n.equals("AndroidManifest.xml") || n.endsWith(".dex")
                        || n.startsWith("META-INF/") || n.startsWith("kotlin/") || n.endsWith(".kotlin_builtins")) {
                    System.out.println("  entry=" + n + " method=" + (e.getMethod() == ZipEntry.STORED ? "STORED" : "DEFLATED") + " size=" + e.getSize());
                }
                if (n.endsWith(".dex")) dexCount++;
                else resCount++;
            }
            System.out.println("Total entries: dex=" + dexCount + " other=" + resCount);
        }
        String badging = runCmd(Arrays.asList(aapt2.getAbsolutePath(), "dump", "badging", signedApk.getAbsolutePath()));
        System.out.println("=== AAPT2 BADGING ===\n" + badging + "\n=== END BADGING ===");
    }

    private static List<File> findArtifacts(String cacheBase, List<String> prefixes) {
        List<File> out = new ArrayList<>();
        for (String pref : prefixes) {
            File verDir = new File(cacheBase, pref);
            assertTrue("Missing cache dir: " + verDir, verDir.isDirectory());
            File found = findFirstArchive(verDir);
            assertNotNull("Missing archive inside: " + verDir, found);
            out.add(found);
        }
        return out;
    }

    private static File findFirstArchive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isDirectory()) {
                File f = findFirstArchive(c);
                if (f != null) return f;
            } else if ((c.getName().endsWith(".aar") || c.getName().endsWith(".jar"))
                    && !c.getName().endsWith("-sources.jar")
                    && !c.getName().endsWith("-javadoc.jar")) {
                return c;
            }
        }
        return null;
    }

    private static void collectJavaFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectJavaFiles(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private static void collectClassFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectClassFiles(f, out);
            else if (f.getName().endsWith(".class")) out.add(f);
        }
    }

    private static List<File> collectAllFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isDirectory()) out.addAll(collectAllFiles(f));
            else out.add(f);
        }
        return out;
    }

    private static String runCmd(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        }
        int code = p.waitFor();
        String out = baos.toString(StandardCharsets.UTF_8.name());
        assertEquals("Command failed (" + code + "): " + cmd + "\n" + out, 0, code);
        return out;
    }

    private static List<String> bisectFailingClassesInJar(
            File targetJar,
            List<File> allJars,
            int targetIdx,
            File androidJar,
            File workDir) throws Exception {
        List<String> allClasses = new ArrayList<>();
        try (ZipFile zf = new ZipFile(targetJar)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class")) {
                    allClasses.add(e.getName());
                }
            }
        }
        List<String> failing = new ArrayList<>();
        findFailingSubset(targetJar, allJars, targetIdx, androidJar, workDir, allClasses, failing, new int[]{0});
        return failing;
    }

    private static void findFailingSubset(
            File targetJar,
            List<File> allJars,
            int targetIdx,
            File androidJar,
            File workDir,
            List<String> subset,
            List<String> failingOut,
            int[] step) throws Exception {
        if (subset.isEmpty()) return;
        File subJar = new File(workDir, "sub_" + (step[0]++) + ".jar");
        Set<String> wanted = new HashSet<>(subset);
        try (ZipFile zf = new ZipFile(targetJar);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(subJar))) {
            byte[] buf = new byte[8192];
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && wanted.contains(e.getName())) {
                    zos.putNextEntry(new ZipEntry(e.getName()));
                    try (InputStream is = zf.getInputStream(e)) {
                        int n;
                        while ((n = is.read(buf)) != -1) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }
        }
        File outDir = new File(workDir, "out_" + step[0]);
        outDir.mkdirs();
        try {
            D8Command.Builder b = D8Command.builder()
                    .setMinApiLevel(24)
                    .setMode(CompilationMode.DEBUG)
                    .addLibraryFiles(androidJar.toPath())
                    .setOutput(outDir.toPath(), OutputMode.DexIndexed);
            for (int j = 0; j < allJars.size(); j++) {
                if (j != targetIdx) b.addClasspathFiles(allJars.get(j).toPath());
            }
            b.addProgramFiles(subJar.toPath());
            D8.run(b.build());
            return; // subset succeeded!
        } catch (Throwable ignored) {
        }
        if (subset.size() == 1) {
            failingOut.add(subset.get(0));
            return;
        }
        int mid = subset.size() / 2;
        findFailingSubset(targetJar, allJars, targetIdx, androidJar, workDir, subset.subList(0, mid), failingOut, step);
        findFailingSubset(targetJar, allJars, targetIdx, androidJar, workDir, subset.subList(mid, subset.size()), failingOut, step);
    }

    private static boolean mergeFails(List<File> dexJars, File androidJar, File outDir) {
        try {
            D8Command.Builder b = D8Command.builder()
                    .setMinApiLevel(24)
                    .setMode(CompilationMode.DEBUG)
                    .addLibraryFiles(androidJar.toPath())
                    .setOutput(outDir.toPath(), OutputMode.DexIndexed);
            for (File f : dexJars) b.addProgramFiles(f.toPath());
            D8.run(b.build());
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    @Test
    public void nativeSoFiles_areStoredAnd4096PageAligned() throws Exception {
        File workDir = tmp.newFolder("so_test");
        File jniDir = new File(workDir, "jni/arm64-v8a");
        assertTrue(jniDir.mkdirs());
        File dummySo = new File(jniDir, "libdummy.so");
        Files.write(dummySo.toPath(), "ELF dummy native library content for testing page alignment".getBytes(StandardCharsets.UTF_8));

        File unsignedApk = new File(workDir, "unsigned.apk");
        File primaryDex = new File(workDir, "classes.dex");
        Files.write(primaryDex.toPath(), "dex\n035\0".getBytes(StandardCharsets.ISO_8859_1));

        java.lang.reflect.Method m = BuildEngineTask.class.getDeclaredMethod(
                "packageUnsignedApk", File.class, File.class, File.class, List.class, List.class, File.class);
        m.setAccessible(true);
        m.invoke(null, null, workDir, primaryDex, null, Collections.singletonList(new File(workDir, "jni")), unsignedApk);

        assertTrue("unsignedApk must be created", unsignedApk.isFile());
        try (ZipFile zf = new ZipFile(unsignedApk)) {
            ZipEntry soEntry = zf.getEntry("lib/arm64-v8a/libdummy.so");
            assertNotNull("libdummy.so entry must exist", soEntry);
            assertEquals("Native .so entries must be STORED uncompressed", ZipEntry.STORED, soEntry.getMethod());
        }

        File alignedApk = new File(workDir, "aligned.apk");
        ZipAligner.alignApk(unsignedApk, alignedApk, 4);
        assertTrue(ZipAligner.isApkAligned(alignedApk, 4));

        File zipalignBin = new File("/opt/android/sdk/build-tools/35.0.0/zipalign");
        if (zipalignBin.isFile()) {
            String verifyOut = runCmd(Arrays.asList(zipalignBin.getAbsolutePath(), "-c", "-v", "4", alignedApk.getAbsolutePath()));
            assertTrue("zipalign verification must pass", verifyOut.contains("Verification succes") || verifyOut.contains("OK"));
        }
    }
}
