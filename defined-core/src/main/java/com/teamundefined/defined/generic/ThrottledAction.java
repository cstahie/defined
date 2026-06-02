package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

/**
 * Wraps an action and throttles its execution to run at most once every X milliseconds.
 *
 * Use this for expensive operations that don't need to run every cycle:
 * - Limelight processing
 * - Distance calculations
 * - Complex state checks
 * - Logging/telemetry updates
 *
 * Example usage:
 * <pre>
 * // Run expensive check at most every 100ms
 * Action throttled = ThrottledAction.wrap(expensiveAction, 100);
 *
 * // Or with the builder pattern
 * Action throttled = new ThrottledAction(expensiveAction, 100);
 * </pre>
 *
 * The inner action's step() is only called when the interval has elapsed.
 * The throttled action completes when the inner action completes.
 */
public class ThrottledAction extends Action {
    private final Action innerAction;
    private final long intervalMs;
    private long lastRunTime = 0;

    /**
     * Creates a throttled wrapper around an action.
     *
     * @param innerAction The action to throttle
     * @param intervalMs Minimum milliseconds between executions
     */
    public ThrottledAction(Action innerAction, long intervalMs) {
        super("throttled_" + innerAction.name, now -> {});

        this.innerAction = innerAction;
        this.intervalMs = intervalMs;

        // Throttled step - only runs inner action if interval elapsed
        this.step = this::throttledStep;

        // Complete when inner action completes
        this.isComplete = () -> {
            java.util.function.BooleanSupplier innerComplete = innerAction.getIsComplete();
            return innerComplete != null && innerComplete.getAsBoolean();
        };

        // Inherit slots from inner action
        this.requiredSlots.addAll(innerAction.requiredSlots());

        // Reset timing on start
        this.onStart = now -> lastRunTime = 0;
    }

    private void throttledStep(long nowMillis) {
        // Check if enough time has passed
        if (nowMillis - lastRunTime < intervalMs) {
            return;  // Skip this cycle
        }

        // Time elapsed - run the inner action's step
        lastRunTime = nowMillis;
        java.util.function.LongConsumer innerStep = innerAction.getStep();
        if (innerStep != null) {
            innerStep.accept(nowMillis);
        }
    }

    @Override
    public Action reset() {
        super.reset();
        innerAction.reset();
        lastRunTime = 0;
        return this;
    }

    @Override
    public ActionState cancel() {
        innerAction.cancel();
        return super.cancel();
    }

    /**
     * Static factory method for wrapping an action with throttling.
     *
     * @param action The action to throttle
     * @param intervalMs Minimum milliseconds between executions
     * @return Throttled action wrapper
     */
    public static ThrottledAction wrap(Action action, long intervalMs) {
        return new ThrottledAction(action, intervalMs);
    }

    /**
     * Creates a throttled monitor action that checks a condition periodically.
     * Useful for expensive condition checks that don't need to run every cycle.
     *
     * @param name Action name
     * @param intervalMs How often to check (milliseconds)
     * @param check The condition check to run (called every intervalMs)
     * @return Throttled monitor action
     */
    public static Action throttledMonitor(String name, long intervalMs, Runnable check) {
        Action inner = Action.until(name, now -> check.run(), () -> false);
        return new ThrottledAction(inner, intervalMs);
    }

    /**
     * Creates a throttled monitor that executes an action when a condition is met,
     * but only checks the condition every intervalMs.
     *
     * @param name Action name
     * @param intervalMs How often to check condition (milliseconds)
     * @param condition The condition to check
     * @param onConditionMet What to do when condition is true
     * @return Throttled conditional monitor
     */
    public static Action throttledConditional(String name, long intervalMs,
                                               java.util.function.BooleanSupplier condition,
                                               Runnable onConditionMet) {
        Action inner = Action.until(name, now -> {
            if (condition.getAsBoolean()) {
                onConditionMet.run();
            }
        }, () -> false);
        return new ThrottledAction(inner, intervalMs);
    }
}
