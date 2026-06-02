package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * CancelOnAction
 *
 * Wraps another Action and cancels it if a condition becomes true.
 *
 * - Delegates update() to the inner action.
 * - Continuously checks cancelCondition.
 * - If cancelCondition becomes true:
 *     - inner action is canceled
 *     - this action ends CANCELED
 *
 * Typical use:
 *   Action safeAuto =
 *       CancelOnAction.cancelIf(
 *           "abort_on_driver",
 *           longAutomation,
 *           () -> gamepad1.a
 *       );
 *
 * This is CRITICAL for TeleOp safety.
 */
public class CancelOnAction extends Action {

    private final Action inner;
    private final BooleanSupplier cancelCondition;

    private CancelOnAction(String name, Action inner, BooleanSupplier cancelCondition) {
        super(name, now -> {});
        this.inner = inner;
        this.cancelCondition = cancelCondition;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: cancel inner action if condition becomes true. */
    public static CancelOnAction cancelIf(String name, Action inner, BooleanSupplier cancelCondition) {
        return new CancelOnAction(name, inner, cancelCondition);
    }

    @Override
    public Action reset() {
        super.reset();
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        // Cancel inner first
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because CancelOnAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("CancelOnAction inner is null Action=[" + name + "]");
            return;
        }

        if (cancelCondition == null) {
            endActionWithError("CancelOnAction cancelCondition is null Action=[" + name + "]");
            return;
        }

        // Check cancel condition FIRST (before ticking inner)
        boolean cancel;
        try {
            cancel = cancelCondition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("CancelOnAction cancelCondition threw: " + e.toString());
            return;
        }

        if (cancel) {
            if (!inner.inTerminalState()) {
                inner.cancel("Canceled by CancelOnAction: " + name);
            }
            endActionWithCancel("Canceled by condition Action=[" + name + "]");
            return;
        }

        // Tick inner
        ActionState s = inner.update(nowMillis);

        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }

        if (s == ActionState.ERROR) {
            endActionWithError("CancelOnAction inner failed: " + inner.getErrorMessage());
            return;
        }

        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("CancelOnAction inner timed out");
            return;
        }

        if (s == ActionState.CANCELED) {
            endActionWithCancel("CancelOnAction inner canceled");
        }
    }
}


// EXAMPLES:

// Teleop cancel:
// Action autoAlignSafe =
//     CancelOnAction.cancelIf(
//         "abort_align",
//         autoAlign,
//         () -> Math.abs(gamepad1.left_stick_x) > 0.1
//     );

// Emergency stop:
// Action liftSafe =
//     CancelOnAction.cancelIf(
//         "lift_abort",
//         liftAction,
//         () -> limitSwitch.isPressed()
//     );

// Combined with Timeout:
// Action liftUltraSafe =
//     CancelOnAction.cancelIf(
//         "lift_abort",
//         new TimeoutAction("lift_safe", liftUp, 800),
//         () -> gamepad1.b
//     );