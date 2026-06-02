package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.LongConsumer;

/**
 * Non-blocking wait action.
 *
 * Usage:
 *   Action wait = WaitAction.ms("wait250", 250);
 *   // in a SequentialAction: new SequentialAction("seq", ..., wait, ...)
 *
 * It does NOT sleep. It completes when nowMillis - startTime >= durationMs.
 * Optional: onTick callback runs while waiting.
 */
public class WaitAction extends Action {

    private final long durationMs;
    private LongConsumer onTick = null;

    private long startedAtMs = -1;
    private long lastNowMs = -1;

    public WaitAction(String name, long durationMs) {
        super(name, now -> {});
        this.durationMs = Math.max(0, durationMs);

        this.step = this::runStep;

        // Completion is driven by the timestamp injected into update(now), NOT wall-clock time.
        // This keeps WaitAction deterministic and consistent with the rest of the engine, so a
        // robot loop that feeds a monotonic clock behaves identically under test.
        this.isComplete = () -> startedAtMs >= 0 && (lastNowMs - startedAtMs) >= this.durationMs;
    }

    public static WaitAction ms(String name, long durationMs) {
        return new WaitAction(name, durationMs);
    }

    public static WaitAction seconds(String name, double seconds) {
        long ms = (long) (seconds * 1000.0);
        return new WaitAction(name, ms);
    }

    /** Optional: run a callback each update while waiting (e.g., keep a servo holding position). */
    public WaitAction withOnTick(LongConsumer onTick) {
        if (!ensureMutable("withOnTick")) return this;
        this.onTick = onTick;
        return this;
    }

    private void runStep(long nowMillis) {
        // initialize once
        if (startedAtMs < 0) {
            startedAtMs = nowMillis;
        }
        lastNowMs = nowMillis;

        if (onTick != null) {
            try {
                onTick.accept(nowMillis);
            } catch (Exception e) {
                endActionWithError("WaitAction onTick failed: " + e);
            }
        }
    }

    @Override
    public Action reset() {
        super.reset();
        startedAtMs = -1;
        lastNowMs = -1;
        return this;
    }
}