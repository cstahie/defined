package com.teamundefined.defined.ftc;

import com.teamundefined.defined.Log;

/**
 * Bridges the Defined core {@link Log} facade to Android's logcat.
 *
 * <p>Call once in your OpMode's {@code init()} to route engine logs to logcat:
 *
 * <pre>{@code AndroidLog.install();}</pre>
 *
 * <p>Logging stays off (zero cost) until you call this — important on the
 * Control Hub where every millisecond of the loop counts.
 */
public final class AndroidLog {

    private AndroidLog() {}

    /** Route all Defined logs to {@code android.util.Log}. */
    public static void install() {
        Log.sink = (level, tag, msg) -> {
            switch (level) {
                case Log.DEBUG: android.util.Log.d(tag, msg); break;
                case Log.WARN:  android.util.Log.w(tag, msg); break;
                case Log.ERROR: android.util.Log.e(tag, msg); break;
                case Log.INFO:
                default:        android.util.Log.i(tag, msg); break;
            }
        };
    }

    /** Disable engine logging again (restores the zero-overhead default). */
    public static void uninstall() {
        Log.sink = null;
    }
}
