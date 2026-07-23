package com.teamundefined.defined;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Measures where your loop time actually goes, section by section.
 *
 * <p>On a Control Hub the difference between a 6 ms and a 12 ms loop decides matches, and
 * guessing which subsystem costs what is usually wrong. Wrap the suspects and read the
 * numbers:
 *
 * <pre>{@code
 * enum Section { ROBOT_UPDATE, RUNNER_UPDATE, TELEMETRY }
 *
 * SectionProfiler<Section> profiler = new SectionProfiler<>(Config.PROFILER_ON);
 *
 * profiler.time(Section.ROBOT_UPDATE, () -> robot.update());
 * profiler.time(Section.RUNNER_UPDATE, () -> runner.update());
 *
 * telemetry.addLine(profiler.getFormattedStats());
 * }</pre>
 *
 * <p>Averages come from a rolling window of the last {@value #WINDOW_SIZE} calls, so the
 * numbers track current behavior instead of being dragged around by startup outliers.
 * Min/max/total are cumulative.
 *
 * <p>When constructed with {@code enabled == false} every method short-circuits —
 * {@link #time} still runs your code, it just does not measure it — so profiling calls can
 * be left in competition code at effectively zero cost.
 *
 * <p>Timing uses {@link System#nanoTime()}. Not thread-safe; profile from the loop thread.
 */
public class SectionProfiler<T> {

    private static final int WINDOW_SIZE = 100; // rolling window for averages
    private static final String ADB_TAG = "FTC-Profiler";
    private static final long DEFAULT_LOG_INTERVAL_MS = 2000;

    /** Timing statistics for one section. */
    public class Section {
        public final T name;
        public long startTime = 0;
        public final long[] timingWindow = new long[WINDOW_SIZE];
        private int windowIndex = 0;
        private long totalTime = 0;
        private long callCount = 0;
        private long minTime = Long.MAX_VALUE;
        private long maxTime = 0;
        private boolean inProgress = false;

        Section(T name) {
            this.name = name;
        }

        public void start() {
            this.startTime = System.nanoTime();
            this.inProgress = true;
        }

        /** @return elapsed nanoseconds, or 0 if this section was not started */
        public long stop() {
            if (!this.inProgress) return 0;

            long elapsed = System.nanoTime() - this.startTime;

            this.timingWindow[this.windowIndex] = elapsed;
            this.windowIndex = (this.windowIndex + 1) % WINDOW_SIZE;

            totalTime += elapsed;
            callCount++;

            if (elapsed < this.minTime) this.minTime = elapsed;
            if (elapsed > this.maxTime) maxTime = elapsed;

            inProgress = false;
            return elapsed;
        }

        /** Rolling-window mean in milliseconds. */
        public double getAverageMs() {
            if (callCount == 0) return 0;

            long sum = 0;
            int count = 0;
            int windowCount = (int) Math.min(callCount, WINDOW_SIZE);

            for (int i = 0; i < windowCount; i++) {
                long time = timingWindow[i];
                if (time > 0) {
                    sum += time;
                    count++;
                }
            }

            if (count > 0) return ((double) sum / count) / 1_000_000.0;

            return ((double) totalTime / callCount) / 1_000_000.0; // fallback
        }

        public double getTotalMs() {
            if (callCount == 0) return 0;
            return totalTime / 1_000_000.0;
        }

        public double getMinMs() { return callCount == 0 ? 0 : minTime / 1_000_000.0; }

        public double getMaxMs() { return maxTime / 1_000_000.0; }

        public long getCallCount() { return callCount; }

        public void reset() {
            startTime = 0;
            windowIndex = 0;
            totalTime = 0;
            callCount = 0;
            minTime = Long.MAX_VALUE;
            maxTime = 0;
            inProgress = false;
            Arrays.fill(timingWindow, 0);
        }
    }

    /**
     * Where a profiler report is written. Implemented by {@code TelemetryReport} in
     * {@code defined-ftc} for FTC {@code Telemetry}; implement it yourself for anything else.
     */
    public interface Report {
        void line(String text);
        void data(String key, String value);
    }

    private final boolean enabled;
    private final long adbLogIntervalMs;
    // Insertion-ordered so reports read in the order sections were first timed.
    private final Map<T, Section> sections = new LinkedHashMap<>();
    private long profilingStartTime;
    private long lastAdbLogTime = 0;

    /** A profiler that logs to logcat at most every {@value #DEFAULT_LOG_INTERVAL_MS} ms. */
    public SectionProfiler(boolean enabled) {
        this(enabled, DEFAULT_LOG_INTERVAL_MS);
    }

    /**
     * @param enabled          when false, every operation is a no-op
     * @param adbLogIntervalMs minimum gap between {@link #logToAdb()} emissions
     */
    public SectionProfiler(boolean enabled, long adbLogIntervalMs) {
        this.enabled = enabled;
        this.adbLogIntervalMs = adbLogIntervalMs;
        if (enabled) profilingStartTime = System.nanoTime();
    }

    public void start(T name) {
        if (!enabled) return;
        findOrCreateSection(name).start();
    }

    /** @return elapsed nanoseconds, or 0 when disabled */
    public long stop(T name) {
        if (!enabled) return 0;
        return findOrCreateSection(name).stop();
    }

    /**
     * Times {@code runnable}. When disabled the code still runs, untimed.
     *
     * @return elapsed nanoseconds, or 0 when disabled
     */
    public long time(T name, Runnable runnable) {
        if (!enabled) {
            runnable.run();
            return 0;
        }

        long elapsed;
        start(name);
        try {
            runnable.run();
        } finally {
            // Assign, don't return, from finally: a `return` here would swallow any
            // exception the runnable threw.
            elapsed = stop(name);
        }
        return elapsed;
    }

    /** Full multi-line report: per-section average, share of measured time, min and max. */
    public void displayTelemetry(Report report) {
        if (!enabled || sections.isEmpty()) return;

        report.line("=== Performance Profile ===");

        long totalMeasured = 0;
        for (Section s : sections.values()) totalMeasured += s.totalTime;

        for (Section s : sections.values()) {
            if (s.callCount > 0) {
                double percentage = (totalMeasured > 0) ? (s.totalTime * 100.0) / totalMeasured : 0;
                report.data(String.valueOf(s.name),
                        String.format(Locale.US, "%.2fms (%.1f%%) [min:%.2f max:%.2f]",
                                s.getAverageMs(), percentage, s.getMinMs(), s.getMaxMs()));
            }
        }

        double totalSeconds = (System.nanoTime() - profilingStartTime) / 1_000_000_000.0;
        report.data("Profiling Time", String.format(Locale.US, "%.1fs", totalSeconds));
    }

    /** One-line summary: {@code section1:1.2 | section2:0.5}. */
    public void displayCompact(Report report) {
        if (!enabled || sections.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (Section s : sections.values()) {
            if (s.callCount > 0) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(String.format(Locale.US, "%s:%.1f", s.name, s.getAverageMs()));
            }
        }
        if (sb.length() > 0) report.line(sb.toString());
    }

    /**
     * Emits one compact line to logcat, rate-limited to the configured interval so it can be
     * called every loop. Lets you watch loop timing over {@code adb logcat} while driving.
     */
    public void logToAdb() {
        if (!enabled || sections.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastAdbLogTime < adbLogIntervalMs) return;
        lastAdbLogTime = now;

        StringBuilder logMsg = new StringBuilder("[PROFILER] ");
        int pos = 0;
        for (Section s : sections.values()) {
            if (s.callCount > 0) {
                if (pos > 0) logMsg.append(", ");
                logMsg.append(String.format(Locale.US, "%s: %.2fms", s.name, s.getAverageMs()));
                pos++;
            }
        }
        Log.i(ADB_TAG, logMsg.toString());
    }

    /** {@code "section1: 1.2ms | section2: 0.5ms | Total: 1.7ms"}. */
    public String getFormattedStats() {
        if (!enabled || sections.isEmpty()) return "Profiling disabled";

        StringBuilder sb = new StringBuilder();
        double totalMs = 0;

        for (Section s : sections.values()) {
            if (s.callCount > 0) {
                double avgMs = s.getAverageMs();
                totalMs += avgMs;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(String.format(Locale.US, "%s: %.1fms", s.name, avgMs));
            }
        }

        if (sb.length() > 0) sb.append(String.format(Locale.US, " | Total: %.1fms", totalMs));

        return sb.toString();
    }

    public void reset() {
        if (!enabled) return;
        for (Section s : sections.values()) s.reset();
        profilingStartTime = System.nanoTime();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return the section for {@code name}, or {@code null} if it was never timed */
    public Section getSection(T name) {
        return sections.get(name);
    }

    private Section findOrCreateSection(T name) {
        Section section = sections.get(name);
        if (section == null) {
            section = new Section(name);
            sections.put(name, section);
        }
        return section;
    }
}
