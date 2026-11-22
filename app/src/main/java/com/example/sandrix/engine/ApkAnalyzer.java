package com.example.sandrix.engine;

import com.android.apksig.ApkVerifier;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-Java Android APK Analyzer inspecting ZIP structure, DEX headers,
 * binary AXML {@code AndroidManifest.xml}, and APK v1/v2/v3 signatures via {@code apksig}.
 */
public final class ApkAnalyzer {

    private ApkAnalyzer() {}

    public static final class ApkEntryInfo {
        public final String path;
        public final String category;
        public final long compressedSize;
        public final long uncompressedSize;

        public ApkEntryInfo(String path, String category, long compressedSize, long uncompressedSize) {
            this.path = path;
            this.category = category;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
        }
    }

    public static final class CategoryStats {
        public final String category;
        public int fileCount;
        public long compressedBytes;
        public long uncompressedBytes;

        public CategoryStats(String category) {
            this.category = category;
        }
    }

    public static final class DexSummary {
        public final String entryName;
        public final String version;
        public final int fileSize;
        public final int stringCount;
        public final int typeCount;
        public final int fieldCount;
        public final int methodCount;
        public final int classDefCount;

        public DexSummary(
                String entryName,
                String version,
                int fileSize,
                int stringCount,
                int typeCount,
                int fieldCount,
                int methodCount,
                int classDefCount) {
            this.entryName = entryName;
            this.version = version;
            this.fileSize = fileSize;
            this.stringCount = stringCount;
            this.typeCount = typeCount;
            this.fieldCount = fieldCount;
            this.methodCount = methodCount;
            this.classDefCount = classDefCount;
        }
    }

    public static final class BinaryManifestSummary {
        public String packageName = "";
        public String versionCode = "";
        public String versionName = "";
        public String minSdkVersion = "";
        public String targetSdkVersion = "";
        public String applicationLabel = "";
        public boolean debuggable = false;
        public int componentCount = 0;
        public int exportedComponentCount = 0;
        public final List<String> permissions = new ArrayList<>();
        public final List<String> components = new ArrayList<>();
    }

    public static final class SigningSummary {
        public boolean verified;
        public boolean v1Verified;
        public boolean v2Verified;
        public boolean v3Verified;
        public final List<String> signerSubjects = new ArrayList<>();
        public final List<String> signerSha256s = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    public static final class ApkReport {
        public final String fileName;
        public final long fileSizeBytes;
        public final int entryCount;
        public final long totalUncompressedBytes;
        public final Map<String, CategoryStats> breakdown;
        public final List<ApkEntryInfo> topEntries;
        public final List<DexSummary> dexFiles;
        public final int totalDexClasses;
        public final int totalDexMethods;
        public final int totalDexFields;
        public final int totalDexStrings;
        public final BinaryManifestSummary manifest;
        public final SigningSummary signing;

        public ApkReport(
                String fileName,
                long fileSizeBytes,
                int entryCount,
                long totalUncompressedBytes,
                Map<String, CategoryStats> breakdown,
                List<ApkEntryInfo> topEntries,
                List<DexSummary> dexFiles,
                int totalDexClasses,
                int totalDexMethods,
                int totalDexFields,
                int totalDexStrings,
                BinaryManifestSummary manifest,
                SigningSummary signing) {
            this.fileName = fileName;
            this.fileSizeBytes = fileSizeBytes;
            this.entryCount = entryCount;
            this.totalUncompressedBytes = totalUncompressedBytes;
            this.breakdown = breakdown;
            this.topEntries = topEntries;
            this.dexFiles = dexFiles;
            this.totalDexClasses = totalDexClasses;
            this.totalDexMethods = totalDexMethods;
            this.totalDexFields = totalDexFields;
            this.totalDexStrings = totalDexStrings;
            this.manifest = manifest;
            this.signing = signing;
        }
    }

    public static ApkReport analyze(File apkFile) throws IOException {
        if (apkFile == null || !apkFile.exists() || !apkFile.isFile()) {
            throw new IOException("APK file not found");
        }

        Map<String, CategoryStats> breakdown = new LinkedHashMap<>();
        String[] cats = {"DEX", "Resources", "Native Libs", "Assets", "Manifest", "Bundle Config", "Bundle Meta", "Signatures", "Other"};
        for (String c : cats) {
            breakdown.put(c, new CategoryStats(c));
        }

        List<ApkEntryInfo> allEntries = new ArrayList<>();
        List<DexSummary> dexFiles = new ArrayList<>();
        BinaryManifestSummary manifestSummary = new BinaryManifestSummary();
        long totalUncompressed = 0L;
        int totalClasses = 0;
        int totalMethods = 0;
        int totalFields = 0;
        int totalStrings = 0;

        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                long comp = Math.max(0L, entry.getCompressedSize());
                long uncomp = Math.max(0L, entry.getSize());
                String category = classifyEntry(name);

                CategoryStats stats = breakdown.get(category);
                if (stats != null) {
                    stats.fileCount++;
                    stats.compressedBytes += comp;
                    stats.uncompressedBytes += uncomp;
                }
                totalUncompressed += uncomp;
                allEntries.add(new ApkEntryInfo(name, category, comp, uncomp));

                if (name.matches("^(?:base/dex/)?classes\\d*\\.dex$")) {
                    byte[] header = readEntryHeader(zip, entry, 112);
                    DexSummary dex = parseDexHeader(name, header);
                    if (dex != null) {
                        dexFiles.add(dex);
                        totalClasses += dex.classDefCount;
                        totalMethods += dex.methodCount;
                        totalFields += dex.fieldCount;
                        totalStrings += dex.stringCount;
                    }
                } else if ("AndroidManifest.xml".equals(name)) {
                    byte[] rawManifest = readAllEntryBytes(zip, entry);
                    manifestSummary = parseBinaryManifest(rawManifest);
                } else if ("base/manifest/AndroidManifest.xml".equals(name) && manifestSummary.packageName.isEmpty()) {
                    manifestSummary.packageName = "(Proto AndroidManifest.xml in AAB)";
                }
            }
        }

        Collections.sort(dexFiles, Comparator.comparing(d -> d.entryName));
        allEntries.sort((a, b) -> Long.compare(b.compressedSize, a.compressedSize));
        List<ApkEntryInfo> topEntries = allEntries.size() > 25
                ? new ArrayList<>(allEntries.subList(0, 25))
                : allEntries;

        boolean isAab = apkFile.getName().toLowerCase(Locale.ROOT).endsWith(".aab");
        SigningSummary signing = isAab ? verifyJarV1Signatures(apkFile) : verifySignatures(apkFile);

        return new ApkReport(
                apkFile.getName(),
                apkFile.length(),
                allEntries.size(),
                totalUncompressed,
                breakdown,
                topEntries,
                dexFiles,
                totalClasses,
                totalMethods,
                totalFields,
                totalStrings,
                manifestSummary,
                signing);
    }

    public static String classifyEntry(String name) {
        if (name == null) return "Other";
        if (name.equals("AndroidManifest.xml") || name.equals("base/manifest/AndroidManifest.xml")) return "Manifest";
        if (name.equals("BundleConfig.pb")) return "Bundle Config";
        if (name.startsWith("BUNDLE-METADATA/")) return "Bundle Meta";
        if (name.matches("^(?:base/dex/)?classes\\d*\\.dex$")) return "DEX";
        if ((name.startsWith("lib/") || name.startsWith("base/lib/")) && name.endsWith(".so")) return "Native Libs";
        if (name.startsWith("assets/") || name.startsWith("base/assets/")) return "Assets";
        if (name.startsWith("META-INF/")) return "Signatures";
        if (name.startsWith("res/") || name.startsWith("base/res/")
                || name.equals("resources.arsc") || name.equals("base/resources.pb")) return "Resources";
        return "Other";
    }

    public static DexSummary parseDexHeader(String entryName, byte[] bytes) {
        if (bytes == null || bytes.length < 104) return null;
        // Standard DEX magic: "dex\n035\0".."dex\n040\0"
        if (bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n') {
            return null;
        }
        String version = new String(bytes, 4, 3, StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int fileSize = buf.getInt(32);
        int stringIdsSize = buf.getInt(56);
        int typeIdsSize = buf.getInt(64);
        int fieldIdsSize = buf.getInt(80);
        int methodIdsSize = buf.getInt(88);
        int classDefsSize = buf.getInt(96);
        return new DexSummary(entryName, version, fileSize, stringIdsSize, typeIdsSize,
                fieldIdsSize, methodIdsSize, classDefsSize);
    }

    /**
     * Decodes compiled Android Binary XML ({@code AXML}) chunks from {@code AndroidManifest.xml},
     * with a fallback for plain-text XML manifests in uncompiled test archives.
     */
    public static BinaryManifestSummary parseBinaryManifest(byte[] bytes) {
        BinaryManifestSummary summary = new BinaryManifestSummary();
        if (bytes == null || bytes.length < 8) return summary;

        // Check plain-text XML first
        String head = new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.UTF_8).trim();
        if (head.startsWith("<")) {
            com.example.sandrix.xml.XmlPatcher.ManifestModel model =
                    com.example.sandrix.xml.XmlPatcher.parseManifestModel(new String(bytes, StandardCharsets.UTF_8));
            summary.packageName = model.packageName;
            summary.minSdkVersion = model.minSdk;
            summary.targetSdkVersion = model.targetSdk;
            summary.applicationLabel = model.appLabel;
            summary.permissions.addAll(model.permissions);
            summary.componentCount = model.components.size();
            for (com.example.sandrix.xml.XmlPatcher.ManifestComponent c : model.components) {
                if (c.exported) summary.exportedComponentCount++;
                summary.components.add("<" + c.tag + "> " + c.name + (c.exported ? " (exported)" : ""));
            }
            return summary;
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buf.getShort(0) & 0xFFFF;
        if (fileType != 0x0003) {
            return summary;
        }

        List<String> stringPool = Collections.emptyList();
        int pos = buf.getShort(2) & 0xFFFF; // root XML header size (usually 8)
        int limit = Math.min(bytes.length, buf.getInt(4));

        while (pos + 8 <= limit) {
            int chunkType = buf.getShort(pos) & 0xFFFF;
            int headerSize = buf.getShort(pos + 2) & 0xFFFF;
            int chunkSize = buf.getInt(pos + 4);
            if (chunkSize < 8 || pos + chunkSize > limit) break;

            if (chunkType == 0x0001) { // RES_STRING_POOL_TYPE
                stringPool = parseStringPool(bytes, pos, chunkSize);
            } else if (chunkType == 0x0102 && pos + 36 <= pos + chunkSize) { // RES_XML_START_ELEMENT_TYPE
                int nameIdx = buf.getInt(pos + 20);
                String tagName = stringAt(stringPool, nameIdx);
                int attrStart = buf.getShort(pos + 24) & 0xFFFF;
                int attrSize = buf.getShort(pos + 26) & 0xFFFF;
                int attrCount = buf.getShort(pos + 28) & 0xFFFF;
                if (attrSize <= 0) attrSize = 20;

                int attrOffset = pos + 16 + attrStart;
                Map<String, String> attrs = new LinkedHashMap<>();
                for (int i = 0; i < attrCount && attrOffset + 20 <= pos + chunkSize; i++) {
                    int attrNameIdx = buf.getInt(attrOffset + 4);
                    int rawValueIdx = buf.getInt(attrOffset + 8);
                    int valueType = bytes[attrOffset + 15] & 0xFF;
                    int valueData = buf.getInt(attrOffset + 16);

                    String attrName = stringAt(stringPool, attrNameIdx);
                    String attrVal = decodeAttrValue(stringPool, rawValueIdx, valueType, valueData);
                    if (!attrName.isEmpty()) {
                        attrs.put(attrName, attrVal);
                    }
                    attrOffset += attrSize;
                }

                if ("manifest".equals(tagName)) {
                    if (attrs.containsKey("package")) summary.packageName = attrs.get("package");
                    if (attrs.containsKey("versionCode")) summary.versionCode = attrs.get("versionCode");
                    if (attrs.containsKey("versionName")) summary.versionName = attrs.get("versionName");
                } else if ("uses-sdk".equals(tagName)) {
                    if (attrs.containsKey("minSdkVersion")) summary.minSdkVersion = attrs.get("minSdkVersion");
                    if (attrs.containsKey("targetSdkVersion")) summary.targetSdkVersion = attrs.get("targetSdkVersion");
                } else if ("uses-permission".equals(tagName) || "uses-permission-sdk-23".equals(tagName)) {
                    String perm = attrs.get("name");
                    if (perm != null && !perm.isEmpty() && !summary.permissions.contains(perm)) {
                        summary.permissions.add(perm);
                    }
                } else if ("application".equals(tagName)) {
                    if (attrs.containsKey("label")) summary.applicationLabel = attrs.get("label");
                    if ("true".equalsIgnoreCase(attrs.get("debuggable"))) summary.debuggable = true;
                } else if ("activity".equals(tagName) || "service".equals(tagName)
                        || "receiver".equals(tagName) || "provider".equals(tagName)) {
                    summary.componentCount++;
                    String compName = attrs.get("name");
                    boolean exp = "true".equalsIgnoreCase(attrs.get("exported"));
                    if (exp) summary.exportedComponentCount++;
                    summary.components.add("<" + tagName + "> " + (compName != null ? compName : "")
                            + (exp ? " (exported)" : ""));
                }
            }
            pos += chunkSize;
        }
        return summary;
    }

    private static List<String> parseStringPool(byte[] bytes, int chunkStart, int chunkSize) {
        if (chunkSize < 28) return Collections.emptyList();
        ByteBuffer buf = ByteBuffer.wrap(bytes, chunkStart, chunkSize).slice().order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = buf.getShort(2) & 0xFFFF;
        int stringCount = buf.getInt(8);
        int flags = buf.getInt(16);
        int stringsStart = buf.getInt(20);
        boolean isUtf8 = (flags & 0x00000100) != 0;

        if (stringCount <= 0 || stringCount > 100_000) return Collections.emptyList();
        List<String> list = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            int offsetPos = headerSize + i * 4;
            if (offsetPos + 4 > chunkSize) break;
            int relOffset = buf.getInt(offsetPos);
            int strPos = chunkStart + stringsStart + relOffset;
            if (strPos < chunkStart || strPos >= chunkStart + chunkSize) {
                list.add("");
                continue;
            }
            list.add(readPoolString(bytes, strPos, chunkStart + chunkSize, isUtf8));
        }
        return list;
    }

    private static String readPoolString(byte[] bytes, int pos, int limit, boolean isUtf8) {
        if (isUtf8) {
            int idx = pos;
            // Skip char length (1 or 2 bytes)
            if (idx >= limit) return "";
            if ((bytes[idx] & 0x80) != 0) idx += 2;
            else idx += 1;
            if (idx >= limit) return "";
            // Read UTF-8 byte length (1 or 2 bytes)
            int byteLen;
            if ((bytes[idx] & 0x80) != 0) {
                if (idx + 1 >= limit) return "";
                byteLen = ((bytes[idx] & 0x7F) << 8) | (bytes[idx + 1] & 0xFF);
                idx += 2;
            } else {
                byteLen = bytes[idx] & 0x7F;
                idx += 1;
            }
            int safeLen = Math.min(byteLen, Math.max(0, limit - idx));
            return new String(bytes, idx, safeLen, StandardCharsets.UTF_8);
        } else {
            if (pos + 2 > limit) return "";
            ByteBuffer le = ByteBuffer.wrap(bytes, pos, Math.min(4, limit - pos)).order(ByteOrder.LITTLE_ENDIAN);
            int charLen = le.getShort(0) & 0xFFFF;
            int idx = pos + 2;
            if ((charLen & 0x8000) != 0) {
                if (pos + 4 > limit) return "";
                charLen = ((charLen & 0x7FFF) << 16) | (le.getShort(2) & 0xFFFF);
                idx = pos + 4;
            }
            int byteLen = Math.min(charLen * 2, Math.max(0, limit - idx));
            return new String(bytes, idx, byteLen, StandardCharsets.UTF_16LE);
        }
    }

    private static String stringAt(List<String> pool, int idx) {
        return (idx >= 0 && idx < pool.size()) ? pool.get(idx) : "";
    }

    private static String decodeAttrValue(List<String> pool, int rawValueIdx, int valueType, int data) {
        if (valueType == 0x03) { // TYPE_STRING
            return stringAt(pool, data);
        }
        if (rawValueIdx >= 0 && rawValueIdx < pool.size()) {
            String raw = pool.get(rawValueIdx);
            if (!raw.isEmpty()) return raw;
        }
        if (valueType == 0x12) { // TYPE_INT_BOOLEAN
            return data != 0 ? "true" : "false";
        }
        if (valueType == 0x10 || valueType == 0x11) { // TYPE_INT_DEC / HEX
            return String.valueOf(data);
        }
        if (valueType == 0x01) { // TYPE_REFERENCE
            return String.format(Locale.US, "@0x%08x", data);
        }
        return String.valueOf(data);
    }

    private static SigningSummary verifySignatures(File apkFile) {
        SigningSummary summary = new SigningSummary();
        try {
            ApkVerifier verifier = new ApkVerifier.Builder(apkFile).build();
            ApkVerifier.Result result = verifier.verify();
            summary.verified = result.isVerified();
            summary.v1Verified = result.isVerifiedUsingV1Scheme();
            summary.v2Verified = result.isVerifiedUsingV2Scheme();
            summary.v3Verified = result.isVerifiedUsingV3Scheme();
            for (X509Certificate cert : result.getSignerCertificates()) {
                summary.signerSubjects.add(cert.getSubjectX500Principal().getName());
                summary.signerSha256s.add(sha256Hex(cert.getEncoded()));
            }
            for (ApkVerifier.IssueWithParams err : result.getErrors()) {
                summary.errors.add(err.toString());
            }
            for (ApkVerifier.IssueWithParams warn : result.getWarnings()) {
                summary.warnings.add(warn.toString());
            }
            for (ApkVerifier.Result.V1SchemeSignerInfo s1 : result.getV1SchemeSigners()) {
                for (ApkVerifier.IssueWithParams err : s1.getErrors()) summary.errors.add("v1(" + s1.getName() + "): " + err);
                for (ApkVerifier.IssueWithParams warn : s1.getWarnings()) summary.warnings.add("v1(" + s1.getName() + "): " + warn);
            }
            for (ApkVerifier.Result.V2SchemeSignerInfo s2 : result.getV2SchemeSigners()) {
                for (ApkVerifier.IssueWithParams err : s2.getErrors()) summary.errors.add("v2: " + err);
                for (ApkVerifier.IssueWithParams warn : s2.getWarnings()) summary.warnings.add("v2: " + warn);
            }
            for (ApkVerifier.Result.V3SchemeSignerInfo s3 : result.getV3SchemeSigners()) {
                for (ApkVerifier.IssueWithParams err : s3.getErrors()) summary.errors.add("v3: " + err);
                for (ApkVerifier.IssueWithParams warn : s3.getWarnings()) summary.warnings.add("v3: " + warn);
            }
        } catch (Throwable t) {
            summary.verified = false;
            summary.errors.add(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        return summary;
    }

    private static SigningSummary verifyJarV1Signatures(File aabFile) {
        SigningSummary summary = new SigningSummary();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(aabFile, true)) {
            Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            byte[] buf = new byte[4096];
            Set<X509Certificate> seenCerts = new java.util.LinkedHashSet<>();
            int signedEntries = 0;
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry je = entries.nextElement();
                if (je.isDirectory() || je.getName().toUpperCase(Locale.ROOT).startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream is = jar.getInputStream(je)) {
                    while (is.read(buf) != -1) {
                        // consume stream to trigger digest + signature verification
                    }
                }
                java.security.CodeSigner[] signers = je.getCodeSigners();
                if (signers != null && signers.length > 0) {
                    signedEntries++;
                    for (java.security.CodeSigner cs : signers) {
                        if (cs.getSignerCertPath() != null && !cs.getSignerCertPath().getCertificates().isEmpty()) {
                            java.security.cert.Certificate c = cs.getSignerCertPath().getCertificates().get(0);
                            if (c instanceof X509Certificate) {
                                seenCerts.add((X509Certificate) c);
                            }
                        }
                    }
                }
            }
            summary.v1Verified = signedEntries > 0 && !seenCerts.isEmpty();
            summary.verified = summary.v1Verified;
            for (X509Certificate cert : seenCerts) {
                summary.signerSubjects.add(cert.getSubjectX500Principal().getName());
                summary.signerSha256s.add(sha256Hex(cert.getEncoded()));
            }
            if (!summary.verified) {
                summary.errors.add("No verified JAR v1 signers found on AAB entries.");
            }
        } catch (Throwable t) {
            summary.verified = false;
            summary.errors.add(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        return summary;
    }

    private static String sha256Hex(byte[] encoded) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readEntryHeader(ZipFile zip, ZipEntry entry, int maxBytes) throws IOException {
        try (InputStream is = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[Math.min(1024, maxBytes)];
            int total = 0;
            while (total < maxBytes) {
                int n = is.read(buf, 0, Math.min(buf.length, maxBytes - total));
                if (n == -1) break;
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }

    private static byte[] readAllEntryBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
