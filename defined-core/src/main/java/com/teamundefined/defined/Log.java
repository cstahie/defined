package com.teamundefined.defined;

import java.util.function.Supplier;

/**
 * Pluggable logging facade for the Defined action engine — <b>works out of the box</b>.
 *
 * <p>You do not have to configure anything. On a robot, this auto-detects
 * {@code android.util.Log} the first time something is logged and forwards to it, so
 * library messages show up in {@code adb logcat} with no setup. On desktop (unit tests,
 * MeepMeep) there is no Android class to find, so logging stays silent and free.
 *
 * <p>The core library itself is pure Java and does not link against Android — the
 * detection is reflective and happens exactly once.
 *
 * <h2>Controlling the noise</h2>
 * <ul>
 *   <li>{@link #verbosity} gates the {@code i(tag, level, supplier)} calls used on hot
 *       paths. It defaults to {@code 1}, which drops the engine's per-state-change
 *       logging; raise it while debugging.</li>
 *   <li>{@link #disable()} turns logging off entirely.</li>
 *   <li>Assign {@link #sink} to route somewhere else — a file, telemetry, or your own
 *       gating logic. An explicit sink always wins over auto-detection.</li>
 * </ul>
 *
 * <pre>{@code
 * Log.verbosity = 30;                                  // see engine state transitions
 * Log.disable();                                       // silence everything
 * Log.sink = (lvl, tag, msg) -> System.out.println(tag + ": " + msg);   // custom
 * }</pre>
 *
 * <p>The static method names mirror {@code android.util.Log} ({@code d/i/w/e})
 * so existing call sites port over unchanged.
 */
public final class Log {

    /** Sink that receives log records. Set to {@code null} to disable (default). */
    @FunctionalInterface
    public interface Sink {
        /**
         * @param level one of {@link #DEBUG}, {@link #INFO}, {@link #WARN}, {@link #ERROR}
         * @param tag   short category tag
         * @param msg   already-rendered message
         */
        void log(int level, String tag, String msg);
    }

    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    /**
     * The active sink. When {@code null} (the default) the facade falls back to an
     * auto-detected {@code android.util.Log} sink, so logging works with no setup on a
     * robot and stays silent off one. Assign a sink to take full control, or call
     * {@link #disable()} to switch logging off.
     */
    public static volatile Sink sink = null;

    /** Auto-detected fallback, resolved at most once. */
    private static volatile Sink autoSink = null;
    private static volatile boolean autoResolved = false;

    /** A sink that discards everything — used by {@link #disable()}. */
    public static final Sink NO_OP = (level, tag, msg) -> {};

    /** Turns logging off entirely, including the auto-detected Android sink. */
    public static void disable() {
        sink = NO_OP;
    }

    /** Restores the default behavior (auto-detect {@code android.util.Log}). */
    public static void useAutoDetect() {
        sink = null;
    }

    /**
     * The sink to log through: an explicitly installed one, else the auto-detected
     * Android one, else {@code null} (nothing installed and not on Android).
     */
    private static Sink activeSink() {
        Sink s = sink;
        if (s != null) return s;
        if (!autoResolved) {
            autoResolved = true;      // set first: a failed lookup must not retry every call
            autoSink = detectAndroidSink();
        }
        return autoSink;
    }

    /**
     * Reflectively binds {@code android.util.Log}. Returns {@code null} off Android, which
     * is the desired outcome for desktop tests — nothing to configure, nothing printed.
     */
    private static Sink detectAndroidSink() {
        try {
            Class<?> androidLog = Class.forName("android.util.Log");
            final java.lang.reflect.Method d = androidLog.getMethod("d", String.class, String.class);
            final java.lang.reflect.Method i = androidLog.getMethod("i", String.class, String.class);
            final java.lang.reflect.Method w = androidLog.getMethod("w", String.class, String.class);
            final java.lang.reflect.Method e = androidLog.getMethod("e", String.class, String.class);
            return (level, tag, msg) -> {
                try {
                    switch (level) {
                        case DEBUG: d.invoke(null, tag, msg); break;
                        case WARN:  w.invoke(null, tag, msg); break;
                        case ERROR: e.invoke(null, tag, msg); break;
                        default:    i.invoke(null, tag, msg); break;
                    }
                } catch (Throwable ignored) {
                    // Logging must never take the robot down.
                }
            };
        } catch (Throwable notAndroid) {
            return null;
        }
    }

    /**
     * Verbosity gate for {@link #i(String, int, Supplier)}. A call whose {@code level}
     * argument exceeds this value is dropped <em>before</em> the message supplier runs,
     * so noisy per-loop logging costs nothing but the comparison.
     *
     * <p>Defaults to {@code 1} (only the least-verbose call sites). Raise it while
     * debugging. This matters on an FTC Control Hub, where the action engine logs on
     * every state change inside a ~100 Hz loop.
     */
    public static volatile int verbosity = 1;

    private Log() {}

    public static void d(String tag, String msg) { emit(DEBUG, tag, msg); }
    public static void i(String tag, String msg) { emit(INFO, tag, msg); }
    public static void w(String tag, String msg) { emit(WARN, tag, msg); }
    public static void e(String tag, String msg) { emit(ERROR, tag, msg); }

    /** Lazy variants — {@code msg} is only evaluated when a sink is installed. */
    public static void d(String tag, Supplier<String> msg) { emit(DEBUG, tag, msg); }
    public static void i(String tag, Supplier<String> msg) { emit(INFO, tag, msg); }
    public static void w(String tag, Supplier<String> msg) { emit(WARN, tag, msg); }
    public static void e(String tag, Supplier<String> msg) { emit(ERROR, tag, msg); }

    /**
     * Lazy variant gated on {@link #verbosity}: the message is dropped, and its supplier
     * never evaluated, when {@code level > verbosity}. Higher {@code level} means noisier.
     */
    public static void i(String tag, int level, Supplier<String> msg) {
        if (level > verbosity) return;
        emit(INFO, tag, msg);
    }

    private static void emit(int level, String tag, String msg) {
        Sink s = activeSink();
        if (s != null) s.log(level, tag, msg);
    }

    private static void emit(int level, String tag, Supplier<String> msg) {
        Sink s = activeSink();
        // Note the ordering: no sink means the supplier is never invoked, so an expensive
        // message costs nothing when logging is off.
        if (s != null && msg != null) s.log(level, tag, msg.get());
    }
}
