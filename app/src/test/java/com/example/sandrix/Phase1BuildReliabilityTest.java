package com.example.sandrix;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.engine.BuildEngineTask;
import com.example.sandrix.engine.ClassFileSanitizer;
import com.example.sandrix.engine.DexCacheManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Phase1BuildReliabilityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sanitizerFixesR8MethodParametersNullNameCrash() throws Exception {
        byte[] crashingClass = buildMinimalClassWithMethodParameters("com/example/ActionBarDrawerToggle$1", true);

        // Attempt D8 on raw class (R8 4.0.52 crashed here; R8 8.7.18 handles it cleanly)
        File badDir = tmp.newFolder("bad_in");
        File badClassFile = new File(badDir, "ActionBarDrawerToggle$1.class");
        try (FileOutputStream fos = new FileOutputStream(badClassFile)) {
            fos.write(crashingClass);
        }
        File badOut = new File(tmp.getRoot(), "bad_dex_out");
        //noinspection ResultOfMethodCallIgnored
        badOut.mkdirs();

        boolean rawHandled = true;
        try {
            D8.run(D8Command.builder()
                    .setMinApiLevel(24)
                    .setMode(CompilationMode.DEBUG)
                    .addProgramFiles(badClassFile.toPath())
                    .setOutput(badOut.toPath(), OutputMode.DexIndexed)
                    .build());
        } catch (Throwable t) {
            rawHandled = false;
        }
        assertTrue("Expected R8 8.7+ to handle MethodParameters cleanly", rawHandled);

        // Now sanitize and verify ClassFileSanitizer cleans name_index=0 and D8 succeeds cleanly
        ClassFileSanitizer.SanitizeResult res = ClassFileSanitizer.sanitizeClassBytes(crashingClass);
        assertTrue(res.modified);
        File goodDir = tmp.newFolder("good_in");
        File goodClassFile = new File(goodDir, "ActionBarDrawerToggle$1.class");
        try (FileOutputStream fos = new FileOutputStream(goodClassFile)) {
            fos.write(res.bytes);
        }
        File goodOut = new File(tmp.getRoot(), "good_dex_out");
        //noinspection ResultOfMethodCallIgnored
        goodOut.mkdirs();

        D8.run(D8Command.builder()
                .setMinApiLevel(24)
                .setMode(CompilationMode.DEBUG)
                .addProgramFiles(goodClassFile.toPath())
                .setOutput(goodOut.toPath(), OutputMode.DexIndexed)
                .build());

        File dex = new File(goodOut, "classes.dex");
        assertTrue("Sanitized class must dex cleanly", dex.exists() && dex.length() > 0);
    }

    @Test
    public void sanitizerLeavesValidMethodParametersUntouched() throws Exception {
        byte[] validClass = buildMinimalClassWithMethodParameters("com/example/CleanClass", false);
        ClassFileSanitizer.SanitizeResult res = ClassFileSanitizer.sanitizeClassBytes(validClass);
        assertFalse(res.modified);
    }

    @Test
    public void dexCacheSanitizesChunksAndCachesLargeLibraries() throws Exception {
        File cacheDir = tmp.newFolder("dex_cache");
        File inputJar = new File(tmp.getRoot(), "heavy-appcompat-sim.jar");

        // Build JAR with >450 classes including MethodParameters name_index=0 entries + a resource file
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(inputJar))) {
            for (int i = 0; i < 460; i++) {
                String internalName = "com/example/lib/Widget" + i;
                boolean injectNullParam = (i % 25 == 0);
                byte[] cls = buildMinimalClassWithMethodParameters(internalName, injectNullParam);
                zos.putNextEntry(new ZipEntry(internalName + ".class"));
                zos.write(cls);
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("META-INF/services/com.example.Plugin"));
            zos.write("com.example.lib.Widget0\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        DexCacheManager manager = new DexCacheManager(cacheDir);
        // Pass null android.jar so test runs standalone; simple java/lang/Object classes don't fail dexing
        DexCacheManager.PreDexResult first = manager.preparePreDexedLibraries(
                Collections.singletonList(inputJar),
                Collections.emptyList(),
                24,
                null,
                false,
                new com.android.tools.r8.DiagnosticsHandler() {},
                (msg, err) -> {
                    if (err) fail("Unexpected error log: " + msg);
                },
                () -> false
        );
        assertEquals(1, first.newlyDexed);
        assertEquals(1, first.sanitizedArchives);
        assertEquals(1, first.cachedDexFiles.size());
        File cached = first.cachedDexFiles.get(0);
        assertTrue(cached.exists() && cached.length() > 0);
        try (ZipFile zf = new ZipFile(cached)) {
            assertNotNull(zf.getEntry("classes.dex"));
        }

        // Second run must hit cache
        DexCacheManager.PreDexResult second = manager.preparePreDexedLibraries(
                Collections.singletonList(inputJar),
                Collections.emptyList(),
                24,
                null,
                false,
                new com.android.tools.r8.DiagnosticsHandler() {},
                null,
                () -> false
        );
        assertEquals(1, second.cacheHits);
        assertEquals(0, second.newlyDexed);
    }

    @Test
    public void deduplicatePreservesResourceOnlyJars() throws Exception {
        File classesDir = tmp.newFolder("app_classes");
        File dedupDir = tmp.newFolder("dedup_out");
        File jar1 = new File(tmp.getRoot(), "lib1.jar");
        File jar2 = new File(tmp.getRoot(), "lib2_svc.jar");

        byte[] dupCls = buildMinimalClassWithMethodParameters("com/example/shared/Shared", false);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar1))) {
            zos.putNextEntry(new ZipEntry("com/example/shared/Shared.class"));
            zos.write(dupCls);
            zos.closeEntry();
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar2))) {
            zos.putNextEntry(new ZipEntry("com/example/shared/Shared.class"));
            zos.write(dupCls);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/services/io.Demo"));
            zos.write("io.DemoImpl\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        List<String> logs = new ArrayList<>();
        List<File> out = BuildEngineTask.deduplicateJarLibsForD8(
                classesDir, dedupDir, Arrays.asList(jar1, jar2), Collections.emptyList(), logs);
        assertEquals("Both primary JAR and resource-bearing secondary JAR should be preserved", 2, out.size());
        try (ZipFile zf = new ZipFile(out.get(1))) {
            assertNotNull(zf.getEntry("META-INF/services/io.Demo"));
        }
    }

    @Test
    public void buildSdParserSupportsGradleFallbackAndBuildConfigFields() throws Exception {
        File projectRoot = tmp.newFolder("sample_gradle_proj");
        File appDir = new File(projectRoot, "app");
        //noinspection ResultOfMethodCallIgnored
        appDir.mkdirs();
        File appGradle = new File(appDir, "build.gradle");
        try (FileOutputStream fos = new FileOutputStream(appGradle)) {
            fos.write(("android {\n"
                    + "    namespace 'com.demo.app'\n"
                    + "    compileSdk 36\n"
                    + "    defaultConfig {\n"
                    + "        applicationId 'com.demo.app'\n"
                    + "        minSdk 26\n"
                    + "        targetSdk 35\n"
                    + "        versionCode 4\n"
                    + "        versionName '2.1'\n"
                    + "        buildConfigField \"String\", \"API_HOST\", \"\\\"https://api.example.com\\\"\"\n"
                    + "    }\n"
                    + "    buildTypes {\n"
                    + "        debug {\n"
                    + "            applicationIdSuffix '.debug'\n"
                    + "            versionNameSuffix '-dbg'\n"
                    + "        }\n"
                    + "        release {\n"
                    + "            minifyEnabled false\n"
                    + "        }\n"
                    + "    }\n"
                    + "}\n"
                    + "dependencies {\n"
                    + "    implementation 'androidx.appcompat:appcompat:1.7.0'\n"
                    + "}\n").getBytes(StandardCharsets.UTF_8));
        }

        BuildSdParser.BuildSdConfig cfg = BuildSdParser.parseProjectConfig(projectRoot);
        assertEquals(36, cfg.compileSdk);
        assertEquals(26, cfg.minSdk);
        assertEquals(35, cfg.targetSdk);
        assertEquals(4, cfg.versionCode);
        assertEquals("com.demo.app.debug", cfg.effectiveApplicationId("com.demo.app", false));
        assertEquals("2.1-dbg", cfg.effectiveVersionName(false));
        assertEquals(1, cfg.buildConfigFields.size());
        assertEquals("API_HOST", cfg.buildConfigFields.get(0).name);
        assertEquals(1, cfg.dependencies.size());
        assertEquals("androidx.appcompat", cfg.dependencies.get(0).group);
        assertTrue("Expected zero warnings for standard Groovy build.gradle", cfg.warnings.isEmpty());
    }

    /**
     * Synthesizes a valid Java 8 (classfile v52) class with an {@code init(String)} method
     * containing a {@code MethodParameters} attribute whose parameter name index is either
     * {@code 0} (triggering R8 4.0.x NPE) or a valid Utf8 constant pool entry.
     */
    private static byte[] buildMinimalClassWithMethodParameters(String internalClassName, boolean nullParamName)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(0xCAFEBABE);
        dos.writeShort(0);   // minor
        dos.writeShort(52);  // major (Java 8)

        // Constant pool entries (1..9):
        // 1: Utf8 <internalClassName>
        // 2: Class #1
        // 3: Utf8 java/lang/Object
        // 4: Class #3
        // 5: Utf8 work
        // 6: Utf8 (Ljava/lang/String;)V
        // 7: Utf8 Code
        // 8: Utf8 MethodParameters
        // 9: Utf8 arg0
        dos.writeShort(10); // cpCount = 10
        writeUtf8Cp(dos, internalClassName);
        dos.writeByte(7); dos.writeShort(1);
        writeUtf8Cp(dos, "java/lang/Object");
        dos.writeByte(7); dos.writeShort(3);
        writeUtf8Cp(dos, "work");
        writeUtf8Cp(dos, "(Ljava/lang/String;)V");
        writeUtf8Cp(dos, "Code");
        writeUtf8Cp(dos, "MethodParameters");
        writeUtf8Cp(dos, "arg0");

        dos.writeShort(0x0021); // public super
        dos.writeShort(2);      // this_class = #2
        dos.writeShort(4);      // super_class = #4
        dos.writeShort(0);      // interfaces_count
        dos.writeShort(0);      // fields_count

        // methods_count = 1
        dos.writeShort(1);
        dos.writeShort(0x0001); // public
        dos.writeShort(5);      // name = work
        dos.writeShort(6);      // descriptor = (Ljava/lang/String;)V
        dos.writeShort(2);      // attributes: Code + MethodParameters

        // Code attribute: max_stack=0, max_locals=2, code=[RETURN (0xB1)], ex_table=0, code_attrs=0
        dos.writeShort(7);      // attr_name = #7 (Code)
        dos.writeInt(13);       // attr_length
        dos.writeShort(0);      // max_stack
        dos.writeShort(2);      // max_locals
        dos.writeInt(1);        // code_length
        dos.writeByte(0xB1);    // return
        dos.writeShort(0);      // exception_table_length
        dos.writeShort(0);      // attributes_count

        // MethodParameters attribute
        dos.writeShort(8);      // attr_name = #8 (MethodParameters)
        dos.writeInt(5);        // attr_length = 1 + 1*4
        dos.writeByte(1);       // parameters_count = 1
        dos.writeShort(nullParamName ? 0 : 9); // name_index = 0 (bug trigger) or #9 ("arg0")
        dos.writeShort(0x8000); // access_flags = ACC_MANDATED

        dos.writeShort(0);      // class attributes_count
        dos.flush();
        return baos.toByteArray();
    }

    @Test
    public void testVerifyingD8MergesPreDexedJarWithAppClass() throws Exception {
        // 1. Create a pre-dexed JAR with a library class "com.example.lib.LibWidget"
        byte[] libClassBytes = buildMinimalClassWithMethodParameters("com/example/lib/LibWidget", false);
        File libInDir = tmp.newFolder("lib_in");
        File libClassFile = new File(libInDir, "LibWidget.class");
        try (FileOutputStream fos = new FileOutputStream(libClassFile)) {
            fos.write(libClassBytes);
        }
        File preDexJar = new File(tmp.getRoot(), "predexed_lib.jar");
        D8.run(D8Command.builder()
                .setMinApiLevel(24)
                .setMode(CompilationMode.RELEASE)
                .setIntermediate(true)
                .addProgramFiles(libClassFile.toPath())
                .setOutput(preDexJar.toPath(), OutputMode.DexIndexed)
                .build());
        assertTrue("Pre-dex JAR must exist", preDexJar.exists());
        try (ZipFile zf = new ZipFile(preDexJar)) {
            assertNotNull("Pre-dex JAR must contain classes.dex", zf.getEntry("classes.dex"));
        }

        // 2. Create an app class "com.example.app.MainActivity"
        byte[] appClassBytes = buildMinimalClassWithMethodParameters("com/example/app/MainActivity", false);
        File appInDir = tmp.newFolder("app_in");
        File appClassFile = new File(appInDir, "MainActivity.class");
        try (FileOutputStream fos = new FileOutputStream(appClassFile)) {
            fos.write(appClassBytes);
        }

        // 3. Run final D8 merging app class and the pre-dexed jar
        File finalDexDir = tmp.newFolder("final_dex_out");
        D8.run(D8Command.builder()
                .setMinApiLevel(24)
                .setMode(CompilationMode.DEBUG)
                .addProgramFiles(appClassFile.toPath())
                .addProgramFiles(preDexJar.toPath())
                .setOutput(finalDexDir.toPath(), OutputMode.DexIndexed)
                .build());

        File outDex = new File(finalDexDir, "classes.dex");
        assertTrue("Final classes.dex must exist", outDex.exists() && outDex.length() > 0);
        byte[] dexBytes = Files.readAllBytes(outDex.toPath());
        String dexContent = new String(dexBytes, StandardCharsets.ISO_8859_1);
        assertTrue("Must contain app class", dexContent.contains("com/example/app/MainActivity"));
        assertTrue("Must contain lib class", dexContent.contains("com/example/lib/LibWidget"));
        System.out.println("Final classes.dex size: " + outDex.length() + " bytes; contains both classes!");
    }

    private static void writeUtf8Cp(DataOutputStream dos, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        dos.writeByte(1);
        dos.writeShort(bytes.length);
        dos.write(bytes);
    }
}

