package com.example.sandrix.engine;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Inspects and manages the on-device Sandrix native/ART build toolchain under
 * {@code filesDir/sandrix_engine/}.
 *
 * <p>ECJ (Java compiler), D8 (dexer), and ApkSigner (v1/v2/v3 APK signer) are bundled directly
 * inside Sandrix Studio, so only {@code aapt2} and {@code android.jar} are required externally
 * (while optional external native binaries or dexed JARs remain supported).
 */
public final class SandrixToolchain {

    public static final String ENGINE_DIR_NAME = "sandrix_engine";

    public final File engineRoot;
    public final File binDir;
    public final File libDir;
    public final File libsDir;
    public final File platformsDir;
    public final File keysDir;

    public final File aapt2Binary;
    public final File javacBinary;
    public final File d8Binary;
    public final File apksignerBinary;
    public final File zipBinary;
    public final File zipalignBinary;

    public final File ecjJar;
    public final File d8Jar;
    public final File apksignerJar;
    public final File debugKeystore;

    private final Context appContext;

    private SandrixToolchain(Context context) {
        this.appContext = context.getApplicationContext();
        this.engineRoot = new File(appContext.getFilesDir(), ENGINE_DIR_NAME);
        this.binDir = new File(engineRoot, "bin");
        this.libDir = new File(engineRoot, "lib");
        this.libsDir = new File(engineRoot, "libs");
        this.platformsDir = new File(engineRoot, "platforms");
        this.keysDir = new File(engineRoot, "keys");

        ensureDirectories();

        String nativeLibDir = appContext.getApplicationInfo().nativeLibraryDir;

        this.aapt2Binary = locateExecutable("aapt2", "libaapt2.so", nativeLibDir);
        this.javacBinary = locateExecutable("javac", "libjavac.so", nativeLibDir);
        this.d8Binary = locateExecutable("d8", "libd8.so", nativeLibDir);
        this.apksignerBinary = locateExecutable("apksigner", "libapksigner.so", nativeLibDir);
        this.zipBinary = locateExecutable("zip", "libzip.so", nativeLibDir);
        this.zipalignBinary = locateExecutable("zipalign", "libzipalign.so", nativeLibDir);

        this.ecjJar = locateFile(libsDir, "ecj.jar");
        this.d8Jar = locateFile(libsDir, "d8.jar");
        this.apksignerJar = locateFile(libsDir, "apksigner.jar");
        this.debugKeystore = new File(keysDir, "sandrix_debug.jks");
    }

    public static SandrixToolchain inspect(Context context) {
        return new SandrixToolchain(context);
    }

    private void ensureDirectories() {
        mkdirsSafe(engineRoot);
        mkdirsSafe(binDir);
        mkdirsSafe(libDir);
        mkdirsSafe(libsDir);
        mkdirsSafe(platformsDir);
        mkdirsSafe(keysDir);

        chmodSafe(engineRoot, 0755);
        chmodSafe(binDir, 0755);
        chmodSafe(libDir, 0755);
    }

    private static void mkdirsSafe(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    private static void chmodSafe(File file, int mode) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (Throwable ignored) {
            boolean exec = (mode & 0111) != 0;
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(exec, false);
        }
    }

    private File locateExecutable(String name, String soName, String nativeLibDir) {
        if (nativeLibDir != null) {
            File jniBin = new File(nativeLibDir, soName);
            if (jniBin.exists() && jniBin.isFile()) {
                return jniBin;
            }
        }
        File direct = new File(binDir, name);
        if (direct.exists() && direct.isFile()) {
            chmodSafe(direct, 0755);
            return direct;
        }
        File arm64 = new File(binDir, "arm64-v8a/" + name);
        if (arm64.exists() && arm64.isFile()) {
            chmodSafe(arm64, 0755);
            return arm64;
        }
        File nested = findExecutableRecursively(engineRoot, name);
        if (nested != null) {
            chmodSafe(nested, 0755);
            return nested;
        }
        return direct;
    }

    private static File findExecutableRecursively(File directory, String name) {
        File[] children = directory != null ? directory.listFiles() : null;
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile() && name.equals(child.getName())) return child;
            if (child.isDirectory()) {
                File found = findExecutableRecursively(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static File locateFile(File dir, String name) {
        return new File(dir, name);
    }

    /**
     * Returns true if the given JAR/ZIP archive contains {@code classes.dex} so it can be
     * executed by Android's {@code /system/bin/dalvikvm}.
     */
    public static boolean hasClassesDex(File jarFile) {
        if (jarFile == null || !jarFile.exists() || !jarFile.isFile()) {
            return false;
        }
        try (ZipFile zip = new ZipFile(jarFile)) {
            return zip.getEntry("classes.dex") != null;
        } catch (IOException e) {
            return false;
        }
    }

    public String buildLdLibraryPath() {
        List<String> paths = new ArrayList<>();
        if (libDir.exists()) paths.add(libDir.getAbsolutePath());
        if (binDir.exists()) paths.add(binDir.getAbsolutePath());
        if (libsDir.exists()) paths.add(libsDir.getAbsolutePath());
        String nativeLibDir = appContext.getApplicationInfo().nativeLibraryDir;
        if (nativeLibDir != null) paths.add(nativeLibDir);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(':');
            sb.append(paths.get(i));
        }
        return sb.toString();
    }

    public File findAndroidJar(int compileSdk) {
        File specific = new File(platformsDir, "android-" + compileSdk + "/android.jar");
        if (specific.exists()) {
            return specific;
        }
        File flat = new File(platformsDir, "android.jar");
        if (flat.exists()) {
            return flat;
        }
        File[] children = platformsDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File candidate = new File(child, "android.jar");
                    if (candidate.exists()) {
                        return candidate;
                    }
                }
            }
        }
        return specific;
    }

    public File ensureLambdaStubsJar() {
        File target = new File(libsDir, "core-lambda-stubs.jar");
        if (target.exists() && target.length() > 0) {
            return target;
        }
        try (InputStream is = appContext.getAssets().open("sandrix_engine/libs/core-lambda-stubs.jar");
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            chmodSafe(target, 0444);
            return target;
        } catch (IOException ignored) {
            return null;
        }
    }

    public File ensureJavaCompilerApiJar() {
        File target = new File(libsDir, "java-compiler-api.jar");
        if (target.exists() && target.length() > 0) {
            return target;
        }
        try (InputStream is = appContext.getAssets().open("sandrix_engine/libs/java-compiler-api.jar");
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
            chmodSafe(target, 0444);
            return target;
        } catch (IOException ignored) {
            return null;
        }
    }





    public boolean hasAapt2() {
        return aapt2Binary.exists() && aapt2Binary.canExecute();
    }

    public boolean hasNativeJavac() {
        return javacBinary.exists() && javacBinary.canExecute();
    }

    public boolean hasDexedEcjJar() {
        return hasClassesDex(ecjJar);
    }

    public boolean hasJavac() {
        return true;
    }









    public boolean hasNativeD8() {
        return d8Binary.exists() && d8Binary.canExecute();
    }

    public boolean hasDexedD8Jar() {
        return hasClassesDex(d8Jar);
    }

    public boolean hasD8() {
        return true;
    }

    public boolean hasNativeApksigner() {
        return apksignerBinary.exists() && apksignerBinary.canExecute();
    }

    public boolean hasDexedApksignerJar() {
        return hasClassesDex(apksignerJar);
    }

    public boolean hasApksigner() {
        return true;
    }

    public boolean hasZipalign() {
        return zipalignBinary.exists() && zipalignBinary.canExecute();
    }

    public boolean isFullNativePipelineReady(int compileSdk) {
        return hasAapt2()
                && hasJavac()
                && hasD8()
                && hasApksigner()
                && findAndroidJar(compileSdk).exists();
    }

    public List<ToolStatus> getStatusList(int compileSdk) {
        List<ToolStatus> statuses = new ArrayList<>();
        File androidJar = findAndroidJar(compileSdk);

        statuses.add(new ToolStatus("aapt2", aapt2Binary.getAbsolutePath(), hasAapt2()));

        String javacPath;
        if (hasNativeJavac()) {
            javacPath = javacBinary.getAbsolutePath();
        } else if (hasDexedEcjJar()) {
            javacPath = ecjJar.getAbsolutePath();
        } else if (ecjJar.exists()) {
            javacPath = "Built-in ECJ 3.33 Java 17 (external ecj.jar lacks classes.dex)";
        } else {
            javacPath = "Built-in ECJ 3.33 (Java 17 ready)";
        }
        statuses.add(new ToolStatus("javac / ECJ (Java 17)", javacPath, hasJavac()));

        String d8Path;
        if (hasNativeD8()) {
            d8Path = d8Binary.getAbsolutePath();
        } else if (hasDexedD8Jar()) {
            d8Path = d8Jar.getAbsolutePath();
        } else if (d8Jar.exists()) {
            d8Path = "Built-in R8/D8 8.7 (external d8.jar lacks classes.dex)";
        } else {
            d8Path = "Built-in R8/D8 8.7 (R8 Optimize + D8 ready)";
        }
        statuses.add(new ToolStatus("d8 / r8", d8Path, hasD8()));

        String apkSignerPath;
        if (hasNativeApksigner()) {
            apkSignerPath = apksignerBinary.getAbsolutePath();
        } else if (hasDexedApksignerJar()) {
            apkSignerPath = apksignerJar.getAbsolutePath();
        } else if (apksignerJar.exists()) {
            apkSignerPath = "Built-in ApkSigner (external apksigner.jar lacks classes.dex)";
        } else {
            apkSignerPath = "Built-in ApkSigner (ready)";
        }
        statuses.add(new ToolStatus("apksigner", apkSignerPath, hasApksigner()));
        statuses.add(new ToolStatus("zipalign (Release APK)", zipalignBinary.getAbsolutePath(), hasZipalign()));

        statuses.add(new ToolStatus(
                "android.jar (SDK " + compileSdk + ")",
                androidJar.getAbsolutePath(),
                androidJar.exists()));

        return statuses;
    }

    public static String describeBinaryType(File file) {
        if (file == null || !file.exists()) {
            return "missing";
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] hdr = new byte[20];
            int n = fis.read(hdr);
            if (n >= 4 && hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                int machine = (n >= 20) ? ((hdr[19] & 0xff) << 8) | (hdr[18] & 0xff) : -1;
                String arch;
                if (machine == 183) {
                    arch = "aarch64";
                } else if (machine == 40) {
                    arch = "arm32";
                } else if (machine == 62) {
                    arch = "x86_64";
                } else {
                    arch = "ELF";
                }
                return "ELF(" + arch + "), exec=" + file.canExecute();
            }
            if (n >= 2 && hdr[0] == '#' && hdr[1] == '!') {
                return "shell-script, exec=" + file.canExecute();
            }
            if (n >= 2 && hdr[0] == 'M' && hdr[1] == 'Z') {
                return "Windows PE(.exe) - incompatible with Android";
            }
        } catch (IOException ignored) {
        }
        return "unknown format, exec=" + file.canExecute();
    }

    /**
     * Copies engine files bundled in the APK (assets/sandrix_engine/{platforms,bin,lib}) into the
     * engine directory, so users do not have to import a toolchain zip by hand. Re-copies only
     * after the app was installed/updated. Safe to call on every launch from a background thread.
     */
    public static void installBundledEngine(Context context) {
        try {
            Context app = context.getApplicationContext();
            SandrixToolchain tc = new SandrixToolchain(app);
            long stamp = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).lastUpdateTime;
            File marker = new File(tc.engineRoot, ".bundled_engine_stamp");
            if (marker.isFile() && marker.length() > 0) {
                try (FileInputStream in = new FileInputStream(marker)) {
                    byte[] b = new byte[32];
                    int n = in.read(b);
                    if (n > 0 && Long.parseLong(new String(b, 0, n).trim()) == stamp
                            && tc.findAndroidJar(36).exists()) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            AssetManager am = app.getAssets();
            boolean copiedAny = false;
            for (String sub : new String[]{"platforms", "bin", "lib"}) {
                String[] top = am.list("sandrix_engine/" + sub);
                if (top == null || top.length == 0) continue;
                copyAssetTree(am, "sandrix_engine/" + sub, new File(tc.engineRoot, sub), !sub.equals("platforms"));
                copiedAny = true;
            }
            if (copiedAny) {
                try (FileOutputStream out = new FileOutputStream(marker)) {
                    out.write(String.valueOf(stamp).getBytes("UTF-8"));
                }
            }
        } catch (Throwable ignored) {
            // Best effort: the user can still import a toolchain zip from the Toolchain dialog.
        }
    }

    private static void copyAssetTree(AssetManager am, String assetPath, File dest, boolean executable)
            throws IOException {
        String[] children = am.list(assetPath);
        if (children != null && children.length > 0) {
            mkdirsSafe(dest);
            chmodSafe(dest, 0755);
            for (String child : children) {
                copyAssetTree(am, assetPath + "/" + child, new File(dest, child), executable);
            }
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null) mkdirsSafe(parent);
        File tmp = new File(dest.getPath() + ".tmp");
        try (InputStream is = am.open(assetPath); FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
        if (dest.exists() && !dest.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot replace " + dest);
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("Cannot install " + dest);
        }
        chmodSafe(dest, executable ? 0755 : 0644);
    }

    public int importToolchainZip(Uri zipUri) throws IOException {
        ensureDirectories();
        int count = 0;
        String canonicalRoot = engineRoot.getCanonicalPath() + File.separator;

        try (InputStream is = appContext.getContentResolver().openInputStream(zipUri)) {
            if (is == null) {
                throw new IOException("Unable to open toolchain archive.");
            }
            try (ZipInputStream zis = new ZipInputStream(is)) {
                byte[] buffer = new byte[8192];
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || name.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }
                    if (name.startsWith("sandrix_engine/")) {
                        name = name.substring("sandrix_engine/".length());
                    }
                    if (name.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }

                    File outFile = new File(engineRoot, name);
                    String canonicalOut = outFile.getCanonicalPath();
                    if (!canonicalOut.startsWith(canonicalRoot)) {
                        throw new IOException("Blocked unsafe zip entry: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        mkdirsSafe(outFile);
                        chmodSafe(outFile, 0755);
                    } else {
                        File parent = outFile.getParentFile();
                        if (parent != null) {
                            mkdirsSafe(parent);
                            chmodSafe(parent, 0755);
                        }
                        if (outFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            outFile.delete();
                        }
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            int len;
                            while ((len = zis.read(buffer)) != -1) {
                                fos.write(buffer, 0, len);
                            }
                        }
                        String normalized = name.replace('\\', '/');
                        if (normalized.startsWith("bin/")
                                || normalized.startsWith("lib/")
                                || normalized.endsWith(".so")) {
                            chmodSafe(outFile, 0755);
                        } else if (normalized.endsWith(".jar")) {
                            chmodSafe(outFile, 0444);
                        } else {
                            chmodSafe(outFile, 0644);
                        }
                        count++;
                    }
                    zis.closeEntry();
                }
            }
        }
        return count;
    }

    public static final class ToolStatus {
        public final String name;
        public final String path;
        public final boolean available;

        public ToolStatus(String name, String path, boolean available) {
            this.name = name;
            this.path = path;
            this.available = available;
        }
    }
}
