package com.example.sandrix;

import com.example.sandrix.engine.ApkAnalyzer;
import com.example.sandrix.logcat.LogcatStreamReader;
import com.example.sandrix.xml.XmlPatcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class Phase3ToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void patchElementAttributeByOccurrence_updatesAddsAndRemovesSafely() {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:layout_width=\"match_parent\"\n"
                + "    android:layout_height=\"match_parent\">\n"
                + "    <TextView\n"
                + "        android:id=\"@+id/tvHello\"\n"
                + "        android:layout_width=\"wrap_content\"\n"
                + "        android:text=\"Old Text\" />\n"
                + "</LinearLayout>\n";

        List<Integer> lines = XmlPatcher.findStartTagLineNumbers(xml);
        assertEquals(2, lines.size());
        assertEquals(Integer.valueOf(2), lines.get(0));
        assertEquals(Integer.valueOf(5), lines.get(1));

        String edited = XmlPatcher.patchElementAttributeByOccurrence(xml, 1, "android:text", "New Text");
        assertTrue(edited.contains("android:text=\"New Text\""));
        assertTrue(edited.contains("android:id=\"@+id/tvHello\""));

        String added = XmlPatcher.patchElementAttributeByOccurrence(edited, 1, "android:textColor", "#38BDF8");
        assertTrue(added.contains("android:textColor=\"#38BDF8\""));

        String removed = XmlPatcher.patchElementAttributeByOccurrence(added, 1, "android:textColor", "");
        assertFalse(removed.contains("android:textColor"));
    }

    @Test
    public void manifestModel_roundTripPreservesComponentsAndUpdatesPermissions() {
        String manifest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    package=\"com.sandrix.demo\">\n"
                + "    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\" />\n"
                + "    <uses-permission android:name=\"android.permission.INTERNET\" />\n"
                + "    <application android:allowBackup=\"true\" android:label=\"Demo\">\n"
                + "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                + "            <intent-filter>\n"
                + "                <action android:name=\"android.intent.action.MAIN\" />\n"
                + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
                + "            </intent-filter>\n"
                + "        </activity>\n"
                + "    </application>\n"
                + "</manifest>\n";

        XmlPatcher.ManifestModel model = XmlPatcher.parseManifestModel(manifest);
        assertEquals("com.sandrix.demo", model.packageName);
        assertEquals("24", model.minSdk);
        assertEquals("34", model.targetSdk);
        assertTrue(model.permissions.contains("android.permission.INTERNET"));
        assertEquals(1, model.components.size());
        assertTrue(model.components.get(0).hasLauncherIntent);

        model.targetSdk = "35";
        model.permissions.add("android.permission.CAMERA");
        model.components.get(0).exported = false;

        String updated = XmlPatcher.applyManifestModel(manifest, model);
        XmlPatcher.ManifestModel reparsed = XmlPatcher.parseManifestModel(updated);
        assertEquals("35", reparsed.targetSdk);
        assertTrue(reparsed.permissions.contains("android.permission.CAMERA"));
        assertFalse(reparsed.components.get(0).exported);
        assertTrue(reparsed.components.get(0).hasLauncherIntent);
    }

    @Test
    public void apkAnalyzer_readsZipBreakdownDexHeaderAndAxmlManifest() throws Exception {
        byte[] fakeDex = new byte[112];
        fakeDex[0] = 'd';
        fakeDex[1] = 'e';
        fakeDex[2] = 'x';
        fakeDex[3] = '\n';
        fakeDex[4] = '0';
        fakeDex[5] = '3';
        fakeDex[6] = '5';
        fakeDex[7] = 0;
        ByteBuffer dexBuf = ByteBuffer.wrap(fakeDex).order(ByteOrder.LITTLE_ENDIAN);
        dexBuf.putInt(32, 4096);
        dexBuf.putInt(56, 120);
        dexBuf.putInt(64, 45);
        dexBuf.putInt(80, 18);
        dexBuf.putInt(88, 99);
        dexBuf.putInt(96, 12);

        ApkAnalyzer.DexSummary parsedDex = ApkAnalyzer.parseDexHeader("classes.dex", fakeDex);
        assertNotNull(parsedDex);
        assertEquals("035", parsedDex.version);
        assertEquals(12, parsedDex.classDefCount);
        assertEquals(99, parsedDex.methodCount);

        byte[] axml = buildSyntheticAxml("com.sandrix.sample", "android.permission.INTERNET");
        ApkAnalyzer.BinaryManifestSummary axmlSummary = ApkAnalyzer.parseBinaryManifest(axml);
        assertEquals("com.sandrix.sample", axmlSummary.packageName);
        assertTrue(axmlSummary.permissions.contains("android.permission.INTERNET"));

        File apk = tmp.newFile("app-debug.apk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apk))) {
            zos.putNextEntry(new ZipEntry("classes.dex"));
            zos.write(fakeDex);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zos.write(axml);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("assets/config.json"));
            zos.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        ApkAnalyzer.ApkReport report = ApkAnalyzer.analyze(apk);
        assertEquals(3, report.entryCount);
        assertEquals(12, report.totalDexClasses);
        assertEquals(99, report.totalDexMethods);
        assertEquals("com.sandrix.sample", report.manifest.packageName);
        assertEquals(1, report.breakdown.get("Assets").fileCount);
    }

    @Test
    public void logcatStreamReader_parsesStandardFormats() {
        LogcatStreamReader.LogEntry e1 = LogcatStreamReader.parseLine(
                "11-18 10:20:30.456 E/AndroidRuntime( 4321): NullPointerException");
        assertEquals("E", e1.level);
        assertEquals("AndroidRuntime", e1.tag);
        assertEquals("NullPointerException", e1.message);

        LogcatStreamReader.LogEntry e2 = LogcatStreamReader.parseLine(
                "11-18 10:20:30.456  1000  1001 W SandrixEngine: Cache miss");
        assertEquals("W", e2.level);
        assertEquals("SandrixEngine", e2.tag);
    }

    private static byte[] buildSyntheticAxml(String pkgName, String permission) throws Exception {
        String[] strings = {"manifest", "package", pkgName, "uses-permission", "name", permission};
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
        sp.putInt(0x00000100); // UTF-8
        sp.putInt(spHeaderSize + spOffsetsSize);
        sp.putInt(0);
        for (int off : offsets) sp.putInt(off);
        sp.put(strData.toByteArray());

        byte[] manifestElem = buildStartElementChunk(0, 1, 2); // <manifest package=pkgName>
        byte[] permElem = buildStartElementChunk(3, 4, 5);     // <uses-permission name=permission>

        int totalSize = 8 + spChunkSize + manifestElem.length + permElem.length;
        ByteBuffer root = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        root.putShort((short) 0x0003);
        root.putShort((short) 8);
        root.putInt(totalSize);
        root.put(sp.array());
        root.put(manifestElem);
        root.put(permElem);
        return root.array();
    }

    private static byte[] buildStartElementChunk(int tagIdx, int attrNameIdx, int attrValIdx) {
        int chunkSize = 36 + 20;
        ByteBuffer b = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0102);
        b.putShort((short) 16);
        b.putInt(chunkSize);
        b.putInt(1); // line
        b.putInt(-1); // comment
        b.putInt(-1); // ns
        b.putInt(tagIdx);
        b.putShort((short) 20); // attrStart relative to +16
        b.putShort((short) 20); // attrSize
        b.putShort((short) 1);  // attrCount
        b.putShort((short) 0);  // idIndex
        b.putShort((short) 0);  // classIndex
        b.putShort((short) 0);  // styleIndex
        // Attribute (20 bytes)
        b.putInt(-1); // ns
        b.putInt(attrNameIdx);
        b.putInt(attrValIdx);
        b.putShort((short) 8);
        b.put((byte) 0);
        b.put((byte) 0x03); // TYPE_STRING
        b.putInt(attrValIdx);
        return b.array();
    }
}
