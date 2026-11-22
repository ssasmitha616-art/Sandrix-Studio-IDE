package com.example.sandrix.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java Android App Bundle ({@code .aab}) packager and JAR v1 CMS signer.
 *
 * <p>Produces a standards-compliant {@code .aab} archive containing:
 * <ul>
 *     <li>{@code BundleConfig.pb} (protobuf-encoded {@code com.android.bundle.BundleConfig})</li>
 *     <li>{@code base/manifest/AndroidManifest.xml} (binary proto XML from {@code aapt2 link --proto-format})</li>
 *     <li>{@code base/resources.pb} (resource table proto)</li>
 *     <li>{@code base/res/**} &amp; {@code base/assets/**}</li>
 *     <li>{@code base/dex/classes*.dex}</li>
 *     <li>{@code base/lib/<abi>/*.so}</li>
 *     <li>{@code base/root/**} (Java library non-class resources)</li>
 *     <li>{@code BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map} (when R8 mapping exists)</li>
 *     <li>JAR v1 signature ({@code META-INF/MANIFEST.MF}, {@code .SF}, {@code .RSA})</li>
 * </ul>
 */
public final class AabBuilder {

    public static final String BUNDLETOOL_VERSION = "1.18.3";
    public static final String METADATA_PROGUARD_MAP =
            "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map";

    private AabBuilder() {}

    public static void buildUnsignedAab(
            File protoApk,
            File dexDir,
            List<File> jarLibs,
            List<File> jniDirs,
            File proguardMappingFile,
            File outputUnsignedAab) throws IOException {
        if (protoApk == null || !protoApk.isFile()) {
            throw new IOException("Proto resource APK not found for AAB assembly.");
        }
        File parent = outputUnsignedAab.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory for AAB: " + parent.getAbsolutePath());
        }

        byte[] bundleConfigBytes = encodeDefaultBundleConfig();
        byte[] buffer = new byte[8192];
        Set<String> seenEntries = new HashSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputUnsignedAab))) {
            // 1. BundleConfig.pb at archive root
            writeRawEntry(zos, "BundleConfig.pb", bundleConfigBytes, seenEntries);

            // 2. Proto APK contents mapped into base/* module layout
            try (ZipFile protoZip = new ZipFile(protoApk)) {
                Enumeration<? extends ZipEntry> entries = protoZip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String orig = entry.getName();
                    String mapped = mapProtoApkEntryToBase(orig);
                    if (mapped == null || !seenEntries.add(mapped)) {
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(mapped));
                    try (InputStream is = protoZip.getInputStream(entry)) {
                        int n;
                        while ((n = is.read(buffer)) != -1) {
                            zos.write(buffer, 0, n);
                        }
                    }
                    zos.closeEntry();
                }
            }

            // 3. DEX files -> base/dex/classes*.dex
            if (dexDir != null && dexDir.isDirectory()) {
                File[] dexFiles = dexDir.listFiles((dir, name) -> name.endsWith(".dex"));
                if (dexFiles != null) {
                    Arrays.sort(dexFiles);
                    for (File dex : dexFiles) {
                        String dest = "base/dex/" + dex.getName();
                        if (!seenEntries.add(dest)) continue;
                        copyFileEntry(zos, dest, dex, buffer);
                    }
                }
            }

            // 4. Native JNI libraries -> base/lib/<abi>/*.so
            if (jniDirs != null) {
                for (File jniRoot : jniDirs) {
                    if (jniRoot == null || !jniRoot.isDirectory()) continue;
                    List<File> soFiles = collectFiles(jniRoot);
                    Collections.sort(soFiles);
                    String prefix = jniRoot.getAbsolutePath();
                    if (!prefix.endsWith(File.separator)) prefix += File.separator;
                    for (File so : soFiles) {
                        String rel = so.getAbsolutePath().substring(prefix.length()).replace('\\', '/');
                        String dest = "base/lib/" + rel;
                        if (!seenEntries.add(dest)) continue;
                        copyFileEntry(zos, dest, so, buffer);
                    }
                }
            }

            // 5. Non-class Java library resources -> base/root/*
            if (jarLibs != null) {
                for (File jar : jarLibs) {
                    if (jar == null || !jar.isFile()) continue;
                    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jar))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            String name = entry.getName();
                            if (!entry.isDirectory() && !shouldSkipLibraryEntry(name)) {
                                String dest = "base/root/" + name;
                                if (seenEntries.add(dest)) {
                                    zos.putNextEntry(new ZipEntry(dest));
                                    int n;
                                    while ((n = zis.read(buffer)) != -1) {
                                        zos.write(buffer, 0, n);
                                    }
                                    zos.closeEntry();
                                }
                            }
                            zis.closeEntry();
                        }
                    } catch (IOException ignored) {
                    }
                }
            }

            // 6. R8 ProGuard mapping -> BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map
            if (proguardMappingFile != null && proguardMappingFile.isFile() && proguardMappingFile.length() > 0) {
                if (seenEntries.add(METADATA_PROGUARD_MAP)) {
                    copyFileEntry(zos, METADATA_PROGUARD_MAP, proguardMappingFile, buffer);
                }
            }
        }
    }

    /**
     * Signs an {@code unsignedAab} archive with a standard JAR v1 signature
     * ({@code META-INF/MANIFEST.MF}, {@code META-INF/<ALIAS>.SF}, {@code META-INF/<ALIAS>.RSA}).
     */
    public static void signAabJarV1(
            File unsignedAab,
            File signedAab,
            String keyAlias,
            PrivateKey privateKey,
            X509Certificate certificate) throws Exception {
        if (unsignedAab == null || !unsignedAab.isFile()) {
            throw new IOException("Unsigned AAB not found.");
        }
        if (privateKey == null || certificate == null) {
            throw new IOException("Missing signing private key or X.509 certificate.");
        }

        String sfBaseName = sanitizeAliasForMetaInf(keyAlias);
        Map<String, byte[]> entryBytes = new LinkedHashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(unsignedAab))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && !isSignatureMetaInf(name)) {
                    entryBytes.put(name, readStream(zis));
                }
                zis.closeEntry();
            }
        }

        List<String> sortedNames = new ArrayList<>(entryBytes.keySet());
        Collections.sort(sortedNames);

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        // 1. Build MANIFEST.MF
        ByteArrayOutputStream mfMainOut = new ByteArrayOutputStream();
        writeAscii(mfMainOut, "Manifest-Version: 1.0\r\n");
        writeAscii(mfMainOut, "Created-By: Sandrix Studio IDE\r\n\r\n");
        byte[] mainAttributesBlock = mfMainOut.toByteArray();

        ByteArrayOutputStream mfOut = new ByteArrayOutputStream();
        mfOut.write(mainAttributesBlock);

        Map<String, String> entrySectionDigests = new LinkedHashMap<>();
        for (String name : sortedNames) {
            byte[] data = entryBytes.get(name);
            String fileDigestB64 = Base64.getEncoder().encodeToString(sha256.digest(data));

            ByteArrayOutputStream sectionOut = new ByteArrayOutputStream();
            writeWrappedHeader(sectionOut, "Name", name);
            writeWrappedHeader(sectionOut, "SHA-256-Digest", fileDigestB64);
            sectionOut.write('\r');
            sectionOut.write('\n');
            byte[] sectionBytes = sectionOut.toByteArray();
            mfOut.write(sectionBytes);

            String sectionDigestB64 = Base64.getEncoder().encodeToString(sha256.digest(sectionBytes));
            entrySectionDigests.put(name, sectionDigestB64);
        }

        byte[] manifestBytes = mfOut.toByteArray();
        String manifestDigestB64 = Base64.getEncoder().encodeToString(sha256.digest(manifestBytes));
        String mainAttrsDigestB64 = Base64.getEncoder().encodeToString(sha256.digest(mainAttributesBlock));

        // 2. Build <ALIAS>.SF
        ByteArrayOutputStream sfOut = new ByteArrayOutputStream();
        writeAscii(sfOut, "Signature-Version: 1.0\r\n");
        writeAscii(sfOut, "Created-By: Sandrix Studio IDE\r\n");
        writeWrappedHeader(sfOut, "SHA-256-Digest-Manifest", manifestDigestB64);
        writeWrappedHeader(sfOut, "SHA-256-Digest-Manifest-Main-Attributes", mainAttrsDigestB64);
        writeAscii(sfOut, "\r\n");

        for (String name : sortedNames) {
            writeWrappedHeader(sfOut, "Name", name);
            writeWrappedHeader(sfOut, "SHA-256-Digest", entrySectionDigests.get(name));
            writeAscii(sfOut, "\r\n");
        }
        byte[] sfBytes = sfOut.toByteArray();

        // 3. Build PKCS#7 / CMS SignedData signature block (.RSA/.EC/.DSA)
        String keyAlg = privateKey.getAlgorithm() != null ? privateKey.getAlgorithm().toUpperCase(Locale.ROOT) : "RSA";
        String sigAlg = resolveJarSigAlgorithm(keyAlg);
        String blockExt = "RSA";
        if ("EC".equals(keyAlg) || "ECDSA".equals(keyAlg)) {
            blockExt = "EC";
        } else if ("DSA".equals(keyAlg)) {
            blockExt = "DSA";
        }

        Signature sig = Signature.getInstance(sigAlg);
        sig.initSign(privateKey);
        sig.update(sfBytes);
        byte[] signatureBytes = sig.sign();
        byte[] pkcs7Bytes = buildPkcs7SignedData(certificate, signatureBytes, keyAlg);

        // 4. Write final signed .aab
        if (signedAab.exists() && !signedAab.delete()) {
            throw new IOException("Cannot overwrite signed AAB: " + signedAab.getAbsolutePath());
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(signedAab))) {
            putBytesEntry(zos, "META-INF/MANIFEST.MF", manifestBytes);
            putBytesEntry(zos, "META-INF/" + sfBaseName + ".SF", sfBytes);
            putBytesEntry(zos, "META-INF/" + sfBaseName + "." + blockExt, pkcs7Bytes);
            for (String name : sortedNames) {
                putBytesEntry(zos, name, entryBytes.get(name));
            }
        }
    }

    /**
     * Encodes {@code BundleConfig.pb} conforming to {@code com.android.bundle.BundleConfig}:
     * <ul>
     *     <li>{@code bundletool.version = "1.18.3"}</li>
     *     <li>{@code optimizations.splits_config.split_dimension = [ABI, SCREEN_DENSITY, LANGUAGE]}</li>
     *     <li>{@code type = REGULAR (0)}</li>
     * </ul>
     */
    public static byte[] encodeDefaultBundleConfig() throws IOException {
        // Bundletool submessage (field 2: string version)
        ByteArrayOutputStream bundletoolMsg = new ByteArrayOutputStream();
        writeProtoString(bundletoolMsg, 2, BUNDLETOOL_VERSION);

        // SplitDimension repeated submessages (field 1: enum value, field 2: bool negate)
        ByteArrayOutputStream splitsConfigMsg = new ByteArrayOutputStream();
        writeProtoMessage(splitsConfigMsg, 1, encodeSplitDimension(1)); // ABI
        writeProtoMessage(splitsConfigMsg, 1, encodeSplitDimension(2)); // SCREEN_DENSITY
        writeProtoMessage(splitsConfigMsg, 1, encodeSplitDimension(3)); // LANGUAGE

        // Optimizations submessage (field 1: splits_config)
        ByteArrayOutputStream optimizationsMsg = new ByteArrayOutputStream();
        writeProtoMessage(optimizationsMsg, 1, splitsConfigMsg.toByteArray());

        // Root BundleConfig
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        writeProtoMessage(root, 1, bundletoolMsg.toByteArray());
        writeProtoMessage(root, 2, optimizationsMsg.toByteArray());
        return root.toByteArray();
    }

    private static byte[] encodeSplitDimension(int value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeProtoVarint(out, 1, value);
        return out.toByteArray();
    }

    private static void writeProtoString(ByteArrayOutputStream out, int fieldNumber, String value) throws IOException {
        writeProtoMessage(out, fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeProtoMessage(ByteArrayOutputStream out, int fieldNumber, byte[] payload) throws IOException {
        writeRawVarint(out, (fieldNumber << 3) | 2);
        writeRawVarint(out, payload.length);
        out.write(payload);
    }

    private static void writeProtoVarint(ByteArrayOutputStream out, int fieldNumber, int value) {
        writeRawVarint(out, (fieldNumber << 3));
        writeRawVarint(out, value);
    }

    private static void writeRawVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static String mapProtoApkEntryToBase(String name) {
        if (name == null || name.isEmpty()) return null;
        if ("AndroidManifest.xml".equals(name)) {
            return "base/manifest/AndroidManifest.xml";
        }
        if ("resources.pb".equals(name)) {
            return "base/resources.pb";
        }
        if (name.startsWith("res/") || name.startsWith("assets/")) {
            return "base/" + name;
        }
        if (isSignatureMetaInf(name) || name.endsWith(".dex") || "resources.arsc".equals(name)) {
            return null;
        }
        return "base/root/" + name;
    }

    private static boolean shouldSkipLibraryEntry(String name) {
        if (name == null || name.isEmpty() || name.endsWith(".class") || name.endsWith(".dex")) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF")
                || upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.endsWith(".KOTLIN_MODULE"));
    }

    private static boolean isSignatureMetaInf(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("META-INF/MANIFEST.MF")
                || (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC")));
    }

    private static void writeRawEntry(
            ZipOutputStream zos,
            String entryName,
            byte[] data,
            Set<String> seen) throws IOException {
        if (!seen.add(entryName)) return;
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(data);
        zos.closeEntry();
    }

    private static void putBytesEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void copyFileEntry(
            ZipOutputStream zos,
            String entryName,
            File file,
            byte[] buffer) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buffer)) != -1) {
                zos.write(buffer, 0, n);
            }
        }
        zos.closeEntry();
    }

    private static List<File> collectFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return out;
        for (File c : children) {
            if (c.isDirectory()) {
                out.addAll(collectFiles(c));
            } else if (c.isFile()) {
                out.add(c);
            }
        }
        return out;
    }

    private static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String sanitizeAliasForMetaInf(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            return "SANDRIX";
        }
        String clean = alias.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
        if (clean.length() > 8) {
            clean = clean.substring(0, 8);
        }
        return clean.isEmpty() ? "SANDRIX" : clean;
    }

    private static String resolveJarSigAlgorithm(String keyAlg) {
        if ("EC".equals(keyAlg) || "ECDSA".equals(keyAlg)) return "SHA256withECDSA";
        if ("DSA".equals(keyAlg)) return "SHA256withDSA";
        return "SHA256withRSA";
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeWrappedHeader(ByteArrayOutputStream out, String key, String value) throws IOException {
        byte[] line = (key + ": " + value).getBytes(StandardCharsets.UTF_8);
        if (line.length <= 70) {
            out.write(line);
            out.write('\r');
            out.write('\n');
            return;
        }
        int offset = 0;
        int firstChunk = Math.min(70, line.length);
        out.write(line, 0, firstChunk);
        out.write('\r');
        out.write('\n');
        offset += firstChunk;
        while (offset < line.length) {
            out.write(' ');
            int nextChunk = Math.min(69, line.length - offset);
            out.write(line, offset, nextChunk);
            out.write('\r');
            out.write('\n');
            offset += nextChunk;
        }
    }

    /**
     * Minimal DER encoder producing RFC 5652 / PKCS#7 {@code ContentInfo(SignedData)}
     * compatible with {@link java.util.jar.JarFile} and {@code jarsigner -verify}.
     */
    private static byte[] buildPkcs7SignedData(
            X509Certificate cert,
            byte[] signatureBytes,
            String keyAlg) throws Exception {
        byte[] signedDataOid = derOid("1.2.840.113549.1.7.2");
        byte[] dataOid = derOid("1.2.840.113549.1.7.1");
        byte[] sha256Oid = derOid("2.16.840.1.101.3.4.2.1");

        String sigAlgOidStr;
        if ("EC".equals(keyAlg) || "ECDSA".equals(keyAlg)) {
            sigAlgOidStr = "1.2.840.10045.4.3.2"; // ecdsa-with-SHA256
        } else if ("DSA".equals(keyAlg)) {
            sigAlgOidStr = "2.16.840.1.101.3.4.3.2"; // dsa-with-sha256
        } else {
            sigAlgOidStr = "1.2.840.113549.1.1.11"; // sha256WithRSAEncryption
        }

        byte[] digestAlgId = derSequence(concat(sha256Oid, derNull()));
        byte[] digestAlgsSet = derSet(digestAlgId);
        byte[] encapContentInfo = derSequence(dataOid);
        byte[] certsExplicit0 = derExplicitTag(0, cert.getEncoded());

        byte[] issuerBytes = cert.getIssuerX500Principal().getEncoded();
        byte[] serialBytes = derInteger(cert.getSerialNumber());
        byte[] issuerAndSerial = derSequence(concat(issuerBytes, serialBytes));

        byte[] sigAlgId = ("EC".equals(keyAlg) || "ECDSA".equals(keyAlg) || "DSA".equals(keyAlg))
                ? derSequence(derOid(sigAlgOidStr))
                : derSequence(concat(derOid(sigAlgOidStr), derNull()));

        byte[] signerInfo = derSequence(concat(
                derInteger(BigInteger.ONE),
                issuerAndSerial,
                digestAlgId,
                sigAlgId,
                derOctetString(signatureBytes)
        ));
        byte[] signerInfosSet = derSet(signerInfo);

        byte[] signedData = derSequence(concat(
                derInteger(BigInteger.ONE),
                digestAlgsSet,
                encapContentInfo,
                certsExplicit0,
                signerInfosSet
        ));

        return derSequence(concat(
                signedDataOid,
                derExplicitTag(0, signedData)
        ));
    }

    private static byte[] derSequence(byte[] content) throws IOException {
        return derTag(0x30, content);
    }

    private static byte[] derSet(byte[] content) throws IOException {
        return derTag(0x31, content);
    }

    private static byte[] derExplicitTag(int tagNumber, byte[] content) throws IOException {
        return derTag(0xA0 | (tagNumber & 0x1F), content);
    }

    private static byte[] derOctetString(byte[] content) throws IOException {
        return derTag(0x04, content);
    }

    private static byte[] derInteger(BigInteger value) throws IOException {
        return derTag(0x02, value.toByteArray());
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    private static byte[] derOid(String oid) throws IOException {
        String[] parts = oid.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        out.write(first * 40 + second);
        for (int i = 2; i < parts.length; i++) {
            writeBase128(out, Integer.parseInt(parts[i]));
        }
        return derTag(0x06, out.toByteArray());
    }

    private static void writeBase128(ByteArrayOutputStream out, int val) {
        if (val == 0) {
            out.write(0);
            return;
        }
        byte[] tmp = new byte[5];
        int idx = 4;
        tmp[idx--] = (byte) (val & 0x7F);
        val >>>= 7;
        while (val > 0) {
            tmp[idx--] = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
        }
        out.write(tmp, idx + 1, 4 - idx);
    }

    private static byte[] derTag(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int numBytes = 0;
            int tmp = len;
            while (tmp > 0) {
                numBytes++;
                tmp >>>= 8;
            }
            out.write(0x80 | numBytes);
            for (int i = numBytes - 1; i >= 0; i--) {
                out.write((len >>> (8 * i)) & 0xFF);
            }
        }
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            if (a != null) out.write(a);
        }
        return out.toByteArray();
    }
}
