package com.example.sandrix.logcat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams real-time Android {@code logcat} output across emulators AND physical OEM phones.
 *
 * <p>Physical phones vary widely in {@code logcat} CLI flags and SELinux permissions on {@code logd}.
 * This reader cascades through multiple streaming strategies ({@code --pid} -> UID-scoped {@code -v time}
 * -> {@code -v threadtime} -> {@code -v brief} -> periodic {@code -d} dump polling) while
 * seamlessly combining live {@link SandrixLogcatBus} events so Logcat remains functional even on
 * locked-down OEM builds.</p>
 */
public final class LogcatStreamReader {

    public interface Listener {
        void onLogLine(LogEntry entry);
        void onStreamError(String message);
    }

    public static final class LogEntry {
        public final String timestamp;
        public final String level;
        public final String tag;
        public final String message;
        public final String raw;

        public LogEntry(String timestamp, String level, String tag, String message, String raw) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.raw = raw;
        }
    }

    // Matches -v time: "11-13 21:14:21.123 D/Tag( 1234): Message" or "11-13 21:14:21.123 D/Tag (1234): Message"
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "^\\d{2}-\\d{2}\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEFA])/([^(:]+?)(?:\\(\\s*\\d+\\))?:\\s*(.*)$");

    // Matches -v threadtime: "11-13 21:14:21.123  1234  5678 D Tag     : Message"
    private static final Pattern THREADTIME_PATTERN = Pattern.compile(
            "^\\d{2}-\\d{2}\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEFA])\\s+([^:]+?):\\s*(.*)$");

    // Matches -v brief: "D/Tag( 1234): Message"
    private static final Pattern BRIEF_PATTERN = Pattern.compile(
            "^([VDIWEFA])/([^(:]+?)(?:\\(\\s*\\d+\\))?:\\s*(.*)$");

    private static final int MAX_SEEN_RAW = 600;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> seenLines = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile Process activeProcess;
    private volatile boolean running = false;
    private volatile SandrixLogcatBus.BusListener activeBusListener;

    public synchronized void start(Listener listener) {
        if (running || listener == null) return;
        running = true;

        // Subscribe to in-process SandrixLogcatBus for guaranteed phone coverage
        List<LogEntry> snapshot = SandrixLogcatBus.getSnapshot();
        for (LogEntry entry : snapshot) {
            seenLines.add(entry.raw);
            mainHandler.post(() -> listener.onLogLine(entry));
        }

        activeBusListener = entry -> {
            if (!running) return;
            rememberSeen(entry.raw);
            mainHandler.post(() -> listener.onLogLine(entry));
        };
        SandrixLogcatBus.addListener(activeBusListener);

        executor.execute(() -> streamLoop(listener));
    }

    public synchronized void stop() {
        running = false;
        if (activeBusListener != null) {
            SandrixLogcatBus.removeListener(activeBusListener);
            activeBusListener = null;
        }
        if (activeProcess != null) {
            activeProcess.destroy();
            activeProcess = null;
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    private void streamLoop(Listener listener) {
        int myPid = 0;
        try {
            myPid = android.os.Process.myPid();
            Log.i("SandrixIDE", "Sandrix Logcat stream started (PID " + myPid + ")");
        } catch (Throwable ignored) {
        }

        String logcatBin = resolveLogcatBinary();

        // Strategy 1..4: persistent logcat streaming commands
        // Note: on Android 4.1+, logcat run by an app process is automatically UID-scoped by logd,
        // so omitting --pid= avoids syntax rejections on OEM ROMs where --pid fails!
        List<List<String>> streamCommands = Arrays.asList(
                Arrays.asList(logcatBin, "-v", "time", "--pid=" + myPid),
                Arrays.asList(logcatBin, "-v", "time"),
                Arrays.asList(logcatBin, "-v", "threadtime"),
                Arrays.asList(logcatBin, "-v", "brief")
        );

        for (List<String> cmd : streamCommands) {
            if (!running) return;
            if (tryStreamingCommand(cmd, listener)) {
                // If it streamed validly for a while and exited cleanly on stop(), we're done.
                if (!running) return;
            }
        }

        // Strategy 5: Dump polling (-d) for OEM phones that allow snapshots but kill persistent logdr sockets
        List<List<String>> dumpCommands = Arrays.asList(
                Arrays.asList(logcatBin, "-d", "-v", "time", "-t", "150"),
                Arrays.asList(logcatBin, "-d", "-v", "time", "-t", "300"),
                Arrays.asList(logcatBin, "-d", "-v", "threadtime", "-t", "300")
        );

        List<String> workingDumpCmd = null;
        for (List<String> cmd : dumpCommands) {
            if (!running) return;
            if (runDumpOnce(cmd, listener)) {
                workingDumpCmd = cmd;
                break;
            }
        }

        if (workingDumpCmd != null) {
            while (running) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!running) break;
                runDumpOnce(workingDumpCmd, listener);
            }
            return;
        }

        // Fallback: Phone restricts /system/bin/logcat completely via SELinux/MIUI settings.
        // Keep running via SandrixLogcatBus rather than crashing or showing an error bar.
        if (running) {
            SandrixLogcatBus.log("I", "Logcat",
                    "Device restricted system logcat socket; live in-app & build logcat active.");
        }
    }

    private boolean tryStreamingCommand(List<String> cmd, Listener listener) {
        int validLines = 0;
        long startMs = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            activeProcess = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("---------")) {
                        continue;
                    }
                    if (isLogcatFailureLine(trimmed)) {
                        // This flag or socket access failed on this phone; fall back immediately.
                        break;
                    }
                    validLines++;
                    dispatchIfNew(trimmed, listener);
                }
            }
        } catch (IOException ignored) {
            return false;
        } finally {
            if (activeProcess != null) {
                try {
                    activeProcess.destroy();
                } catch (Throwable ignored) {
                }
                activeProcess = null;
            }
        }
        long elapsed = System.currentTimeMillis() - startMs;
        // Consider successful only if it actually streamed lines or ran beyond startup failure window
        return validLines > 0 || elapsed > 3000;
    }

    private boolean runDumpOnce(List<String> cmd, Listener listener) {
        int validLines = 0;
        boolean sawFailure = false;
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            activeProcess = proc;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("---------")) {
                        continue;
                    }
                    if (isLogcatFailureLine(trimmed)) {
                        sawFailure = true;
                        break;
                    }
                    validLines++;
                    dispatchIfNew(trimmed, listener);
                }
            }
            int exit = proc.waitFor();
            return !sawFailure && exit == 0 && validLines > 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            activeProcess = null;
        }
    }

    private void dispatchIfNew(String rawLine, Listener listener) {
        if (!rememberSeen(rawLine)) {
            return;
        }
        LogEntry parsed = parseLine(rawLine);
        mainHandler.post(() -> listener.onLogLine(parsed));
    }

    private boolean rememberSeen(String rawLine) {
        synchronized (seenLines) {
            if (seenLines.contains(rawLine)) {
                return false;
            }
            seenLines.add(rawLine);
            if (seenLines.size() > MAX_SEEN_RAW) {
                Iterator<String> it = seenLines.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            return true;
        }
    }

    private static String resolveLogcatBinary() {
        File sysBin = new File("/system/bin/logcat");
        if (sysBin.exists()) return sysBin.getAbsolutePath();
        File xbin = new File("/system/xbin/logcat");
        if (xbin.exists()) return xbin.getAbsolutePath();
        return "logcat";
    }

    private static boolean isLogcatFailureLine(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("invalid option")
                || lower.contains("unrecognized option")
                || lower.contains("permission denied")
                || lower.contains("unexpected eof")
                || lower.contains("unable to open log device")
                || lower.contains("failed to open")
                || lower.contains("logcat read failure")
                || lower.contains("operation not permitted");
    }

    public static LogEntry parseLine(String raw) {
        if (raw == null) {
            return new LogEntry("--:--:--", "I", "Sandrix", "", "");
        }
        String trimmed = raw.trim();

        Matcher timeMatcher = TIME_PATTERN.matcher(trimmed);
        if (timeMatcher.matches()) {
            return new LogEntry(
                    timeMatcher.group(1),
                    timeMatcher.group(2),
                    timeMatcher.group(3).trim(),
                    timeMatcher.group(4),
                    raw);
        }

        Matcher threadtimeMatcher = THREADTIME_PATTERN.matcher(trimmed);
        if (threadtimeMatcher.matches()) {
            return new LogEntry(
                    threadtimeMatcher.group(1),
                    threadtimeMatcher.group(2),
                    threadtimeMatcher.group(3).trim(),
                    threadtimeMatcher.group(4),
                    raw);
        }

        Matcher briefMatcher = BRIEF_PATTERN.matcher(trimmed);
        if (briefMatcher.matches()) {
            return new LogEntry(
                    "--:--:--",
                    briefMatcher.group(1),
                    briefMatcher.group(2).trim(),
                    briefMatcher.group(3),
                    raw);
        }

        String lvl = trimmed.contains("ERROR") || trimmed.contains("FATAL") || trimmed.contains("Exception")
                ? "E"
                : (trimmed.contains("WARN") ? "W" : "I");
        return new LogEntry("--:--:--", lvl, "Sandrix", raw, raw);
    }
}
