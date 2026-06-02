package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * RepeatAction
 *
 * Repeats an inner Action:
 *  - a fixed number of times, OR
 *  - until a stop condition becomes true.
 *
 * Non-blocking: it ticks the inner action each update().
 *
 * Semantics:
 *  - Each iteration runs the inner action from NONE -> terminal.
 *  - When the inner action reaches COMPLETE, we either:
 *      * stop (if stop condition met / iterations done), or
 *      * reset inner and start next iteration.
 *  - If the inner action ends ERROR or TIMEOUT, this RepeatAction ends with the same failure.
 *
 * Examples:
 *   Action nudge3 = RepeatAction.times("nudge", nudgeAction, 3);
 *
 *   Action retryPickup = RepeatAction.until("retry_pickup", pickupAction, () -> hasPixel())
 *       .withMaxIterations(5)        // optional safety cap
 *       .withDelayBetween(100);      // optional delay between attempts
 */
public class RepeatAction extends Action {

    private final Action inner;
    private final int targetIterations;          // if >= 0, repeat exactly N times
    private final BooleanSupplier stopCondition; // if non-null, stop when true

    private int completedIterations = 0;

    private long delayBetweenMs = 0;
    private long nextStartAllowedAtMs = -1;

    private int maxIterations = -1; // optional safety cap for "until" mode

    private RepeatAction(String name, Action inner, int targetIterations, BooleanSupplier stopCondition) {
        super(name, now -> {});
        this.inner = inner;
        this.targetIterations = targetIterations;
        this.stopCondition = stopCondition;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Repeat exactly N times. */
    public static RepeatAction times(String name, Action inner, int times) {
        int n = Math.max(0, times);
        return new RepeatAction(name, inner, n, null);
    }

    /** Repeat until stopCondition becomes true. (No built-in max; consider withMaxIterations for safety.) */
    public static RepeatAction until(String name, Action inner, BooleanSupplier stopCondition) {
        return new RepeatAction(name, inner, -1, stopCondition);
    }

    /** Optional: add a delay between iterations (ms). */
    public RepeatAction withDelayBetween(long delayMs) {
        if (!ensureMutable("withDelayBetween")) return this;
        this.delayBetweenMs = Math.max(0, delayMs);
        return this;
    }

    /**
     * Optional safety cap (especially important for until-mode).
     * If exceeded, this action ends TIMEOUT (acts like "gave up").
     */
    public RepeatAction withMaxIterations(int maxIterations) {
        if (!ensureMutable("withMaxIterations")) return this;
        this.maxIterations = maxIterations <= 0 ? -1 : maxIterations;
        return this;
    }

    public int getCompletedIterations() {
        return completedIterations;
    }

    @Override
    public Action reset() {
        super.reset();
        completedIterations = 0;
        nextStartAllowedAtMs = -1;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because RepeatAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("RepeatAction inner is null Action=[" + name + "]");
            return;
        }

        // Delay gate between iterations
        if (nextStartAllowedAtMs >= 0 && nowMillis < nextStartAllowedAtMs) {
            return;
        }

        // Stop condition can end us early (before ticking)
        if (stopCondition != null) {
            boolean stop;
            try {
                stop = stopCondition.getAsBoolean();
            } catch (Exception e) {
                endActionWithError("RepeatAction stopCondition threw: " + e.toString());
                return;
            }
            if (stop) {
                endAction(ActionState.COMPLETE);
                return;
            }
        }

        // Safety cap for until-mode (or also allowed for times-mode)
        if (maxIterations > 0 && completedIterations >= maxIterations) {
            endActionWithTimeout("RepeatAction exceeded maxIterations=" + maxIterations + " Action=[" + name + "]");
            return;
        }

        // Tick inner
        ActionState s = inner.update(nowMillis);

        if (s == ActionState.COMPLETE) {
            completedIterations++;

            // Check if we're done (times-mode)
            if (targetIterations >= 0 && completedIterations >= targetIterations) {
                endAction(ActionState.COMPLETE);
                return;
            }

            // Check if we're done (until-mode) right after a successful iteration
            if (stopCondition != null) {
                boolean stop;
                try {
                    stop = stopCondition.getAsBoolean();
                } catch (Exception e) {
                    endActionWithError("RepeatAction stopCondition threw: " + e.toString());
                    return;
                }
                if (stop) {
                    endAction(ActionState.COMPLETE);
                    return;
                }
            }

            // Not done: prepare next iteration
            inner.reset();
            nextStartAllowedAtMs = delayBetweenMs > 0 ? (nowMillis + delayBetweenMs) : -1;
            return;
        }

        if (s == ActionState.ERROR) {
            endActionWithError("RepeatAction inner failed on iter " + completedIterations +
                    " [" + inner.name + "]: " + inner.getErrorMessage());
            return;
        }

        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("RepeatAction inner timed out on iter " + completedIterations +
                    " [" + inner.name + "]");
            return;
        }

        if (s == ActionState.CANCELED) {
            endActionWithCancel("RepeatAction inner canceled on iter " + completedIterations +
                    " [" + inner.name + "]");
        }
    }
}