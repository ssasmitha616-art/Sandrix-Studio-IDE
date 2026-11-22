package com.example.sandrix.engine;

import android.app.ActivityManager;
import android.content.Context;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * SHA-256 + minSdk pre-dex cache for external library JARs and AAR {@code classes.jar} files.
 *
 * <p>Sanitizes classfile metadata via {@link ClassFileSanitizer} prior to dexing (preventing
 * R8/D8 4.0.x {@code MethodParameters} NPE crashes such as {@code ActionBarDrawerToggle$1.class}),
 * provides sibling classpath JARs for cross-library desugaring, and automatically falls back to
 * chunked pre-dexing for large libraries on low-memory devices.</p>
 */
public final class DexCacheManager {

    private static final String CACHE_SCHEMA_TAG = "v2";
    private static final int LOW_MEM_CHUNK_THRESHOLD = 750;
    private static final int CHUNK_CLASS_LIMIT = 400;

    public interface CacheLogListener {
        void onLog(String message, boolean isError);
    }

    public interface CancellationCheck {
        boolean isCancelled();
    }

    public static final class PreDexResult {
        public final List<File> cachedDexFiles;
        public final int cacheHits;
        public final int newlyDexed;
        public final int sanitizedArchives;

        public PreDexResult(List<File> cachedDexFiles, int cacheHits, int newlyDexed) {
            this(cachedDexFiles, cacheHits, newlyDexed, 0);
        }

        public PreDexResult(List<File> cachedDexFiles, int cacheHits, int newlyDexed, int sanitizedArchives) {
            this.cachedDexFiles = Collections.unmodifiableList(new ArrayList<>(cachedDexFiles));
            this.cacheHits = cacheHits;
            this.newlyDexed = newlyDexed;
            this.sanitizedArchives = sanitizedArchives;
        }
    }

    public static final class CacheStats {
        public final int dexEntryCount;
        public final long dexCacheBytes;
        public final long mavenCacheBytes;

        public CacheStats(int dexEntryCount, long dexCacheBytes, long mavenCacheBytes) {
            this.dexEntryCount = dexEntryCount;
            this.dexCacheBytes = dexCacheBytes;
            this.mavenCacheBytes = mavenCacheBytes;
        }
    }

    private final File cacheDir;

    public DexCacheManager(File cacheDir) {
        this.cacheDir = cacheDir;
        if (this.cacheDir != null && !this.cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.cacheDir.mkdirs();
        }
    }

    public static boolean isLowMemoryDevice(Context context) {
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.isLowRamDevice()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return Runtime.getRuntime().maxMemory() <= 220L * 1024L * 1024L;
    }

    public static CacheStats inspectEngineCaches(File engineRoot) {
        if (engineRoot == null || !engineRoot.exists()) {
            return new CacheStats(0, 0L, 0L);
        }
        File dexDir = new File(engineRoot, "dex_cache");
        File mavenDir = new File(engineRoot, "maven_cache");
        int dexCount = 0;
        long dexBytes = 0L;
        if (dexDir.exists() && dexDir.isDirectory()) {
            File[] files = dexDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".jar")) {
                        dexCount++;
                        dexBytes += f.length();
                    }
                }
            }
        }
        long mavenBytes = folderSize(mavenDir);
        return new CacheStats(dexCount, dexBytes, mavenBytes);
    }

    public static int clearDexCache(File engineRoot) {
        if (engineRoot == null) return 0;
        File dexDir = new File(engineRoot, "dex_cache");
        return deleteFolderContents(dexDir);
    }

    public PreDexResult preparePreDexedLibraries(
            List<File> jarLibs,
            int minSdk,
            File androidJar,
            boolean lowMemoryMode,
            DiagnosticsHandler diagnosticsHandler,
            CacheLogListener logListener,
            CancellationCheck cancellationCheck) throws IOException {
        return preparePreDexedLibraries(
                jarLibs,
                Collections.emptyList(),
                minSdk,
                androidJar,
                lowMemoryMode,
                diagnosticsHandler,
                logListener,
                cancellationCheck);
    }

    public PreDexResult preparePreDexedLibraries(
            List<File> jarLibs,
            List<File> fullClasspathJars,
            int minSdk,
            File androidJar,
            boolean lowMemoryMode,
            DiagnosticsHandler diagnosticsHandler,
            CacheLogListener logListener,
            CancellationCheck cancellationCheck) throws IOException {
        if (jarLibs == null || jarLibs.isEmpty()) {
            return new PreDexResult(Collections.emptyList(), 0, 0, 0);
        }
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }

        // Build complete sibling classpath pool for accurate cross-JAR desugaring
        List<File> allClasspathPool = new ArrayList<>();
        if (fullClasspathJars != null) {
            for (File cp : fullClasspathJars) {
                if (cp != null && cp.isFile() && cp.length() > 0 && !allClasspathPool.contains(cp)) {
                    allClasspathPool.add(cp);
                }
            }
        }
        for (File lib : jarLibs) {
            if (lib != null && lib.isFile() && lib.length() > 0 && !allClasspathPool.contains(lib)) {
                allClasspathPool.add(lib);
            }
        }

        ExecutorService singleWorker = Executors.newSingleThreadExecutor(
                r -> new Thread(null, r, "SandrixD8PreDex", 16L * 1024L * 1024L));

        List<File> outputs = new ArrayList<>(jarLibs.size());
        int hits = 0;
        int newlyDexed = 0;
        int sanitizedArchives = 0;

        File stagingDir = new File(cacheDir, "_staging");
        if (!stagingDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            stagingDir.mkdirs();
        }

        try {
            for (File jar : jarLibs) {
                if (cancellationCheck != null && cancellationCheck.isCancelled()) {
                    break;
                }
                if (jar == null || !jar.exists() || !jar.isFile() || jar.length() == 0) {
                    continue;
                }

                if (!containsClassEntries(jar)) {
                    // Already dex-only or resource-only JAR
                    outputs.add(jar);
                    continue;
                }

                String sha256 = computeSha256(jar);
                String safeBase = jar.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                if (safeBase.length() > 36) {
                    safeBase = safeBase.substring(0, 36);
                }
                File cachedDexJar = new File(
                        cacheDir,
                        safeBase + "_" + sha256.substring(0, 16) + "_min" + minSdk + "_" + CACHE_SCHEMA_TAG + ".jar");

                if (cachedDexJar.exists() && cachedDexJar.length() > 0 && hasClassesDex(cachedDexJar)) {
                    hits++;
                    outputs.add(cachedDexJar);
                    continue;
                }

                // Sanitize bytecode metadata (e.g. null-name MethodParameters in AndroidX) before dexing
                File stagedJar = new File(stagingDir, cachedDexJar.getName() + ".sanitized.jar");
                ClassFileSanitizer.ArchiveSanitizeStats sanitizeStats =
                        ClassFileSanitizer.sanitizeJarIfNeeded(jar, stagedJar);
                File effectiveInput = sanitizeStats.effectiveFile;
                if (sanitizeStats.rewritten) {
                    sanitizedArchives++;
                    if (logListener != null && sanitizeStats.patchedClasses > 0) {
                        logListener.onLog("[D8-SANITIZE] Patched " + sanitizeStats.patchedClasses
                                + " class(es) in " + jar.getName() + " for D8 compatibility.", false);
                    }
                }

                File tempOut = new File(cacheDir, cachedDexJar.getName() + ".tmp.jar");
                deleteIfExists(tempOut);

                if (logListener != null) {
                    logListener.onLog("[D8-CACHE] Pre-dexing " + jar.getName()
                            + " (" + sanitizeStats.totalClasses + " classes"
                            + (lowMemoryMode ? ", low-RAM mode" : "") + ")...", false);
                }

                List<File> siblingClasspath = buildSiblingClasspath(allClasspathPool, jar, effectiveInput);

                boolean shouldChunkFirst = lowMemoryMode && sanitizeStats.totalClasses > LOW_MEM_CHUNK_THRESHOLD;
                boolean ok = false;
                Throwable failure = null;

                if (!shouldChunkFirst) {
                    try {
                        ok = runSinglePreDex(singleWorker, effectiveInput, siblingClasspath,
                                androidJar, minSdk, tempOut, diagnosticsHandler);
                    } catch (Throwable t) {
                        failure = unwrapExecutionException(t);
                        deleteIfExists(tempOut);
                    }
                }

                // Chunked fallback for very large JARs or memory-constrained devices
                if (!ok && sanitizeStats.totalClasses > CHUNK_CLASS_LIMIT) {
                    if (logListener != null) {
                        logListener.onLog("[D8-CACHE] Chunked pre-dexing " + jar.getName()
                                + " (" + sanitizeStats.totalClasses + " classes)...", false);
                    }
                    try {
                        ok = runChunkedPreDex(
                                singleWorker,
                                stagingDir,
                                effectiveInput,
                                siblingClasspath,
                                androidJar,
                                minSdk,
                                tempOut,
                                diagnosticsHandler,
                                cancellationCheck);
                        failure = null;
                    } catch (Throwable chunkErr) {
                        failure = unwrapExecutionException(chunkErr);
                        deleteIfExists(tempOut);
                    }
                }

                if (ok && hasClassesDex(tempOut)) {
                    if (sanitizeStats.rewritten && stagedJar.exists()) {
                        deleteIfExists(stagedJar);
                    }
                    deleteIfExists(cachedDexJar);
                    if (!tempOut.renameTo(cachedDexJar)) {
                        Files.copy(tempOut.toPath(), cachedDexJar.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        deleteIfExists(tempOut);
                    }
                    newlyDexed++;
                    outputs.add(cachedDexJar);
                } else {
                    deleteIfExists(tempOut);
                    File fallbackOut = jar;
                    if (sanitizeStats.rewritten && stagedJar.exists()) {
                        File persistedSanitized = new File(cacheDir, cachedDexJar.getName() + ".sanitized_fallback.jar");
                        deleteIfExists(persistedSanitized);
                        if (stagedJar.renameTo(persistedSanitized)) {
                            fallbackOut = persistedSanitized;
                        } else {
                            Files.copy(stagedJar.toPath(), persistedSanitized.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            deleteIfExists(stagedJar);
                            fallbackOut = persistedSanitized;
                        }
                    }
                    if (logListener != null) {
                        String reason = failure != null
                                ? failure.getClass().getSimpleName() + ": " + failure.getMessage()
                                : "no classes.dex emitted";
                        logListener.onLog("[D8-CACHE WARN] Pre-dex skipped for "
                                + jar.getName() + " (" + reason + "); passing sanitized JAR to final D8.", false);
                    }
                    outputs.add(fallbackOut);
                }

            }
        } finally {
            singleWorker.shutdownNow();
            deleteFolderContents(stagingDir);
        }

        if (logListener != null && (hits > 0 || newlyDexed > 0)) {
            logListener.onLog("[D8-CACHE] Pre-dex cache: " + hits + " hit(s), "
                    + newlyDexed + " newly cached"
                    + (sanitizedArchives > 0 ? (", " + sanitizedArchives + " sanitized") : "") + ".", false);
        }

        return new PreDexResult(outputs, hits, newlyDexed, sanitizedArchives);
    }

    private static boolean runSinglePreDex(
            ExecutorService worker,
            File programJar,
            List<File> classpathJars,
            File androidJar,
            int minSdk,
            File tempOut,
            DiagnosticsHandler diagnosticsHandler) throws Exception {
        Future<Boolean> future = worker.submit(() -> {
            D8Command.Builder builder = D8Command.builder(diagnosticsHandler)
                    .setMinApiLevel(Math.max(1, minSdk))
                    .setMode(CompilationMode.RELEASE)
                    .setIntermediate(true)
                    .setOutput(tempOut.toPath(), OutputMode.DexIndexed);
            if (androidJar != null && androidJar.exists()) {
                builder.addLibraryFiles(androidJar.toPath());
            }
            for (File cp : classpathJars) {
                builder.addClasspathFiles(cp.toPath());
            }
            builder.addProgramFiles(programJar.toPath());
            D8.run(builder.build());
            return tempOut.exists() && tempOut.length() > 0;
        });
        return Boolean.TRUE.equals(future.get());
    }

    private static boolean runChunkedPreDex(
            ExecutorService worker,
            File stagingDir,
            File sanitizedJar,
            List<File> siblingClasspath,
            File androidJar,
            int minSdk,
            File mergedTempOut,
            DiagnosticsHandler diagnosticsHandler,
            CancellationCheck cancellationCheck) throws Exception {
        List<File> chunkInputJars = new ArrayList<>();
        List<File> chunkDexJars = new ArrayList<>();
        File resourceOnlyJar = new File(stagingDir, "chunk_res.jar");
        boolean hasResources = false;

        try {
            byte[] buffer = new byte[8192];
            int chunkIdx = 0;
            int classInChunk = 0;
            ZipOutputStream currentClassZip = null;
            File currentClassJar = null;
            ZipOutputStream resZip = new ZipOutputStream(new FileOutputStream(resourceOnlyJar));

            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(sanitizedJar))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    String name = entry.getName();
                    if (name.endsWith(".class")) {
                        if (currentClassZip == null || classInChunk >= CHUNK_CLASS_LIMIT) {
                            if (currentClassZip != null) {
                                currentClassZip.close();
                            }
                            currentClassJar = new File(stagingDir, "chunk_in_" + (chunkIdx++) + ".jar");
                            chunkInputJars.add(currentClassJar);
                            currentClassZip = new ZipOutputStream(new FileOutputStream(currentClassJar));
                            classInChunk = 0;
                        }
                        currentClassZip.putNextEntry(new ZipEntry(name));
                        copyStream(zis, currentClassZip, buffer);
                        currentClassZip.closeEntry();
                        classInChunk++;
                    } else {
                        hasResources = true;
                        resZip.putNextEntry(new ZipEntry(name));
                        copyStream(zis, resZip, buffer);
                        resZip.closeEntry();
                    }
                    zis.closeEntry();
                }
            } finally {
                if (currentClassZip != null) {
                    currentClassZip.close();
                }
                resZip.close();
            }

            if (chunkInputJars.isEmpty()) {
                return false;
            }

            // Full classpath for each chunk includes sanitizedJar itself so intra-JAR references resolve
            List<File> chunkClasspath = new ArrayList<>(siblingClasspath.size() + 1);
            chunkClasspath.add(sanitizedJar);
            chunkClasspath.addAll(siblingClasspath);

            for (int i = 0; i < chunkInputJars.size(); i++) {
                if (cancellationCheck != null && cancellationCheck.isCancelled()) {
                    return false;
                }
                File chunkIn = chunkInputJars.get(i);
                File chunkDexOut = new File(stagingDir, "chunk_dex_" + i + ".jar");
                boolean chunkOk = runSinglePreDex(
                        worker, chunkIn, chunkClasspath, androidJar, minSdk, chunkDexOut, diagnosticsHandler);
                if (!chunkOk || !hasClassesDex(chunkDexOut)) {
                    return false;
                }
                chunkDexJars.add(chunkDexOut);
                deleteIfExists(chunkIn);
            }

            // Merge intermediate DEX chunk archives into mergedTempOut via D8 intermediate dex merge
            Future<Boolean> mergeFuture = worker.submit(() -> {
                D8Command.Builder mergeBuilder = D8Command.builder(diagnosticsHandler)
                        .setMinApiLevel(Math.max(1, minSdk))
                        .setMode(CompilationMode.RELEASE)
                        .setIntermediate(true)
                        .setOutput(mergedTempOut.toPath(), OutputMode.DexIndexed);
                if (androidJar != null && androidJar.exists()) {
                    mergeBuilder.addLibraryFiles(androidJar.toPath());
                }
                for (File chunkDex : chunkDexJars) {
                    mergeBuilder.addProgramFiles(chunkDex.toPath());
                }
                D8.run(mergeBuilder.build());
                return mergedTempOut.exists() && mergedTempOut.length() > 0;
            });
            boolean merged = Boolean.TRUE.equals(mergeFuture.get());
            if (merged && hasResources) {
                appendResourcesToArchive(mergedTempOut, resourceOnlyJar);
            }
            return merged;
        } finally {
            for (File f : chunkInputJars) deleteIfExists(f);
            for (File f : chunkDexJars) deleteIfExists(f);
            deleteIfExists(resourceOnlyJar);
        }
    }

    private static void appendResourcesToArchive(File targetJar, File resourceJar) throws IOException {
        if (!targetJar.exists() || !resourceJar.exists()) return;
        File combined = new File(targetJar.getAbsolutePath() + ".res.tmp.jar");
        byte[] buffer = new byte[8192];
        try (ZipInputStream dexZis = new ZipInputStream(new FileInputStream(targetJar));
             ZipInputStream resZis = new ZipInputStream(new FileInputStream(resourceJar));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(combined))) {
            ZipEntry entry;
            while ((entry = dexZis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    copyStream(dexZis, zos, buffer);
                    zos.closeEntry();
                }
                dexZis.closeEntry();
            }
            while ((entry = resZis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    copyStream(resZis, zos, buffer);
                    zos.closeEntry();
                }
                resZis.closeEntry();
            }
        }
        deleteIfExists(targetJar);
        if (!combined.renameTo(targetJar)) {
            Files.copy(combined.toPath(), targetJar.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            deleteIfExists(combined);
        }
    }

    private static List<File> buildSiblingClasspath(
            List<File> allPool,
            File originalJar,
            File effectiveJar) {
        List<File> siblings = new ArrayList<>(allPool.size());
        String origPath = originalJar.getAbsolutePath();
        String effPath = effectiveJar.getAbsolutePath();
        for (File candidate : allPool) {
            String cp = candidate.getAbsolutePath();
            if (!cp.equals(origPath) && !cp.equals(effPath) && candidate.exists()) {
                siblings.add(candidate);
            }
        }
        return siblings;
    }

    private static Throwable unwrapExecutionException(Throwable t) {
        if (t instanceof ExecutionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static void copyStream(ZipInputStream in, ZipOutputStream out, byte[] buffer) throws IOException {
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }

    private static boolean containsClassEntries(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean hasClassesDex(File archive) {
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!e.isDirectory() && name.startsWith("classes") && name.endsWith(".dex")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static long folderSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long sum = 0L;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                sum += folderSize(c);
            }
        }
        return sum;
    }

    private static int deleteFolderContents(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        int deleted = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    deleted += deleteFolderContents(c);
                }
                if (c.delete()) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private static void deleteIfExists(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static String computeSha256(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (FileInputStream fis = new FileInputStream(file)) {
                int n;
                while ((n = fis.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("SHA-256 failed for " + file.getName(), e);
        }
    }
}
