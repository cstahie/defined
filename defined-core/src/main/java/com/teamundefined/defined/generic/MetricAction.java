package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.LongConsumer;

/**
 * MetricAction
 *
 * Wraps another action and measures:
 *  - wall time from first tick to terminal
 *  - tick count
 *  - average ms per tick (based on nowMillis deltas)
 *  - max ms between ticks (jitter)
 *
 * Useful for:
 *  - "why is this action slow?"
 *  - detecting Lynx/IO stalls
 *  - benchmarking vision pipelines / pathing / servo sequences
 *
 * Example:
 *   Action measured =
 *       MetricAction.measure("aim_metrics", aimAction, (msg) -> telemetry.addLine(msg));
 *
 *   // Or without logging, just read fields after it ends:
 *   MetricAction m = MetricAction.measure("lift_metrics", liftAction, null);
 *   ...
 *   telemetry.addData("lift_ms", m.getElapsedMs());
 */
public class MetricAction extends Action {

    @FunctionalInterface
    public interface LogSink {
        void log(String msg);
    }

    private final Action inner;
    private final LogSink sink;

    private boolean started = false;
    private long firstNow = -1;
    private long lastNow = -1;

    private int ticks = 0;
    private long sumDeltaMs = 0;
    private long maxDeltaMs = 0;

    private long endedAtNow = -1;

    private MetricAction(String name, Action inner, LogSink sink) {
        super(name, now -> {});
        this.inner = inner;
        this.sink = sink;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: wrap an action and collect metrics. */
    public static MetricAction measure(String name, Action inner, LogSink sink) {
        return new MetricAction(name, inner, sink);
    }

    /** Factory: log via a simple LongConsumer sink (e.g., to flush telemetry). */
    public static MetricAction measure(String name, Action inner, LongConsumer logLine) {
        LogSink s = (logLine == null) ? null : (msg) -> logLine.accept(System.currentTimeMillis());
        return new MetricAction(name, inner, s);
    }

    @Override
    public Action reset() {
        super.reset();
        started = false;
        firstNow = -1;
        lastNow = -1;
        ticks = 0;
        sumDeltaMs = 0;
        maxDeltaMs = 0;
        endedAtNow = -1;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because MetricAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("MetricAction inner is null Action=[" + name + "]");
            return;
        }

        if (!started) {
            started = true;
            firstNow = nowMillis;
            lastNow = nowMillis;
            ticks = 0;
            sumDeltaMs = 0;
            maxDeltaMs = 0;
            if (sink != null) sink.log("[METRIC START] " + name + " inner=" + inner.name);
        }

        // Tick delta stats (between MetricAction updates)
        long dt = nowMillis - lastNow;
        if (dt >= 0) {
            sumDeltaMs += dt;
            if (dt > maxDeltaMs) maxDeltaMs = dt;
        }
        lastNow = nowMillis;
        ticks++;

        // Run inner
        ActionState s = inner.update(nowMillis);

        // Mirror terminal result
        if (s == ActionState.COMPLETE) {
            endedAtNow = nowMillis;
            endAction(ActionState.COMPLETE);
            logEnd();
            return;
        }
        if (s == ActionState.ERROR) {
            endedAtNow = nowMillis;
            endActionWithError("Inner failed: " + inner.getErrorMessage());
            logEnd();
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endedAtNow = nowMillis;
            endActionWithTimeout("Inner timed out");
            logEnd();
            return;
        }
        if (s == ActionState.CANCELED) {
            endedAtNow = nowMillis;
            endActionWithCancel("Inner canceled");
            logEnd();
        }
    }

    private void logEnd() {
        if (sink == null) return;

        long elapsed = getElapsedMs();
        double avgTick = getAvgTickMs();

        String extra = "";
        if (this.state == ActionState.ERROR && inner != null && inner.getErrorMessage() != null) {
            extra = " err=" + inner.getErrorMessage();
        }

        sink.log("[METRIC END] " + name +
                " state=" + this.state +
                " elapsedMs=" + elapsed +
                " ticks=" + ticks +
                " avgTickMs=" + String.format(java.util.Locale.US, "%.2f", avgTick) +
                " maxTickMs=" + maxDeltaMs +
                extra);
    }

    /** Total elapsed time from first tick to terminal (ms). */
    public long getElapsedMs() {
        if (!started) return 0;
        long end = (endedAtNow >= 0) ? endedAtNow : lastNow;
        return Math.max(0, end - firstNow);
    }

    /** Number of update() ticks observed while running. */
    public int getTicks() {
        return ticks;
    }

    /** Average ms between MetricAction ticks (based on nowMillis deltas). */
    public double getAvgTickMs() {
        if (ticks <= 1) return 0.0;
        return (double) sumDeltaMs / (double) (ticks - 1);
    }

    /** Maximum ms between ticks (jitter / stalls). */
    public long getMaxTickMs() {
        return maxDeltaMs;
    }

    /** Snapshot string for telemetry. */
    public String summary() {
        return "Metric[" + name + "] state=" + state +
                " elapsedMs=" + getElapsedMs() +
                " ticks=" + ticks +
                " avgTickMs=" + String.format(java.util.Locale.US, "%.2f", getAvgTickMs()) +
                " maxTickMs=" + maxDeltaMs;
    }
}