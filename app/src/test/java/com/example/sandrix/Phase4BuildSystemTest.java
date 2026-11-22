package com.example.sandrix;

import com.android.apksig.ApkSigner;
import com.example.sandrix.config.BuildSdParser;
import com.example.sandrix.engine.ApkAnalyzer;
import com.example.sandrix.engine.DexCacheManager;
import com.example.sandrix.engine.KeystoreGenerator;
import com.example.sandrix.engine.ZipAligner;
import com.example.sandrix.git.SandrixGitManager;
import com.example.sandrix.xml.XmlPatcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class Phase4BuildSystemTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void keystoreGenerator_createsPkcs12SavesConfigAndSignsApk() throws Exception {
        File projectDir = tmp.newFolder("release_proj");
        File ksFile = new File(projectDir, "release-key.p12");

        X509Certificate cert = KeystoreGenerator.generatePkcs12Keystore(
                ksFile,
                "sandrix123",
                "release",
                "sandrix123",
                "Sandrix Dev",
                "Sandrix Studio",
                "US",
                25);
        assertNotNull(cert);
        assertTrue(ksFile.isFile() && ksFile.length() > 0);

        KeystoreGenerator.KeystoreInspection inspection =
                KeystoreGenerator.inspectKeystore(ksFile, "sandrix123");
        assertTrue(inspection.errorMessage, inspection.valid);
        assertEquals("PKCS12", inspection.storeType);
        assertTrue(inspection.aliases.contains("release"));
        assertTrue(inspection.subject.contains("Sandrix Dev"));
        assertFalse(inspection.sha256.isEmpty());

        KeystoreGenerator.saveSigningConfig(projectDir,
                new KeystoreGenerator.SigningConfigModel(
                        "release-key.p12", "PKCS12", "sandrix123", "release", "sandrix123", true));
        KeystoreGenerator.SigningConfigModel loaded = KeystoreGenerator.loadSigningConfig(projectDir);
        assertTrue(loaded.configured);
        assertEquals("release-key.p12", loaded.storeFilePath);
        assertEquals("release", loaded.keyAlias);

        String gitignore = readUtf8(new File(projectDir, ".gitignore"));
        assertTrue(gitignore.contains(KeystoreGenerator.SIGNING_PROPS_FILE));
        assertTrue(gitignore.contains("*.p12"));

        // Create minimal APK archive with valid binary AXML manifest and sign with generated key/cert
        File unsignedApk = new File(projectDir, "unsigned.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk))) {
            zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zos.write(buildSyntheticAxml("com.sandrix.release", "android.permission.INTERNET"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("assets/hello.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, "sandrix123".toCharArray());
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey("release", "sandrix123".toCharArray());
        X509Certificate x509 = (X509Certificate) ks.getCertificate("release");

        File signedApk = new File(projectDir, "signed-release.apk");
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "release", privateKey, Collections.singletonList(x509)).build();
        new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();

        ApkAnalyzer.ApkReport report = ApkAnalyzer.analyze(signedApk);
        assertTrue("APK signature should verify cleanly: errors=" + report.signing.errors
                        + " warnings=" + report.signing.warnings
                        + " v1=" + report.signing.v1Verified
                        + " v2=" + report.signing.v2Verified
                        + " v3=" + report.signing.v3Verified,
                report.signing.verified);
        assertFalse(report.signing.signerSha256s.isEmpty());
        assertEquals("com.sandrix.release", report.manifest.packageName);
        assertEquals("24", report.manifest.minSdkVersion);
        assertTrue("Expected v2 and v3 signatures verified (v1=" + report.signing.v1Verified
                        + ", v2=" + report.signing.v2Verified
                        + ", v3=" + report.signing.v3Verified + ")",
                report.signing.v2Verified && report.signing.v3Verified);
    }

    @Test
    public void buildSdParser_managesDependenciesAndBuildVariants() throws Exception {
        File buildSd = tmp.newFile("build.sd");
        String initial = "android {\n"
                + "    compileSdk 36\n"
                + "    defaultConfig {\n"
                + "        applicationId \"com.sandrix.pro\"\n"
                + "        minSdk 24\n"
                + "        targetSdk 34\n"
                + "        versionCode 3\n"
                + "        versionName \"2.1\"\n"
                + "    }\n"
                + "    buildTypes {\n"
                + "        debug {\n"
                + "            applicationIdSuffix \".debug\"\n"
                + "            versionNameSuffix \"-dev\"\n"
                + "        }\n"
                + "        release {\n"
                + "            applicationIdSuffix \".pro\"\n"
                + "            versionNameSuffix \"-rc1\"\n"
                + "            minifyEnabled true\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        writeUtf8(buildSd, initial);

        BuildSdParser.addOrUpdateDependency(buildSd, "implementation", "com.squareup.okhttp3:okhttp:4.11.0");
        BuildSdParser.addOrUpdateDependency(buildSd, "compileOnly", "androidx.annotation:annotation:1.8.0");
        BuildSdParser.addOrUpdateDependency(buildSd, "implementation", "com.squareup.okhttp3:okhttp:4.12.0");

        BuildSdParser.BuildSdConfig cfg = BuildSdParser.parse(buildSd);
        assertEquals(2, cfg.dependencies.size());
        assertEquals("4.12.0", cfg.dependencies.get(0).version);
        assertTrue(cfg.dependencies.get(1).isCompileOnly());

        assertEquals("com.sandrix.pro.debug", cfg.effectiveApplicationId("com.sandrix.fallback", false));
        assertEquals("2.1-dev", cfg.effectiveVersionName(false));
        assertEquals("com.sandrix.pro.pro", cfg.effectiveApplicationId("com.sandrix.fallback", true));
        assertEquals("2.1-rc1", cfg.effectiveVersionName(true));
        assertTrue(cfg.releaseMinifyEnabled);

        boolean removed = BuildSdParser.removeDependency(buildSd, "androidx.annotation:annotation:1.8.0");
        assertTrue(removed);
        BuildSdParser.BuildSdConfig afterRemove = BuildSdParser.parse(buildSd);
        assertEquals(1, afterRemove.dependencies.size());
        assertEquals("com.squareup.okhttp3", afterRemove.dependencies.get(0).group);
    }

    @Test
    public void dexCacheManager_inspectsAndClearsEngineCaches() throws Exception {
        File engineRoot = tmp.newFolder("engine_root");
        File dexCache = new File(engineRoot, "dex_cache");
        File mavenCache = new File(engineRoot, "maven_cache");
        assertTrue(dexCache.mkdirs());
        assertTrue(mavenCache.mkdirs());

        writeUtf8(new File(dexCache, "lib1_classes.jar"), "1234567890");
        writeUtf8(new File(dexCache, "lib2_classes.jar"), "abcdef");
        writeUtf8(new File(mavenCache, "dep.jar"), "maven-bytes");

        DexCacheManager.CacheStats stats = DexCacheManager.inspectEngineCaches(engineRoot);
        assertEquals(2, stats.dexEntryCount);
        assertEquals(16L, stats.dexCacheBytes);
        assertEquals(11L, stats.mavenCacheBytes);

        int deleted = DexCacheManager.clearDexCache(engineRoot);
        assertEquals(2, deleted);
        DexCacheManager.CacheStats cleared = DexCacheManager.inspectEngineCaches(engineRoot);
        assertEquals(0, cleared.dexEntryCount);
        assertEquals(0L, cleared.dexCacheBytes);
        assertEquals(11L, cleared.mavenCacheBytes);
    }

    @Test
    public void gitManager_stageUnstageCommitStagedDiscardStashTagAndDiff() throws Exception {
        File repoDir = tmp.newFolder("git_stage_stash_tag");
        File mainJava = new File(repoDir, "Main.java");
        writeUtf8(mainJava, "public class Main { int v = 1; }\n");

        SandrixGitManager git = new SandrixGitManager((File) tmp.getRoot());
        assertTrue(git.initRepository(repoDir, "Tester", "tester@sandrix.dev").success);
        assertTrue(git.commitAll(repoDir, "Initial commit", "Tester", "tester@sandrix.dev").success);

        File extra = new File(repoDir, "Extra.java");
        File keepUntracked = new File(repoDir, "Notes.txt");
        writeUtf8(mainJava, "public class Main { int v = 2; }\n");
        writeUtf8(extra, "public class Extra {}\n");
        writeUtf8(keepUntracked, "temporary\n");

        // Stage only Extra.java
        assertTrue(git.stageFile(repoDir, "Extra.java").success);
        assertTrue(git.stageFile(repoDir, "Notes.txt").success);
        assertTrue(git.unstageFile(repoDir, "Notes.txt").success);

        SandrixGitManager.GitRepoStatus statusBeforeCommit = git.inspectStatus(repoDir);
        boolean foundExtraStaged = false;
        boolean foundMainUnstaged = false;
        for (SandrixGitManager.GitFileChange ch : statusBeforeCommit.changes) {
            if ("Extra.java".equals(ch.path)) foundExtraStaged = ch.staged;
            if ("Main.java".equals(ch.path)) foundMainUnstaged = !ch.staged;
        }
        assertTrue("Extra.java should be staged", foundExtraStaged);
        assertTrue("Main.java should remain unstaged", foundMainUnstaged);

        SandrixGitManager.GitOpResult partial =
                git.commitStaged(repoDir, "Add Extra only", "Tester", "tester@sandrix.dev");
        assertTrue(partial.message, partial.success);

        String commitDiff = git.inspectCommitDiff(repoDir, partial.status.recentCommits.get(0).shortHash);
        assertTrue(commitDiff.contains("Add Extra only"));
        assertTrue(commitDiff.contains("Extra.java"));

        // Stash remaining dirty edits, check clean, then pop stash
        assertTrue(git.stashSave(repoDir, "WIP v2").success);
        assertEquals(1, git.inspectStatus(repoDir).stashes.size());
        assertEquals("public class Main { int v = 1; }\n", readUtf8(mainJava));

        assertTrue(git.stashApplyOrPop(repoDir, 0, true).success);
        assertEquals(0, git.inspectStatus(repoDir).stashes.size());
        assertEquals("public class Main { int v = 2; }\n", readUtf8(mainJava));

        // Discard Main.java changes back to HEAD
        assertTrue(git.discardFileChanges(repoDir, "Main.java").success);
        assertEquals("public class Main { int v = 1; }\n", readUtf8(mainJava));

        // Create release tag
        SandrixGitManager.GitOpResult tagRes = git.createTag(repoDir, "v1.0.0", "Release 1.0");
        assertTrue(tagRes.message, tagRes.success);
        assertTrue(tagRes.status.tags.contains("v1.0.0"));
    }

    @Test
    public void gitManager_mergeBranchCleanAndConflictResolution() throws Exception {
        File repoDir = tmp.newFolder("git_merge_repo");
        File shared = new File(repoDir, "App.java");
        writeUtf8(shared, "version=1\n");

        SandrixGitManager git = new SandrixGitManager((File) tmp.getRoot());
        assertTrue(git.initRepository(repoDir, "Tester", "tester@sandrix.dev").success);
        assertTrue(git.commitAll(repoDir, "Base", "Tester", "tester@sandrix.dev").success);
        String defaultBranch = git.inspectStatus(repoDir).currentBranch;

        // Clean fast-forward merge
        assertTrue(git.createBranch(repoDir, "feature-clean", true).success);
        File featFile = new File(repoDir, "Feature.java");
        writeUtf8(featFile, "class Feature {}\n");
        assertTrue(git.commitAll(repoDir, "Feature commit", "Tester", "tester@sandrix.dev").success);

        assertTrue(git.checkoutBranch(repoDir, defaultBranch).success);
        SandrixGitManager.GitOpResult ffRes = git.mergeBranch(repoDir, "feature-clean");
        assertTrue(ffRes.message, ffRes.success);
        assertTrue(featFile.exists());

        // Diverging edit -> conflict -> resolveConflict (Ours)
        assertTrue(git.createBranch(repoDir, "feature-conflict", true).success);
        writeUtf8(shared, "version=theirs\n");
        assertTrue(git.commitAll(repoDir, "Theirs change", "Tester", "tester@sandrix.dev").success);

        assertTrue(git.checkoutBranch(repoDir, defaultBranch).success);
        writeUtf8(shared, "version=ours\n");
        assertTrue(git.commitAll(repoDir, "Ours change", "Tester", "tester@sandrix.dev").success);

        SandrixGitManager.GitOpResult conflictRes = git.mergeBranch(repoDir, "feature-conflict");
        assertFalse("Expected merge conflict", conflictRes.success);
        boolean hasConflictBadge = false;
        for (SandrixGitManager.GitFileChange ch : conflictRes.status.changes) {
            if ("App.java".equals(ch.path) && ch.type == SandrixGitManager.ChangeType.CONFLICTING) {
                hasConflictBadge = true;
            }
        }
        assertTrue("App.java must be marked CONFLICTING", hasConflictBadge);

        SandrixGitManager.GitOpResult resolved = git.resolveConflict(repoDir, "App.java", true);
        assertTrue(resolved.message, resolved.success);
        assertEquals("version=ours\n", readUtf8(shared));

        SandrixGitManager.GitOpResult committed =
                git.commitStaged(repoDir, "Resolve conflict keeping ours", "Tester", "tester@sandrix.dev");
        assertTrue(committed.message, committed.success);
    }

    private static void writeUtf8(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readUtf8(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static byte[] buildSyntheticAxml(String pkgName, String permission) throws Exception {
        String ns = "http://schemas.android.com/apk/res/android";
        String[] strings = {
                "minSdkVersion",  // 0 -> 0x0101020c
                "name",           // 1 -> 0x01010003
                "android",        // 2
                ns,               // 3
                "manifest",       // 4
                "package",        // 5
                pkgName,          // 6
                "uses-sdk",       // 7
                "uses-permission",// 8
                permission        // 9
        };
        ByteArrayOutputStream strData = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = strData.size();
            byte[] utf8 = strings[i].getBytes(StandardCharsets.UTF_8);
            strData.write(utf8.length);
            strData.write(utf8.length);
            strData.write(utf8);
            strData.write(0);
        }
        while (strData.size() % 4 != 0) strData.write(0);

        int spHeaderSize = 28;
        int spOffsetsSize = strings.length * 4;
        int spChunkSize = spHeaderSize + spOffsetsSize + strData.size();
        ByteBuffer sp = ByteBuffer.allocate(spChunkSize).order(ByteOrder.LITTLE_ENDIAN);
        sp.putShort((short) 0x0001);
        sp.putShort((short) spHeaderSize);
        sp.putInt(spChunkSize);
        sp.putInt(strings.length);
        sp.putInt(0);
        sp.putInt(0x00000100);
        sp.putInt(spHeaderSize + spOffsetsSize);
        sp.putInt(0);
        for (int off : offsets) sp.putInt(off);
        sp.put(strData.toByteArray());

        // RES_XML_RESOURCE_MAP_TYPE (0x0180)
        int resMapSize = 8 + 2 * 4;
        ByteBuffer resMap = ByteBuffer.allocate(resMapSize).order(ByteOrder.LITTLE_ENDIAN);
        resMap.putShort((short) 0x0180);
        resMap.putShort((short) 8);
        resMap.putInt(resMapSize);
        resMap.putInt(0x0101020c); // android:minSdkVersion
        resMap.putInt(0x01010003); // android:name

        byte[] startNs = buildNamespaceChunk(0x0100, 2, 3);
        byte[] manifestElem = buildStartElementChunk(-1, 4, -1, 5, 6, 0x03, 6);
        byte[] usesSdkStart = buildStartElementChunk(-1, 7, 3, 0, -1, 0x10, 24);
        byte[] usesSdkEnd = buildEndElementChunk(-1, 7);
        byte[] permStart = buildStartElementChunk(-1, 8, 3, 1, 9, 0x03, 9);
        byte[] permEnd = buildEndElementChunk(-1, 8);
        byte[] manifestEnd = buildEndElementChunk(-1, 4);
        byte[] endNs = buildNamespaceChunk(0x0101, 2, 3);

        byte[][] chunks = {sp.array(), resMap.array(), startNs, manifestElem, usesSdkStart, usesSdkEnd, permStart, permEnd, manifestEnd, endNs};
        int bodySize = 0;
        for (byte[] c : chunks) bodySize += c.length;
        int totalSize = 8 + bodySize;
        ByteBuffer root = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        root.putShort((short) 0x0003);
        root.putShort((short) 8);
        root.putInt(totalSize);
        for (byte[] c : chunks) root.put(c);
        return root.array();
    }

    private static byte[] buildNamespaceChunk(int type, int prefixIdx, int uriIdx) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) type);
        b.putShort((short) 16);
        b.putInt(24);
        b.putInt(1);
        b.putInt(-1);
        b.putInt(prefixIdx);
        b.putInt(uriIdx);
        return b.array();
    }

    private static byte[] buildStartElementChunk(
            int nsIdx, int tagIdx, int attrNsIdx, int attrNameIdx, int attrRawValIdx, int attrType, int attrData) {
        int chunkSize = 36 + 20;
        ByteBuffer b = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0102);
        b.putShort((short) 16);
        b.putInt(chunkSize);
        b.putInt(1);
        b.putInt(-1);
        b.putInt(nsIdx);
        b.putInt(tagIdx);
        b.putShort((short) 20);
        b.putShort((short) 20);
        b.putShort((short) 1);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putInt(attrNsIdx);
        b.putInt(attrNameIdx);
        b.putInt(attrRawValIdx);
        b.putShort((short) 8);
        b.put((byte) 0);
        b.put((byte) attrType);
        b.putInt(attrData);
        return b.array();
    }

    private static byte[] buildEndElementChunk(int nsIdx, int tagIdx) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0103);
        b.putShort((short) 16);
        b.putInt(24);
        b.putInt(1);
        b.putInt(-1);
        b.putInt(nsIdx);
        b.putInt(tagIdx);
        return b.array();
    }

    @Test
    public void zipAligner_alignsUncompressedEntriesAndVerifiesAlignment() throws Exception {
        File dir = tmp.newFolder("align_test");
        File unalignedApk = new File(dir, "unaligned.apk");

        byte[] axml = buildSyntheticAxml("com.sandrix.align", "android.permission.INTERNET");
        byte[] arscData = "SYNTHETIC_ARSC_RESOURCE_TABLE_DATA_FOR_ALIGNMENT_TEST_1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] soData = "SHARED_LIBRARY_SO_BYTES".getBytes(StandardCharsets.UTF_8);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unalignedApk))) {
            // Deflated entry
            ZipEntry e1 = new ZipEntry("AndroidManifest.xml");
            zos.putNextEntry(e1);
            zos.write(axml);
            zos.closeEntry();

            // STORED entry (resources.arsc)
            ZipEntry e2 = new ZipEntry("resources.arsc");
            e2.setMethod(ZipEntry.STORED);
            e2.setSize(arscData.length);
            e2.setCompressedSize(arscData.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(arscData);
            e2.setCrc(crc.getValue());
            zos.putNextEntry(e2);
            zos.write(arscData);
            zos.closeEntry();

            // STORED entry (.so)
            ZipEntry e3 = new ZipEntry("lib/arm64-v8a/libtest.so");
            e3.setMethod(ZipEntry.STORED);
            e3.setSize(soData.length);
            e3.setCompressedSize(soData.length);
            crc.reset();
            crc.update(soData);
            e3.setCrc(crc.getValue());
            zos.putNextEntry(e3);
            zos.write(soData);
            zos.closeEntry();
        }

        assertFalse("Unaligned APK should fail alignment check", ZipAligner.isApkAligned(unalignedApk, 4));

        File alignedApk = new File(dir, "aligned.apk");
        ZipAligner.alignApk(unalignedApk, alignedApk, 4);
        assertTrue("Aligned APK must pass alignment check", ZipAligner.isApkAligned(alignedApk, 4));

        // Sign aligned APK and verify it remains aligned and passes verification
        File ksFile = new File(dir, "test.p12");
        KeystoreGenerator.generatePkcs12Keystore(ksFile, "pass123", "test", "pass123", "Test", "Org", "US", 10);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, "pass123".toCharArray());
        }
        PrivateKey pk = (PrivateKey) ks.getKey("test", "pass123".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("test");

        File signedApk = new File(dir, "signed.apk");
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("test", pk, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(alignedApk)
                .setOutputApk(signedApk)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();

        assertTrue("Signed APK must remain aligned", ZipAligner.isApkAligned(signedApk, 4));
        com.android.apksig.ApkVerifier verifier = new com.android.apksig.ApkVerifier.Builder(signedApk).build();
        com.android.apksig.ApkVerifier.Result res = verifier.verify();
        assertTrue("Signature must verify", res.isVerified());
    }

    @Test
    public void xmlPatcher_injectsAppThemeAndQualifiesComponents() throws Exception {
        File projDir = tmp.newFolder("theme_patch_proj");
        File manifest = new File(projDir, "AndroidManifest.xml");
        String rawManifest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    package=\"com.sandrix.myapp\">\n"
                + "    <application android:label=\"TestApp\">\n"
                + "        <activity android:name=\".MainActivity\" android:exported=\"true\" />\n"
                + "    </application>\n"
                + "</manifest>\n";
        try (FileOutputStream fos = new FileOutputStream(manifest)) {
            fos.write(rawManifest.getBytes(StandardCharsets.UTF_8));
        }

        File mainJava = new File(projDir, "MainActivity.java");
        try (FileOutputStream fos = new FileOutputStream(mainJava)) {
            fos.write("package com.sandrix.myapp;\npublic class MainActivity {}".getBytes(StandardCharsets.UTF_8));
        }

        XmlPatcher.syncManifestPackage(mainJava, manifest, null);
        String synced = new String(java.nio.file.Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);

        assertTrue("Manifest must have android:theme injected", synced.contains("android:theme=\"@style/AppTheme\""));
        assertTrue("Activity component name must be fully qualified", synced.contains("android:name=\"com.sandrix.myapp.MainActivity\""));

        File stylesFile = new File(projDir, "res/values/styles.xml");
        assertTrue("styles.xml must be auto-created with AppTheme", stylesFile.exists());
        String stylesContent = new String(java.nio.file.Files.readAllBytes(stylesFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("styles.xml must inherit MaterialComponents or AppCompat", stylesContent.contains("parent=\"Theme.MaterialComponents.DayNight.NoActionBar\""));
    }
}

