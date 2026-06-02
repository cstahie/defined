package com.teamundefined.defined;

import java.util.function.Supplier;

/**
 * Zero-overhead, pluggable logging facade for the Defined action engine.
 *
 * <p>The core library is pure Java and has no dependency on Android's
 * {@code android.util.Log}. By default this facade does <b>nothing</b> (the
 * {@link #sink} is {@code null}), so logging adds no measurable cost to the
 * robot loop — important on the resource-constrained REV Control Hub.
 *
 * <p>To see logs, install a {@link Sink}. The {@code defined-ftc} module ships
 * an Android sink that forwards to {@code android.util.Log}; on desktop you can
 * install a {@code System.out} sink:
 *
 * <pre>{@code
 * Log.sink = (level, tag, msg) -> System.out.println(tag + ": " + msg);
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
     * The active sink. {@code null} (the default) means logging is disabled and
     * message suppliers are never evaluated, so there is no allocation or
     * string-building cost.
     */
    public static volatile Sink sink = null;

    private Log() {}

    public static void d(String tag, String msg) { emit(DEBUG, tag, msg); }
    public static void i(String tag, String msg) { emit(INFO, tag, msg); }
    public static void w(String tag, String msg) { emit(WARN, tag, msg); }
    public static void e(String tag, String msg) { emit(ERROR, tag, msg); }

    /** Lazy variant — {@code msg} is only evaluated when a sink is installed. */
    public static void i(String tag, Supplier<String> msg) { emit(INFO, tag, msg); }

    /**
     * Lazy variant with a throttle hint (kept for API compatibility with the
     * original {@code ULog.i(tag, n, supplier)} call sites). The {@code everyN}
     * hint is advisory and currently ignored by the facade; sinks may honor it.
     */
    public static void i(String tag, int everyN, Supplier<String> msg) { emit(INFO, tag, msg); }

    private static void emit(int level, String tag, String msg) {
        Sink s = sink;
        if (s != null) s.log(level, tag, msg);
    }

    private static void emit(int level, String tag, Supplier<String> msg) {
        Sink s = sink;
        if (s != null && msg != null) s.log(level, tag, msg.get());
    }
}
