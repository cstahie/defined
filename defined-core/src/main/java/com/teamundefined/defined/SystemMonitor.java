package com.teamundefined.defined;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Tracks named runtime metrics — loop time, memory, GC pauses, anything you can express as a
 * number — with optional exponential smoothing and per-metric sample intervals.
 *
 * <p>Where {@link SectionProfiler} answers "which part of my loop is slow", this answers
 * "is the robot healthy" — is memory climbing, is the GC pausing us, is cycle time drifting.
 *
 * <pre>{@code
 * SystemMonitor monitor = new SystemMonitor(0.8);
 * monitor.addMemoryMetric();
 * monitor.addCycleMetric();
 * monitor.addMetric("battery", now -> voltageSensor.getVoltage(),
 *                   500L, SystemMonitor.MetricType.NUMBER);
 *
 * // once per loop:
 * monitor.update(System.currentTimeMillis());
 * telemetry.addLine(monitor.getFormattedStats());
 * }</pre>
 *
 * <p>Metrics that need Android APIs (CPU time, GC counts, native heap, allocation counters)
 * live in {@code AndroidMetrics} in the {@code defined-ftc} module, which adds them to a
 * monitor built here.
 *
 * <p>A metric with an update interval is sampled at most that often, so an expensive provider
 * can be registered without paying for it every loop. Not thread-safe; drive it from the loop.
 */
public class SystemMonitor {

    /** How a metric's value is rendered by {@link #getFormattedStats()}. */
    public enum MetricType {
        PERCENTAGE,  // XX.XX%
        MB,          // XX.XMB
        COUNTER,     // integer
        NUMBER       // XX.XX
    }

    /** Supplies a metric's current value; receives the timestamp passed to {@link #update}. */
    @FunctionalInterface
    public interface MetricProvider {
        double getValue(long now);
    }

    /** One tracked metric and its smoothing/sampling state. */
    public static class Metric {
        private final String name;
        private final MetricProvider provider;
        private final boolean useSmoothing;
        private final double alpha;
        private long updateIntervalMs;
        private final MetricType type;

        private double currentValue = 0;
        private double smoothedValue = 0;
        private long lastUpdateTime = 0;
        private double rate = 0;

        private boolean calculateRate = false;
        private double lastRawValue = 0;

        public Metric(String name, MetricProvider provider, boolean useSmoothing,
                      double alpha, long updateIntervalMs, MetricType type) {
            this.name = name;
            this.provider = provider;
            this.useSmoothing = useSmoothing;
            this.alpha = alpha;
            this.updateIntervalMs = updateIntervalMs;
            this.type = type;
        }

        public Metric(String name, MetricProvider provider, boolean useSmoothing,
                      double alpha, MetricType type) {
            this(name, provider, useSmoothing, alpha, 0, type); // sample every call
        }

        /**
         * Treat the provider's output as a running total and report its per-second rate of
         * change instead of the raw value. For cumulative counters like CPU time.
         */
        public Metric withRateCalculation() {
            this.calculateRate = true;
            return this;
        }

        /** Sample at most once per {@code intervalMs}. */
        public Metric withUpdateInterval(long intervalMs) {
            this.updateIntervalMs = intervalMs;
            return this;
        }

        public void update(long now) {
            if (updateIntervalMs > 0 && lastUpdateTime > 0) {
                if (now - lastUpdateTime < updateIntervalMs) {
                    return; // not due yet
                }
            }

            double rawValue = provider.getValue(now);

            if (calculateRate && lastUpdateTime > 0) {
                double timeDelta = (now - lastUpdateTime) / 1000.0; // seconds
                if (timeDelta > 0) {
                    rate = (rawValue - lastRawValue) / timeDelta;
                    currentValue = rate;
                }
                lastRawValue = rawValue;
            } else {
                currentValue = rawValue;
            }

            if (useSmoothing) {
                // Seed on the first sample so the average doesn't crawl up from zero.
                if (smoothedValue == 0) {
                    smoothedValue = currentValue;
                } else {
                    smoothedValue = smoothedValue * alpha + currentValue * (1 - alpha);
                }
            } else {
                smoothedValue = currentValue;
            }

            lastUpdateTime = now;
        }

        /** Smoothed value when smoothing is on, else the latest sample. */
        public double getValue() {
            return useSmoothing ? smoothedValue : currentValue;
        }

        public double getInstantValue() { return currentValue; }

        public double getRate() { return rate; }

        public MetricType getType() { return type; }

        public String getName() { return name; }
    }

    private final List<Metric> metrics = new ArrayList<>();
    private final double defaultAlpha;
    private boolean useSmoothing = true;

    /** When this returns false, {@link #update} and {@link #getFormattedStats} do nothing. */
    private BooleanSupplier enabled = () -> true;

    private final Runtime runtime = Runtime.getRuntime();
    private static final double BYTES_TO_MB = 1.0 / (1024 * 1024);

    /**
     * @param defaultAlpha smoothing factor 0–1; higher is smoother (0.8 is a good start)
     */
    public SystemMonitor(double defaultAlpha) {
        this.defaultAlpha = Math.max(0.0, Math.min(1.0, defaultAlpha));
    }

    /** Smoothing factor 0.8. */
    public SystemMonitor() {
        this(0.8);
    }

    /** Global switch, re-read every update — wire it to a live telemetry flag. */
    public SystemMonitor enabledWhen(BooleanSupplier enabled) {
        if (enabled != null) this.enabled = enabled;
        return this;
    }

    /** Default smoothing for metrics added after this call. */
    public void setSmoothing(boolean enabled) {
        this.useSmoothing = enabled;
    }

    public Metric addMetric(String name, MetricProvider provider, MetricType type) {
        return addMetric(name, provider, useSmoothing, type);
    }

    public Metric addMetric(String name, MetricProvider provider, boolean useSmoothing, MetricType type) {
        Metric metric = new Metric(name, provider, useSmoothing, defaultAlpha, type);
        metrics.add(metric);
        return metric;
    }

    public Metric addMetric(String name, MetricProvider provider, long updateIntervalMs, MetricType type) {
        Metric metric = new Metric(name, provider, useSmoothing, defaultAlpha, updateIntervalMs, type);
        metrics.add(metric);
        return metric;
    }

    // ---- Built-in, platform-independent metrics ----

    /** Java heap in use, sampled every 100 ms. */
    public void addMemoryMetric() {
        addMetric("📟", now ->
                (runtime.totalMemory() - runtime.freeMemory()) * BYTES_TO_MB, 100L, MetricType.MB);
    }

    /**
     * Loop time: smoothed average (⟳) and worst-ever (꩜), both in milliseconds.
     * Requires {@link #update} to be called exactly once per loop.
     */
    public void addCycleMetric() {
        final long[] cycleMetric = {0, 0}; // [0]=lastTime, [1]=maxCycleTime

        addMetric("⟳", now -> {
            if (cycleMetric[0] > 0) {
                long cycleTime = now - cycleMetric[0];
                if (cycleTime > cycleMetric[1]) {
                    cycleMetric[1] = cycleTime;
                }
                cycleMetric[0] = now;
                return (double) cycleTime;
            }
            cycleMetric[0] = now;
            return 0.0;
        }, true, MetricType.NUMBER);

        addMetric("꩜", now -> (double) cycleMetric[1], false, MetricType.NUMBER);
    }

    /**
     * Cumulative milliseconds lost to suspected GC pauses, detected as loop gaps over 50 ms
     * (a healthy loop is under 20 ms). A rough signal, but it turns "the robot stuttered"
     * into a number you can watch.
     */
    public void addGcPauseDetector() {
        final long[] lastUpdateTime = {0};
        final long[] totalPauseMs = {0};

        addMetric("GCPause", now -> {
            if (lastUpdateTime[0] > 0) {
                long deltaMs = now - lastUpdateTime[0];
                if (deltaMs > 50) {
                    totalPauseMs[0] += (deltaMs - 20); // discount a normal loop
                }
            }
            lastUpdateTime[0] = now;
            return (double) totalPauseMs[0];
        }, false, MetricType.NUMBER);
    }

    /**
     * Rough cumulative Java-heap allocation in MB, inferred from growth between samples.
     * Under-reports whatever the GC reclaims between samples — treat it as a trend, not a total.
     */
    public void addMemoryAllocationMetric() {
        final long[] lastUsedMemory = {0};
        final double[] totalAllocated = {0.0};

        addMetric("ΣMem", now -> {
            long currentUsed = runtime.totalMemory() - runtime.freeMemory();

            if (lastUsedMemory[0] > 0 && currentUsed > lastUsedMemory[0]) {
                totalAllocated[0] += (currentUsed - lastUsedMemory[0]) * BYTES_TO_MB;
            }

            lastUsedMemory[0] = currentUsed;
            return totalAllocated[0];
        }, 50L, MetricType.MB);
    }

    /** Memory + cycle time — the two worth watching on every robot. */
    public void addStandardMetrics() {
        addMemoryMetric();
        addCycleMetric();
    }

    // ---- Reading ----

    /** Samples every due metric. Call once per loop. */
    public void update(long now) {
        if (!enabled.getAsBoolean()) return;
        // Indexed loop: this runs every cycle, so no iterator allocation.
        for (int i = 0; i < metrics.size(); i++) {
            metrics.get(i).update(now);
        }
    }

    /** @return the metric's current value, or 0 if there is no metric by that name */
    public double getValue(String name) {
        for (int i = 0; i < metrics.size(); i++) {
            Metric metric = metrics.get(i);
            if (metric.getName().equals(name)) {
                return metric.getValue();
            }
        }
        return 0.0;
    }

    /** One line of every metric: {@code "📟: 12.3MB | ⟳: 6.85"}. Allocation-light. */
    public String getFormattedStats() {
        if (!enabled.getAsBoolean() || metrics.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(128);

        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) sb.append(" | ");

            Metric metric = metrics.get(i);
            sb.append(metric.getName()).append(": ");

            switch (metric.getType()) {
                case PERCENTAGE:
                    Units.appendDouble(sb, metric.getValue(), 2);
                    sb.append('%');
                    break;
                case MB:
                    Units.appendDouble(sb, metric.getValue(), 1);
                    sb.append("MB");
                    break;
                case COUNTER:
                    sb.append((int) metric.getValue());
                    break;
                case NUMBER:
                default:
                    Units.appendDouble(sb, metric.getValue(), 2);
                    break;
            }
        }

        return sb.toString();
    }

    /** Removes every registered metric. */
    public void reset() {
        metrics.clear();
    }
}
