package com.example.sandrix.engine;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;

/**
 * Pure-Java Android APK 4-byte / 4096-byte ZIP aligner.
 *
 * <p>Ensures uncompressed ({@code STORED}) archive entries start at 4-byte boundaries
 * (and 4096-byte page boundaries for shared libraries {@code .so}), enabling Android's
 * {@code AssetManager2} and runtime linker to {@code mmap()} {@code resources.arsc} and
 * native libraries without runtime crashes or {@code Resources$NotFoundException}.
 */
public final class ZipAligner {

    public static final int DEFAULT_ALIGNMENT = 4;
    public static final int SO_PAGE_ALIGNMENT = 4096;

    private static final int LFH_SIGNATURE = 0x04034B50;
    private static final int CDH_SIGNATURE = 0x02014B50;
    private static final int EOCD_SIGNATURE = 0x06054B50;

    private static final int LFH_FIXED_LEN = 30;
    private static final int CDH_FIXED_LEN = 46;
    private static final int EOCD_FIXED_LEN = 22;

    private ZipAligner() {}

    private static final class EntryRecord {
        final String name;
        final byte[] nameBytes;
        final byte[] extra;
        final byte[] comment;
        final int versionMadeBy;
        final int versionNeeded;
        final int flags;
        final int method;
        final int dosTime;
        final int dosDate;
        final long crc;
        final long compressedSize;
        final long uncompressedSize;
        final int diskNumberStart;
        final int internalAttrs;
        final long externalAttrs;
        final long sourceDataOffset;
        long localHeaderOffset;

        EntryRecord(
                String name,
                byte[] nameBytes,
                byte[] extra,
                byte[] comment,
                int versionMadeBy,
                int versionNeeded,
                int flags,
                int method,
                int dosTime,
                int dosDate,
                long crc,
                long compressedSize,
                long uncompressedSize,
                int diskNumberStart,
                int internalAttrs,
                long externalAttrs,
                long sourceDataOffset) {
            this.name = name;
            this.nameBytes = nameBytes;
            this.extra = extra != null ? extra : new byte[0];
            this.comment = comment != null ? comment : new byte[0];
            this.versionMadeBy = versionMadeBy;
            this.versionNeeded = versionNeeded;
            this.flags = flags;
            this.method = method;
            this.dosTime = dosTime;
            this.dosDate = dosDate;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.diskNumberStart = diskNumberStart;
            this.internalAttrs = internalAttrs;
            this.externalAttrs = externalAttrs;
            this.sourceDataOffset = sourceDataOffset;
        }
    }

    /**
     * Aligns {@code inputApk} into {@code outputApk}. Uncompressed entries ({@code STORED})
     * are aligned to 4 bytes (and {@code .so} shared libraries to 4096 bytes).
     * Raw payload bytes are copied directly without decompressing or modifying compressed streams.
     */
    public static void alignApk(File inputApk, File outputApk, int alignment) throws IOException {
        if (inputApk == null || !inputApk.isFile()) {
            throw new IOException("Input APK does not exist: " + inputApk);
        }
        File outParent = outputApk.getParentFile();
        if (outParent != null && !outParent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outParent.mkdirs();
        }

        int targetAlignment = Math.max(4, alignment);
        byte[] buffer = new byte[16384];

        try (RandomAccessFile inRaf = new RandomAccessFile(inputApk, "r");
             FileOutputStream fos = new FileOutputStream(outputApk);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {

            long eocdOffset = findEocdOffset(inRaf);
            if (eocdOffset < 0) {
                throw new IOException("Malformed ZIP archive: EOCD not found in " + inputApk.getName());
            }

            inRaf.seek(eocdOffset);
            byte[] eocdBuf = new byte[EOCD_FIXED_LEN];
            inRaf.readFully(eocdBuf);
            ByteBuffer eocd = ByteBuffer.wrap(eocdBuf).order(ByteOrder.LITTLE_ENDIAN);
            if (eocd.getInt(0) != EOCD_SIGNATURE) {
                throw new IOException("Invalid EOCD signature in " + inputApk.getName());
            }

            int entryCount = eocd.getShort(10) & 0xFFFF;
            long cdOffset = eocd.getInt(16) & 0xFFFFFFFFL;

            // 1. Read all entries from Central Directory and calculate raw payload offsets
            List<EntryRecord> sourceRecords = new ArrayList<>(entryCount);
            inRaf.seek(cdOffset);

            for (int i = 0; i < entryCount; i++) {
                byte[] cdhFixed = new byte[CDH_FIXED_LEN];
                inRaf.readFully(cdhFixed);
                ByteBuffer cdh = ByteBuffer.wrap(cdhFixed).order(ByteOrder.LITTLE_ENDIAN);
                if (cdh.getInt(0) != CDH_SIGNATURE) {
                    throw new IOException("Invalid CDH signature at entry " + i + " in " + inputApk.getName());
                }

                int versionMadeBy = cdh.getShort(4) & 0xFFFF;
                int versionNeeded = cdh.getShort(6) & 0xFFFF;
                int flags = cdh.getShort(8) & 0xFFFF;
                int method = cdh.getShort(10) & 0xFFFF;
                int dosTime = cdh.getShort(12) & 0xFFFF;
                int dosDate = cdh.getShort(14) & 0xFFFF;
                long crc = cdh.getInt(16) & 0xFFFFFFFFL;
                long compressedSize = cdh.getInt(20) & 0xFFFFFFFFL;
                long uncompressedSize = cdh.getInt(24) & 0xFFFFFFFFL;
                int nameLen = cdh.getShort(28) & 0xFFFF;
                int extraLen = cdh.getShort(30) & 0xFFFF;
                int commentLen = cdh.getShort(32) & 0xFFFF;
                int diskNumberStart = cdh.getShort(34) & 0xFFFF;
                int internalAttrs = cdh.getShort(36) & 0xFFFF;
                long externalAttrs = cdh.getInt(38) & 0xFFFFFFFFL;
                long lfhOffset = cdh.getInt(42) & 0xFFFFFFFFL;

                byte[] nameBytes = new byte[nameLen];
                inRaf.readFully(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);

                byte[] extraBytes = new byte[extraLen];
                if (extraLen > 0) {
                    inRaf.readFully(extraBytes);
                }

                byte[] commentBytes = new byte[commentLen];
                if (commentLen > 0) {
                    inRaf.readFully(commentBytes);
                }

                long savedPos = inRaf.getFilePointer();
                inRaf.seek(lfhOffset);
                byte[] lfhFixed = new byte[LFH_FIXED_LEN];
                inRaf.readFully(lfhFixed);
                ByteBuffer lfh = ByteBuffer.wrap(lfhFixed).order(ByteOrder.LITTLE_ENDIAN);
                if (lfh.getInt(0) != LFH_SIGNATURE) {
                    throw new IOException("Invalid LFH signature at " + lfhOffset + " for " + name);
                }
                int lfhNameLen = lfh.getShort(26) & 0xFFFF;
                int lfhExtraLen = lfh.getShort(28) & 0xFFFF;
                long sourceDataOffset = lfhOffset + LFH_FIXED_LEN + lfhNameLen + lfhExtraLen;
                inRaf.seek(savedPos);

                sourceRecords.add(new EntryRecord(
                        name,
                        nameBytes,
                        extraBytes,
                        commentBytes,
                        versionMadeBy,
                        versionNeeded,
                        flags,
                        method,
                        dosTime,
                        dosDate,
                        crc,
                        compressedSize,
                        uncompressedSize,
                        diskNumberStart,
                        internalAttrs,
                        externalAttrs,
                        sourceDataOffset));
            }

            // 2. Write aligned entries sequentially
            long currentOffset = 0L;
            List<EntryRecord> alignedRecords = new ArrayList<>(sourceRecords.size());

            for (EntryRecord r : sourceRecords) {
                int entryAlign = 1;
                if (r.method == ZipEntry.STORED) {
                    entryAlign = r.name.endsWith(".so") ? SO_PAGE_ALIGNMENT : targetAlignment;
                }

                byte[] newExtra = r.extra;
                if (entryAlign > 1) {
                    long unalignedDataOffset = currentOffset + LFH_FIXED_LEN + r.nameBytes.length + r.extra.length;
                    int padding = (int) ((entryAlign - (unalignedDataOffset % entryAlign)) % entryAlign);
                    if (padding > 0) {
                        newExtra = new byte[r.extra.length + padding];
                        System.arraycopy(r.extra, 0, newExtra, 0, r.extra.length);
                    }
                }

                // Clear bit 3 (0x0008 Data Descriptor flag) because LFH already contains
                // CRC/sizes from CDH and no trailing 16-byte Data Descriptor is written.
                int alignedFlags = r.flags & ~0x0008;

                EntryRecord alignedRec = new EntryRecord(
                        r.name,
                        r.nameBytes,
                        newExtra,
                        r.comment,
                        r.versionMadeBy,
                        r.versionNeeded,
                        alignedFlags,
                        r.method,
                        r.dosTime,
                        r.dosDate,
                        r.crc,
                        r.compressedSize,
                        r.uncompressedSize,
                        r.diskNumberStart,
                        r.internalAttrs,
                        r.externalAttrs,
                        r.sourceDataOffset);
                alignedRec.localHeaderOffset = currentOffset;
                alignedRecords.add(alignedRec);

                // Write Local File Header
                writeLocalHeader(bos, alignedRec);
                currentOffset += LFH_FIXED_LEN + r.nameBytes.length + newExtra.length;

                // Copy raw payload bytes directly from source archive
                inRaf.seek(r.sourceDataOffset);
                long bytesRemaining = r.compressedSize;
                while (bytesRemaining > 0) {
                    int toRead = (int) Math.min(buffer.length, bytesRemaining);
                    int n = inRaf.read(buffer, 0, toRead);
                    if (n < 0) {
                        throw new IOException("Unexpected EOF while reading raw entry payload for " + r.name);
                    }
                    bos.write(buffer, 0, n);
                    bytesRemaining -= n;
                    currentOffset += n;
                }
            }

            // 3. Write Central Directory
            long cdStartOffset = currentOffset;
            for (EntryRecord r : alignedRecords) {
                writeCentralDirectoryHeader(bos, r);
                currentOffset += CDH_FIXED_LEN + r.nameBytes.length + r.extra.length + r.comment.length;
            }
            long cdSize = currentOffset - cdStartOffset;

            // 4. Write End of Central Directory
            writeEocd(bos, alignedRecords.size(), cdSize, cdStartOffset);
            bos.flush();
        }
    }

    /**
     * In-place alignment of an APK file.
     */
    public static void alignInPlace(File apkFile, int alignment, File tempDir) throws IOException {
        File workDir = tempDir != null ? tempDir : apkFile.getParentFile();
        if (workDir != null && !workDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            workDir.mkdirs();
        }
        File tempAligned = new File(workDir, apkFile.getName() + ".aligned.tmp");
        alignApk(apkFile, tempAligned, alignment);
        Files.move(tempAligned.toPath(), apkFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Checks if all uncompressed ({@code STORED}) entries in {@code apkFile} are aligned.
     */
    public static boolean isApkAligned(File apkFile, int alignment) {
        if (apkFile == null || !apkFile.isFile() || apkFile.length() < 22) {
            return false;
        }
        int targetAlignment = Math.max(4, alignment);
        try (RandomAccessFile raf = new RandomAccessFile(apkFile, "r")) {
            long eocdOffset = findEocdOffset(raf);
            if (eocdOffset < 0) return false;

            raf.seek(eocdOffset);
            byte[] eocdBuf = new byte[EOCD_FIXED_LEN];
            raf.readFully(eocdBuf);
            ByteBuffer eocd = ByteBuffer.wrap(eocdBuf).order(ByteOrder.LITTLE_ENDIAN);
            if (eocd.getInt(0) != EOCD_SIGNATURE) return false;

            int entryCount = eocd.getShort(10) & 0xFFFF;
            long cdOffset = eocd.getInt(16) & 0xFFFFFFFFL;

            raf.seek(cdOffset);
            for (int i = 0; i < entryCount; i++) {
                byte[] cdhFixed = new byte[CDH_FIXED_LEN];
                raf.readFully(cdhFixed);
                ByteBuffer cdh = ByteBuffer.wrap(cdhFixed).order(ByteOrder.LITTLE_ENDIAN);
                if (cdh.getInt(0) != CDH_SIGNATURE) return false;

                int method = cdh.getShort(10) & 0xFFFF;
                int nameLen = cdh.getShort(28) & 0xFFFF;
                int extraLen = cdh.getShort(30) & 0xFFFF;
                int commentLen = cdh.getShort(32) & 0xFFFF;
                long lfhOffset = cdh.getInt(42) & 0xFFFFFFFFL;

                byte[] nameBuf = new byte[nameLen];
                raf.readFully(nameBuf);
                String name = new String(nameBuf, StandardCharsets.UTF_8);
                raf.skipBytes(extraLen + commentLen);

                if (method == ZipEntry.STORED) {
                    int reqAlign = name.endsWith(".so") ? SO_PAGE_ALIGNMENT : targetAlignment;
                    long savedPos = raf.getFilePointer();
                    raf.seek(lfhOffset);
                    byte[] lfhFixed = new byte[LFH_FIXED_LEN];
                    raf.readFully(lfhFixed);
                    ByteBuffer lfh = ByteBuffer.wrap(lfhFixed).order(ByteOrder.LITTLE_ENDIAN);
                    if (lfh.getInt(0) != LFH_SIGNATURE) return false;

                    int lfhNameLen = lfh.getShort(26) & 0xFFFF;
                    int lfhExtraLen = lfh.getShort(28) & 0xFFFF;
                    long dataOffset = lfhOffset + LFH_FIXED_LEN + lfhNameLen + lfhExtraLen;
                    if ((dataOffset % reqAlign) != 0) {
                        return false;
                    }
                    raf.seek(savedPos);
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeLocalHeader(OutputStream out, EntryRecord r) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(LFH_FIXED_LEN).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(LFH_SIGNATURE);
        buf.putShort((short) r.versionNeeded);
        buf.putShort((short) r.flags);
        buf.putShort((short) r.method);
        buf.putShort((short) r.dosTime);
        buf.putShort((short) r.dosDate);
        buf.putInt((int) r.crc);
        buf.putInt((int) r.compressedSize);
        buf.putInt((int) r.uncompressedSize);
        buf.putShort((short) r.nameBytes.length);
        buf.putShort((short) r.extra.length);
        out.write(buf.array());
        out.write(r.nameBytes);
        if (r.extra.length > 0) {
            out.write(r.extra);
        }
    }

    private static void writeCentralDirectoryHeader(OutputStream out, EntryRecord r) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CDH_FIXED_LEN).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(CDH_SIGNATURE);
        buf.putShort((short) r.versionMadeBy);
        buf.putShort((short) r.versionNeeded);
        buf.putShort((short) r.flags);
        buf.putShort((short) r.method);
        buf.putShort((short) r.dosTime);
        buf.putShort((short) r.dosDate);
        buf.putInt((int) r.crc);
        buf.putInt((int) r.compressedSize);
        buf.putInt((int) r.uncompressedSize);
        buf.putShort((short) r.nameBytes.length);
        buf.putShort((short) r.extra.length);
        buf.putShort((short) r.comment.length);
        buf.putShort((short) r.diskNumberStart);
        buf.putShort((short) r.internalAttrs);
        buf.putInt((int) r.externalAttrs);
        buf.putInt((int) r.localHeaderOffset);
        out.write(buf.array());
        out.write(r.nameBytes);
        if (r.extra.length > 0) {
            out.write(r.extra);
        }
        if (r.comment.length > 0) {
            out.write(r.comment);
        }
    }

    private static void writeEocd(OutputStream out, int entryCount, long cdSize, long cdOffset) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(EOCD_FIXED_LEN).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(EOCD_SIGNATURE);
        buf.putShort((short) 0);          // Disk number
        buf.putShort((short) 0);          // Start disk
        buf.putShort((short) entryCount); // Entries on disk
        buf.putShort((short) entryCount); // Total entries
        buf.putInt((int) cdSize);
        buf.putInt((int) cdOffset);
        buf.putShort((short) 0);          // Comment length
        out.write(buf.array());
    }

    private static long findEocdOffset(RandomAccessFile raf) throws IOException {
        long fileLen = raf.length();
        long maxScan = Math.min(fileLen, 65536L + 22L);
        byte[] buf = new byte[(int) maxScan];
        long scanStart = fileLen - maxScan;
        raf.seek(scanStart);
        raf.readFully(buf);
        for (int i = buf.length - 22; i >= 0; i--) {
            if (buf[i] == 0x50 && buf[i + 1] == 0x4b && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
                return scanStart + i;
            }
        }
        return -1L;
    }
}
