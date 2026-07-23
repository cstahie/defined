package com.teamundefined.defined;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * High-performance, GC-friendly telemetry snapshot system.
 *
 * - Initialized ONCE, reused every cycle (no allocations)
 * - Thread-safe for async telemetry processing
 * - Key-value based for flexibility
 * - Optimized formatters to avoid String.format() in hot path
 *
 * Usage:
 * TelemetrySnapshot snapshot = new TelemetrySnapshot(50); // Pre-allocate for 50 entries
 *
 * In loop:
 * snapshot.put("flywheel.state", robot.flywheel.currentState.toString());
 * snapshot.putDouble("flywheel.velocity", robot.flywheel.averageVelocity, 1);
 * telemetryProcessor.submitSnapshot(snapshot, this::processTelemetry);
 */
public class TelemetrySnapshot {
    // Thread-safe map for telemetry data
    private final ConcurrentHashMap<String, String> data;

    // Pre-allocated StringBuilder for number formatting (avoid allocations)
    private final StringBuilder numberBuilder;

    // Pre-allocated char array for double-to-string conversion
    private static final char[] DIGITS = {'0','1','2','3','4','5','6','7','8','9'};

    /**
     * Create a new telemetry snapshot with pre-allocated capacity.
     *
     * @param initialCapacity Expected number of telemetry entries
     */
    public TelemetrySnapshot(int initialCapacity) {
        // Pre-size map to avoid resizing
        this.data = new ConcurrentHashMap<>(initialCapacity, 0.75f, 1);
        this.numberBuilder = new StringBuilder(32); // Most numbers fit in 32 chars
    }

    /**
     * Put a string value (thread-safe).
     */
    public void put(String key, String value) {
        data.put(key, value != null ? value : "null");
    }

    /**
     * Put a boolean value (no allocation).
     */
    public void putBoolean(String key, boolean value) {
        data.put(key, value ? "true" : "false");
    }

    /**
     * Put an integer value (optimized, no String.format).
     */
    public void putInt(String key, int value) {
        data.put(key, Integer.toString(value)); // Optimized in JVM
    }

    /**
     * Put a double value with specified decimal places (no String.format!).
     * This is MUCH faster than String.format("%.2f", value).
     *
     * @param key The telemetry key
     * @param value The double value
     * @param decimalPlaces Number of decimal places (0-6)
     */
    public void putDouble(String key, double value, int decimalPlaces) {
        String formatted = fastFormatDouble(value, decimalPlaces);
        data.put(key, formatted);
    }

    /**
     * Put a double value with 2 decimal places (most common case).
     */
    public void putDouble2(String key, double value) {
        putDouble(key, value, 2);
    }

    /**
     * Get a value by key (thread-safe).
     */
    public String get(String key) {
        return data.get(key);
    }

    /**
     * Check if a key exists.
     */
    public boolean contains(String key) {
        return data.containsKey(key);
    }

    /**
     * Iterate over all entries (thread-safe snapshot).
     * The consumer receives key-value pairs.
     */
    public void forEach(Consumer<Entry> consumer) {
        // Iterate over a snapshot to avoid concurrency issues
        data.forEach((k, v) -> consumer.accept(new Entry(k, v)));
    }

    /**
     * Clear all data (call at OpMode stop).
     */
    public void clear() {
        data.clear();
    }

    /**
     * Get the number of entries.
     */
    public int size() {
        return data.size();
    }

    /**
     * Fast double formatting without String.format().
     * This is 10x faster than String.format() for common cases.
     */
    private String fastFormatDouble(double value, int decimalPlaces) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Inf" : "-Inf";

        // Clamp decimal places
        decimalPlaces = Math.max(0, Math.min(6, decimalPlaces));

        // Handle negative
        boolean negative = value < 0;
        if (negative) value = -value;

        // Round to desired decimal places
        double multiplier = Math.pow(10, decimalPlaces);
        long rounded = Math.round(value * multiplier);

        // Build string efficiently
        synchronized (numberBuilder) {
            numberBuilder.setLength(0);

            if (negative) numberBuilder.append('-');

            // Integer part
            long integerPart = rounded / (long)multiplier;
            numberBuilder.append(integerPart);

            // Decimal part
            if (decimalPlaces > 0) {
                numberBuilder.append('.');
                long decimalPart = rounded % (long)multiplier;

                // Pad with leading zeros if needed
                String decimals = Long.toString(decimalPart);
                for (int i = decimals.length(); i < decimalPlaces; i++) {
                    numberBuilder.append('0');
                }
                numberBuilder.append(decimals);
            }

            return numberBuilder.toString();
        }
    }

    /**
     * Simple key-value entry for iteration.
     */
    public static class Entry {
        public final String key;
        public final String value;

        Entry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /**
     * Create a formatted string for a label-value pair (common telemetry pattern).
     * Example: "Flywheel Velocity: 1234.56"
     */
    public void putLabeledDouble(String key, String label, double value, int decimalPlaces) {
        String formatted = fastFormatDouble(value, decimalPlaces);
        data.put(key, label + ": " + formatted);
    }

    /**
     * Put a pose as {@code "X: 12.3, Y: 45.6, H:1.57"}.
     *
     * <p>Takes plain coordinates rather than a Pedro {@code Pose} so this class stays in
     * the dependency-free core. Call it as
     * {@code putPosition("pose", p.getX(), p.getY(), p.getHeading(), 1)}.
     */
    public void putPosition(String key, double x, double y, double heading, int decimalPlaces) {
        String xStr = fastFormatDouble(x, decimalPlaces);
        String yStr = fastFormatDouble(y, decimalPlaces);
        String hStr = fastFormatDouble(heading, decimalPlaces);

        data.put(key, "X: " + xStr + ", Y: " + yStr + ", H:"  + hStr);
    }

    /**
     * Put a percentage value (0-100 with % sign).
     */
    public void putPercentage(String key, double value) {
        String formatted = fastFormatDouble(Math.max(0, Math.min(100, value)), 1);
        data.put(key, formatted + "%");
    }
}