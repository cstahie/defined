package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * FailFastAction
 *
 * Trips immediately when a critical condition becomes true.
 *
 * Intended use:
 * - Put it inside a ParallelAction.ALL_NO_FAIL (or a RaceGroup/Deadline) alongside your automation.
 * - When it trips, it ends in ERROR (or TIMEOUT if you prefer), and your group logic cancels everything.
 *
 * Example:
 *   Action auto =
 *       ParallelAction.allNoFail("auto_safe",
 *           mainAutomation,
 *           FailFastAction.ifTrue("brownout", () -> batteryVoltage < 11.0, TripMode.ERROR)
 *               .withMessage("Brownout risk: battery < 11.0V")
 *       );
 *
 * This is *much* cleaner than sprinkling "if(brownout) return;" across code.
 */
public class FailFastAction extends Action {

    public enum TripMode {
        ERROR,
        CANCELED
    }

    private final BooleanSupplier condition;
    private final TripMode mode;
    private String message = null;

    private FailFastAction(String name, BooleanSupplier condition, TripMode mode) {
        super(name, now -> {});
        this.condition = condition;
        this.mode = mode;

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: end ERROR as soon as condition becomes true. */
    public static FailFastAction ifTrue(String name, BooleanSupplier condition) {
        return new FailFastAction(name, condition, TripMode.ERROR);
    }

    /** Factory: end CANCELED as soon as condition becomes true. */
    public static FailFastAction cancelIf(String name, BooleanSupplier condition) {
        return new FailFastAction(name, condition, TripMode.CANCELED);
    }

    /** Optional: custom message when tripped. */
    public FailFastAction withMessage(String message) {
        if (!ensureMutable("withMessage")) return this;
        this.message = message;
        return this;
    }

    private void runStep(long nowMillis) {
        if (condition == null) {
            endActionWithError("FailFastAction condition is null Action=[" + name + "]");
            return;
        }

        boolean tripped;
        try {
            tripped = condition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("FailFastAction condition threw: " + e.toString());
            return;
        }

        if (!tripped) return;

        String msg = (message != null && !message.isBlank())
                ? message
                : ("FailFast tripped Action=[" + name + "]");

        if (mode == TripMode.CANCELED) {
            endActionWithCancel(msg);
        } else {
            endActionWithError(msg);
        }
    }
}