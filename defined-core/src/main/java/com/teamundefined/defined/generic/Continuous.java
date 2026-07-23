package com.teamundefined.defined.generic;

import com.teamundefined.defined.Log;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * A lightweight continuous action that runs a delegate repeatedly until a condition is met.
 *
 * Unlike RepeatAction which wraps an inner Action (causing state transitions and log spam),
 * Continuous directly executes a delegate function each step with minimal overhead.
 *
 * Usage:
 * - Continuous.forever("name", now -> doSomething()) - runs until externally cancelled
 * - Continuous.until("name", () -> isDone, now -> doSomething()) - runs until condition is true
 */
public class Continuous extends Action {

    private final LongConsumer delegate;
    private final BooleanSupplier stopCondition;

    /**
     * Creates a continuous action.
     *
     * @param name Action name for debugging
     * @param stopCondition Condition to check each step - when true, action completes. Null = run forever.
     * @param delegate The function to execute each step, receives current time in millis
     */
    public Continuous(String name, BooleanSupplier stopCondition, LongConsumer delegate) {
        super(name, null);
        this.delegate = delegate;
        this.stopCondition = stopCondition;

        // Set up the step function to run our delegate
        this.step = this::runStep;

        // Set up completion check
        this.isComplete = this::checkComplete;
    }

    private void runStep(long nowMillis) {
        try {
            if (delegate != null) {
                delegate.accept(nowMillis);
            }
        } catch (Exception e) {
            Log.e("ContinuousAction", "Exception in delegate: " + e.toString());
        }

    }

    private boolean checkComplete() {
        // If no stop condition, never complete on our own (run forever)
        if (stopCondition == null) {
            return false;
        }
        return stopCondition.getAsBoolean();
    }

    /**
     * Creates a continuous action that runs forever (until externally cancelled or timed out).
     *
     * @param name Action name for debugging
     * @param delegate The function to execute each step
     * @return Continuous action that runs indefinitely
     */
    public static Continuous forever(String name, LongConsumer delegate) {
        return new Continuous(name, null, delegate);
    }

    /**
     * Creates a continuous action that runs until a condition becomes true.
     *
     * @param name Action name for debugging
     * @param stopCondition When this returns true, the action completes
     * @param delegate The function to execute each step
     * @return Continuous action that stops when condition is met
     */
    public static Continuous until(String name, BooleanSupplier stopCondition, LongConsumer delegate) {
        return new Continuous(name, stopCondition, delegate);
    }

    /**
     * Creates a continuous action that runs while a condition is true.
     * (Inverse of until - stops when condition becomes false)
     *
     * @param name Action name for debugging
     * @param whileCondition When this returns false, the action completes
     * @param delegate The function to execute each step
     * @return Continuous action that stops when condition becomes false
     */
    public static Continuous whileTrue(String name, BooleanSupplier whileCondition, LongConsumer delegate) {
        return new Continuous(name, () -> !whileCondition.getAsBoolean(), delegate);
    }

    /**
     * A never-ending monitor that runs {@code step} on every tick where {@code condition}
     * holds. This is the workhorse for gamepad and sensor monitors:
     *
     * <pre>{@code
     * runner.addMonitor(Continuous.monitor("reset_pose",
     *         () -> gamepad1.options, now -> robot.resetPose()));
     * }</pre>
     *
     * <p>Note this is <b>level</b>-triggered, not edge-triggered: hold the button and
     * {@code step} runs every loop. Wrap the condition in an
     * {@link EdgeTriggerAction}, or use {@link DebounceAction}, when you want
     * once-per-press semantics instead.
     *
     * <p>The action never completes — add it as a monitor, not as a slot action.
     */
    public static Continuous monitor(String name, BooleanSupplier condition, LongConsumer step) {
        return Continuous.forever(name + "_monitor", now -> {
            if (condition.getAsBoolean()) {
                step.accept(now);
            }
        });
    }
}
