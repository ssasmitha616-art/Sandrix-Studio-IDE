package com.example.sandrix.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Android API 24-32 compatibility backports for Java 11+ standard library methods
 * invoked by bundled toolchain libraries (ECJ 3.33.0, R8 8.7.18, JGit 6.10).
 *
 * <p>Bytecode calls to {@code InputStream.readAllBytes}, {@code InputStream.readNBytes},
 * {@code ByteArrayOutputStream.toString(Charset)}, {@code Arrays.compare(char[], char[])},
 * {@code Arrays.equals(byte[], ...)}, and {@code Path.of(URI)} are rewritten at build time
 * to invoke these static helpers, preventing {@link NoSuchMethodError} on Android 12 (API 31)
 * and earlier.</p>
 */
public final class JdkBackports {

    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    private JdkBackports() {}

    public static byte[] readAllBytes(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        return readNBytes(in, Integer.MAX_VALUE);
    }

    public static byte[] readNBytes(InputStream in, int len) throws IOException {
        Objects.requireNonNull(in, "in");
        if (len < 0) {
            throw new IllegalArgumentException("len < 0");
        }
        if (len == 0) {
            return new byte[0];
        }
        byte[] buf = new byte[Math.min(DEFAULT_BUFFER_SIZE, len)];
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(DEFAULT_BUFFER_SIZE, len));
        int remaining = len;
        while (remaining > 0) {
            int toRead = Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n < 0) {
                break;
            }
            if (n > 0) {
                out.write(buf, 0, n);
                remaining -= n;
                if (out.size() > MAX_BUFFER_SIZE && remaining > 0) {
                    throw new OutOfMemoryError("Required array size too large");
                }
            }
        }
        return out.toByteArray();
    }

    public static int readNBytes(InputStream in, byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(b, "b");
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException("off=" + off + ", len=" + len + ", length=" + b.length);
        }
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                break;
            }
            n += count;
        }
        return n;
    }

    public static String toString(ByteArrayOutputStream baos, Charset charset) {
        Objects.requireNonNull(baos, "baos");
        Objects.requireNonNull(charset, "charset");
        return new String(baos.toByteArray(), charset);
    }

    public static int compareChars(char[] a, char[] b) {
        if (a == b) {
            return 0;
        }
        if (a == null || b == null) {
            return a == null ? -1 : 1;
        }
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Character.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return a.length - b.length;
    }

    public static boolean equalsBytes(
            byte[] a, int aFromIndex, int aToIndex,
            byte[] b, int bFromIndex, int bToIndex) {
        rangeCheck(a.length, aFromIndex, aToIndex);
        rangeCheck(b.length, bFromIndex, bToIndex);
        int aLength = aToIndex - aFromIndex;
        int bLength = bToIndex - bFromIndex;
        if (aLength != bLength) {
            return false;
        }
        for (int i = 0; i < aLength; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) {
                return false;
            }
        }
        return true;
    }

    public static Path pathOf(URI uri) {
        return Paths.get(uri);
    }

    private static void rangeCheck(int arrayLength, int fromIndex, int toIndex) {
        if (fromIndex > toIndex) {
            throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
        }
        if (fromIndex < 0) {
            throw new ArrayIndexOutOfBoundsException(fromIndex);
        }
        if (toIndex > arrayLength) {
            throw new ArrayIndexOutOfBoundsException(toIndex);
        }
    }
}
