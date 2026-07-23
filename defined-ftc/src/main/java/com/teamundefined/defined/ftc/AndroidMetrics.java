package com.teamundefined.defined.ftc;

import android.os.Debug;

import com.teamundefined.defined.SystemMonitor;

import java.util.Map;

/**
 * Android-only metrics for a {@link SystemMonitor} — CPU share, GC activity, native heap and
 * allocation counters. These need {@code android.os.Debug}, which is why they live here rather
 * than in the platform-independent core.
 *
 * <pre>{@code
 * SystemMonitor monitor = new SystemMonitor(0.8);
 * monitor.addStandardMetrics();      // memory + cycle time (core)
 * AndroidMetrics.addCpu(monitor);    // CPU %          (this class)
 * AndroidMetrics.addGcCount(monitor);
 * }</pre>
 *
 * <p>All of these report <b>deltas since first sample</b>, not absolute device totals, so the
 * numbers describe your OpMode rather than everything the phone has ever done.
 */
public final class AndroidMetrics {

    private static final double BYTES_TO_MB = 1.0 / (1024 * 1024);

    private AndroidMetrics() {}

    /**
     * CPU time consumed by this thread as a percentage of wall-clock time, sampled every
     * 200 ms. Above ~100% means the loop is saturating a core.
     */
    public static void addCpu(SystemMonitor monitor) {
        // Arrays so the lambda can mutate the carried-over baseline.
        final long[] startCpuTime = {0};
        final long[] startWallTime = {0};

        monitor.addMetric("🧮", now -> {
            long cpuTime = Debug.threadCpuTimeNanos();
            long wallTime = System.nanoTime();

            if (startCpuTime[0] == 0) {
                startCpuTime[0] = cpuTime;
                startWallTime[0] = wallTime;
                return 0.0;
            }

            long cpuDelta = cpuTime - startCpuTime[0];
            long wallDelta = wallTime - startWallTime[0];

            if (wallDelta > 0) {
                double cpuPercent = ((double) cpuDelta / wallDelta) * 100.0;
                startCpuTime[0] = cpuTime;
                startWallTime[0] = wallTime;
                return cpuPercent;
            }
            return 0.0;
        }, 200L, SystemMonitor.MetricType.PERCENTAGE);
    }

    /** Number of garbage collections since the first sample, checked every second. */
    public static void addGcCount(SystemMonitor monitor) {
        final String gcCountKey = "art.gc.gc-count";
        final int[] startGcCount = {-1};

        monitor.addMetric("GC", now -> {
            Map<String, String> stats = Debug.getRuntimeStats();
            String gcStr = stats.get(gcCountKey);
            if (gcStr != null) {
                try {
                    int currentCount = Integer.parseInt(gcStr);
                    if (startGcCount[0] == -1) {
                        startGcCount[0] = currentCount;
                        return 0.0;
                    }
                    return (double) (currentCount - startGcCount[0]);
                } catch (NumberFormatException ignored) {
                    // stat missing or malformed on this device
                }
            }
            return 0.0;
        }, 1000L, SystemMonitor.MetricType.COUNTER);
    }

    /** Milliseconds spent in GC since the first sample, checked every second. */
    public static void addGcTime(SystemMonitor monitor) {
        final String gcTimeKey = "art.gc.gc-time";
        final long[] startGcTimeMs = {-1};

        monitor.addMetric("GCTime", now -> {
            Map<String, String> stats = Debug.getRuntimeStats();
            String gcTimeStr = stats.get(gcTimeKey);
            if (gcTimeStr != null) {
                try {
                    long currentTimeMs = Long.parseLong(gcTimeStr) / 1_000_000; // ns -> ms
                    if (startGcTimeMs[0] == -1) {
                        startGcTimeMs[0] = currentTimeMs;
                        return 0.0;
                    }
                    return (double) (currentTimeMs - startGcTimeMs[0]);
                } catch (NumberFormatException ignored) {
                    // stat missing or malformed on this device
                }
            }
            return 0.0;
        }, 1000L, SystemMonitor.MetricType.NUMBER);
    }

    /** Native heap in use (MB) — steadier than the Java heap, sampled every 100 ms. */
    public static void addNativeMemory(SystemMonitor monitor) {
        monitor.addMetric("Native", now ->
                Debug.getNativeHeapAllocatedSize() * BYTES_TO_MB, 100L, SystemMonitor.MetricType.MB);
    }

    /**
     * Thousands of object allocations since the first sample. Enables allocation counting,
     * which is a debugging aid — leave it off in competition builds.
     */
    public static void addAllocationCount(SystemMonitor monitor) {
        try {
            Debug.startAllocCounting();
        } catch (Exception ignored) {
            // Not supported on every device/ROM; the counter then simply stays flat.
        }

        final int[] startAllocCount = {-1};

        monitor.addMetric("Allocs", now -> {
            int currentCount = Debug.getGlobalAllocCount();
            if (startAllocCount[0] == -1) {
                startAllocCount[0] = currentCount;
                return 0.0;
            }
            return (currentCount - startAllocCount[0]) / 1000.0;
        }, 50L, SystemMonitor.MetricType.NUMBER);
    }

    /** Total size of allocations since the first sample, in MB. */
    public static void addAllocationSize(SystemMonitor monitor) {
        final int[] startAllocSize = {-1};

        monitor.addMetric("AllocMB", now -> {
            int currentSize = Debug.getGlobalAllocSize();
            if (startAllocSize[0] == -1) {
                startAllocSize[0] = currentSize;
                return 0.0;
            }
            return (currentSize - startAllocSize[0]) * BYTES_TO_MB;
        }, 50L, SystemMonitor.MetricType.MB);
    }

    /** Allocations per second, sampled every 100 ms. */
    public static void addAllocationRate(SystemMonitor monitor) {
        final int[] lastAllocCount = {-1};
        final long[] lastTime = {0};

        monitor.addMetric("AllocRate", now -> {
            int currentCount = Debug.getGlobalAllocCount();

            if (lastAllocCount[0] == -1) {
                lastAllocCount[0] = currentCount;
                lastTime[0] = now;
                return 0.0;
            }

            long timeDelta = now - lastTime[0];
            if (timeDelta > 0) {
                double rate = ((currentCount - lastAllocCount[0]) * 1000.0) / timeDelta;
                lastAllocCount[0] = currentCount;
                lastTime[0] = now;
                return rate;
            }
            return 0.0;
        }, 100L, SystemMonitor.MetricType.NUMBER);
    }

    /** Every runtime stat the device exposes, as {@code key = value} lines. Debugging aid. */
    public static String getAvailableStatKeys() {
        Map<String, String> stats = Debug.getRuntimeStats();
        StringBuilder sb = new StringBuilder("Runtime stats keys:\n");
        for (Map.Entry<String, String> e : stats.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}
