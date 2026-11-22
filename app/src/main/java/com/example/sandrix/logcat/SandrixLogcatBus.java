package com.example.sandrix.logcat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process log capture bus for Sandrix Studio IDE.
 *
 * <p>Guarantees live log visibility in the Logcat tab on ALL physical Android phones,
 * including OEM ROMs (MIUI, HyperOS, ColorOS, OneUI, Android 14+ user builds) where
 * {@code /system/bin/logcat} socket access is restricted or throttled by SELinux.</p>
 */
public final class SandrixLogcatBus {

    public interface BusListener {
        void onBusEntry(LogcatStreamReader.LogEntry entry);
    }

    private static final int MAX_BUFFER = 400;
    private static final Deque<LogcatStreamReader.LogEntry> BUFFER = new ArrayDeque<>(MAX_BUFFER);
    private static final CopyOnWriteArrayList<BusListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);

    private static final ThreadLocal<SimpleDateFormat> TIME_FMT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        }
    };

    private SandrixLogcatBus() {}

    public static void installHooks() {
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            PrintStream origOut = System.out;
            PrintStream origErr = System.err;
            System.setOut(new PrintStream(new TeeLineOutputStream(origOut, "I", "System.out"), true, "UTF-8"));
            System.setErr(new PrintStream(new TeeLineOutputStream(origErr, "E", "System.err"), true, "UTF-8"));
        } catch (Throwable ignored) {
        }

        try {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                log("E", "CrashHandler", "Uncaught exception on thread " + t.getName() + ": " + e);
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void log(String level, String tag, String message) {
        if (message == null || message.trim().isEmpty()) return;
        String lvl = (level != null && !level.isEmpty()) ? level : "I";
        String tg = (tag != null && !tag.isEmpty()) ? tag : "Sandrix";
        String ts = currentTimestamp();
        String raw = ts + " " + lvl + "/" + tg + ": " + message;
        LogcatStreamReader.LogEntry entry = new LogcatStreamReader.LogEntry(ts, lvl, tg, message, raw);

        synchronized (BUFFER) {
            while (BUFFER.size() >= MAX_BUFFER) {
                BUFFER.pollFirst();
            }
            BUFFER.addLast(entry);
        }

        for (BusListener listener : LISTENERS) {
            try {
                listener.onBusEntry(entry);
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<LogcatStreamReader.LogEntry> getSnapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }

    public static void addListener(BusListener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(BusListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private static String currentTimestamp() {
        SimpleDateFormat fmt = TIME_FMT.get();
        return fmt != null ? fmt.format(new Date()) : "--:--:--";
    }

    private static final class TeeLineOutputStream extends OutputStream {
        private final PrintStream delegate;
        private final String level;
        private final String tag;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

        TeeLineOutputStream(PrintStream delegate, String level, String tag) {
            this.delegate = delegate;
            this.level = level;
            this.tag = tag;
        }

        @Override
        public synchronized void write(int b) {
            delegate.write(b);
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                lineBuffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            delegate.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                byte ch = b[i];
                if (ch == '\n') {
                    flushLine();
                } else if (ch != '\r') {
                    lineBuffer.write(ch);
                }
            }
        }

        @Override
        public synchronized void flush() {
            delegate.flush();
        }

        private void flushLine() {
            if (lineBuffer.size() > 0) {
                String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8).trim();
                lineBuffer.reset();
                if (!line.isEmpty()) {
                    SandrixLogcatBus.log(level, tag, line);
                }
            }
        }
    }
}
