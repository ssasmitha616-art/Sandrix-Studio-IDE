package com.example.sandrix.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java JVM classfile bytecode sanitizer for R8 / D8 4.0.x compatibility.
 *
 * <p>Fixes the R8/D8 {@code NullPointerException: String.length() on null} bug triggered when
 * dexing {@code .class} files compiled with Java 8+ {@code -parameters} metadata (such as
 * {@code androidx.appcompat.app.ActionBarDrawerToggle$1.class}), where a method's
 * {@code MethodParameters} attribute contains entries with {@code name_index == 0}
 * ({@code <no name>}, synthetic/mandated constructor parameters).</p>
 *
 * <p>Also strips incompatible modular/multi-release JAR entries ({@code module-info.class},
 * {@code META-INF/versions/*}) and JAR signature files when producing a sanitized archive.</p>
 */
public final class ClassFileSanitizer {

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    public static final class SanitizeResult {
        public final byte[] bytes;
        public final boolean modified;

        public SanitizeResult(byte[] bytes, boolean modified) {
            this.bytes = bytes;
            this.modified = modified;
        }
    }

    public static final class ArchiveSanitizeStats {
        public final File effectiveFile;
        public final boolean rewritten;
        public final int totalClasses;
        public final int patchedClasses;
        public final int strippedEntries;

        public ArchiveSanitizeStats(
                File effectiveFile,
                boolean rewritten,
                int totalClasses,
                int patchedClasses,
                int strippedEntries) {
            this.effectiveFile = effectiveFile;
            this.rewritten = rewritten;
            this.totalClasses = totalClasses;
            this.patchedClasses = patchedClasses;
            this.strippedEntries = strippedEntries;
        }
    }

    private ClassFileSanitizer() {}

    public static boolean isIgnoredArchiveClass(String entryName) {
        if (entryName == null || entryName.isEmpty()) return true;
        return "module-info.class".equals(entryName)
                || entryName.endsWith("/module-info.class")
                || entryName.startsWith("META-INF/versions/");
    }

    public static boolean isSignatureFile(String entryName) {
        if (entryName == null) return false;
        String upper = entryName.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    /**
     * Inspects and sanitizes JVM {@code .class} bytes if any method has a {@code MethodParameters}
     * attribute referencing {@code name_index == 0} (or malformed length / out-of-range UTF-8 index).
     * If no changes are required, returns the original byte array untouched.
     */
    public static SanitizeResult sanitizeClassBytes(byte[] data) {
        if (data == null || data.length < 16 || readInt(data, 0) != CLASS_MAGIC) {
            return new SanitizeResult(data, false);
        }
        try {
            int pos = 8; // magic(4) + minor(2) + major(2)
            int cpCount = readU2(data, pos);
            pos += 2;
            if (cpCount < 1) {
                return new SanitizeResult(data, false);
            }

            Set<Integer> methodParamsCpIndices = new HashSet<>();
            Set<Integer> validUtf8Indices = new HashSet<>();

            int cpIndex = 1;
            while (cpIndex < cpCount) {
                if (pos >= data.length) {
                    return new SanitizeResult(data, false);
                }
                int tag = data[pos++] & 0xFF;
                switch (tag) {
                    case 1: { // CONSTANT_Utf8
                        if (pos + 2 > data.length) return new SanitizeResult(data, false);
                        int len = readU2(data, pos);
                        pos += 2;
                        if (pos + len > data.length) return new SanitizeResult(data, false);
                        validUtf8Indices.add(cpIndex);
                        if (len == 16 && isMethodParametersUtf8(data, pos)) {
                            methodParamsCpIndices.add(cpIndex);
                        }
                        pos += len;
                        break;
                    }
                    case 3: // Integer
                    case 4: // Float
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        pos += 4;
                        break;
                    case 5: // Long
                    case 6: // Double
                        pos += 8;
                        cpIndex++; // occupies two constant pool entries
                        break;
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        pos += 2;
                        break;
                    case 15: // MethodHandle
                        pos += 3;
                        break;
                    default:
                        // Unknown constant pool tag — leave class bytes untouched
                        return new SanitizeResult(data, false);
                }
                cpIndex++;
            }

            if (methodParamsCpIndices.isEmpty()) {
                return new SanitizeResult(data, false);
            }

            // access_flags(2) + this_class(2) + super_class(2) + interfaces_count(2)
            if (pos + 8 > data.length) {
                return new SanitizeResult(data, false);
            }
            pos += 6;
            int interfacesCount = readU2(data, pos);
            pos += 2 + interfacesCount * 2;
            if (pos + 2 > data.length) {
                return new SanitizeResult(data, false);
            }

            // Skip fields
            int fieldsCount = readU2(data, pos);
            pos += 2;
            for (int f = 0; f < fieldsCount; f++) {
                if (pos + 8 > data.length) return new SanitizeResult(data, false);
                pos += 6; // access_flags, name_index, descriptor_index
                pos = skipAttributes(data, pos);
                if (pos < 0) return new SanitizeResult(data, false);
            }

            // Inspect methods and rewrite attributes when MethodParameters has null names
            if (pos + 2 > data.length) {
                return new SanitizeResult(data, false);
            }
            int methodsCount = readU2(data, pos);
            pos += 2;

            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            out.write(data, 0, pos);
            boolean modified = false;

            for (int m = 0; m < methodsCount; m++) {
                if (pos + 8 > data.length) return new SanitizeResult(data, false);
                out.write(data, pos, 6); // access_flags, name_index, descriptor_index
                pos += 6;

                int attrCount = readU2(data, pos);
                pos += 2;

                List<int[]> keptRanges = new ArrayList<>(attrCount);
                for (int a = 0; a < attrCount; a++) {
                    if (pos + 6 > data.length) return new SanitizeResult(data, false);
                    int attrNameIdx = readU2(data, pos);
                    int attrLen = readInt(data, pos + 2);
                    if (attrLen < 0 || pos + 6 + attrLen > data.length) {
                        return new SanitizeResult(data, false);
                    }
                    int attrEnd = pos + 6 + attrLen;

                    boolean drop = false;
                    if (methodParamsCpIndices.contains(attrNameIdx)) {
                        drop = shouldDropMethodParameters(data, pos + 6, attrLen, validUtf8Indices);
                    }

                    if (drop) {
                        modified = true;
                    } else {
                        keptRanges.add(new int[]{pos, attrEnd});
                    }
                    pos = attrEnd;
                }

                writeU2(out, keptRanges.size());
                for (int[] range : keptRanges) {
                    out.write(data, range[0], range[1] - range[0]);
                }
            }

            if (!modified) {
                return new SanitizeResult(data, false);
            }
            if (pos < data.length) {
                out.write(data, pos, data.length - pos);
            }
            return new SanitizeResult(out.toByteArray(), true);
        } catch (Throwable ignored) {
            return new SanitizeResult(data, false);
        }
    }

    private static boolean shouldDropMethodParameters(
            byte[] data,
            int payloadStart,
            int payloadLen,
            Set<Integer> validUtf8Indices) {
        if (payloadLen < 1) return true;
        int paramCount = data[payloadStart] & 0xFF;
        if (payloadLen < 1 + paramCount * 4) {
            return true;
        }
        for (int p = 0; p < paramCount; p++) {
            int nameIdx = readU2(data, payloadStart + 1 + p * 4);
            if (nameIdx == 0 || !validUtf8Indices.contains(nameIdx)) {
                return true;
            }
        }
        return false;
    }

    private static int skipAttributes(byte[] data, int pos) {
        if (pos + 2 > data.length) return -1;
        int count = readU2(data, pos);
        pos += 2;
        for (int i = 0; i < count; i++) {
            if (pos + 6 > data.length) return -1;
            int len = readInt(data, pos + 2);
            if (len < 0 || pos + 6 + len > data.length) return -1;
            pos += 6 + len;
        }
        return pos;
    }

    private static boolean isMethodParametersUtf8(byte[] data, int offset) {
        byte[] target = "MethodParameters".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < target.length; i++) {
            if (data[offset + i] != target[i]) return false;
        }
        return true;
    }

    /**
     * Sanitizes a list of compiled {@code .class} files in place when needed.
     *
     * @return number of class files patched
     */
    public static int sanitizeClassFilesInPlace(List<File> classFiles) throws IOException {
        if (classFiles == null || classFiles.isEmpty()) return 0;
        int patched = 0;
        for (File cf : classFiles) {
            if (cf == null || !cf.isFile() || !cf.getName().endsWith(".class")) continue;
            byte[] original = readFileBytes(cf);
            SanitizeResult res = sanitizeClassBytes(original);
            if (res.modified) {
                try (FileOutputStream fos = new FileOutputStream(cf)) {
                    fos.write(res.bytes);
                }
                patched++;
            }
        }
        return patched;
    }

    /**
     * Creates a sanitized copy of {@code inputJar} into {@code outputJar} ONLY if any entry
     * requires patching or stripping ({@code MethodParameters} with {@code name_index == 0},
     * {@code module-info.class}, {@code META-INF/versions/*}, or duplicate entries).
     * If no entry needed modification, {@code outputJar} is not created and {@code stats.effectiveFile == inputJar}.
     */
    public static ArchiveSanitizeStats sanitizeJarIfNeeded(File inputJar, File outputJar) throws IOException {
        if (inputJar == null || !inputJar.isFile()) {
            return new ArchiveSanitizeStats(inputJar, false, 0, 0, 0);
        }

        boolean needsRewrite = false;
        int totalClasses = 0;
        int patchedClasses = 0;
        int strippedEntries = 0;

        Set<String> seenEntries = new HashSet<>();
        byte[] buffer = new byte[8192];

        // Single streaming pass into a temporary file, then discard if untouched
        File parent = outputJar.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmpOut = new File(outputJar.getAbsolutePath() + ".tmp");
        if (tmpOut.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmpOut.delete();
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputJar));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpOut))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                if (isIgnoredArchiveClass(name) || isSignatureFile(name)) {
                    needsRewrite = true;
                    strippedEntries++;
                    zis.closeEntry();
                    continue;
                }
                if (!seenEntries.add(name)) {
                    needsRewrite = true;
                    strippedEntries++;
                    zis.closeEntry();
                    continue;
                }

                byte[] entryBytes = readEntryBytes(zis, buffer);
                if (name.endsWith(".class")) {
                    totalClasses++;
                    SanitizeResult res = sanitizeClassBytes(entryBytes);
                    if (res.modified) {
                        needsRewrite = true;
                        patchedClasses++;
                        entryBytes = res.bytes;
                    }
                }
                zos.putNextEntry(new ZipEntry(name));
                zos.write(entryBytes);
                zos.closeEntry();
                zis.closeEntry();
            }
        } catch (IOException e) {
            if (tmpOut.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmpOut.delete();
            }
            throw e;
        }

        if (!needsRewrite) {
            //noinspection ResultOfMethodCallIgnored
            tmpOut.delete();
            return new ArchiveSanitizeStats(inputJar, false, totalClasses, 0, 0);
        }

        if (outputJar.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputJar.delete();
        }
        if (!tmpOut.renameTo(outputJar)) {
            copyFile(tmpOut, outputJar);
            //noinspection ResultOfMethodCallIgnored
            tmpOut.delete();
        }
        return new ArchiveSanitizeStats(outputJar, true, totalClasses, patchedClasses, strippedEntries);
    }

    private static byte[] readFileBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(256, f.length()))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] readEntryBytes(InputStream is, byte[] buffer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n;
        while ((n = is.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[8192];
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static int readU2(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void writeU2(ByteArrayOutputStream out, int val) {
        out.write((val >>> 8) & 0xFF);
        out.write(val & 0xFF);
    }
}
