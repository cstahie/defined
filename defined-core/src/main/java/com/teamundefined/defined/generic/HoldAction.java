package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * HoldAction
 *
 * Continuously runs a "hold" function (typically PID / feedforward) until canceled
 * (or until an optional stop condition becomes true, if you set one).
 *
 * Key point: this action is meant to RUN FOREVER in a sequence/parallel group,
 * and you cancel it (explicitly or via CancelOnAction / Parallel(ANY) winner cancel).
 *
 * Examples:
 *   Action armHold = HoldAction.position("arm_hold", now -> arm.holdPosition());
 *
 *   // Hold until a condition:
 *   Action turretHoldUntilLocked = HoldAction.hold("turret_hold", now -> turret.holdAngle())
 *       .until(() -> turret.isLocked());
 *
 * Typical FTC usage:
 *   new ParallelAction("aim_and_hold", ANY, aimAction, HoldAction.position("arm_hold", now -> arm.hold()));
 *   // when aimAction completes, Parallel(ANY) cancels losers, which cancels the HoldAction.
 */
public class HoldAction extends Action {

    private final LongConsumer holdStep;
    private BooleanSupplier stopCondition = null; // optional "finish" condition

    private HoldAction(String name, LongConsumer holdStep) {
        super(name, now -> {});
        this.holdStep = holdStep;

        this.step = this::runStep;
        this.isComplete = this::checkDone;
    }

    /** Generic hold factory. */
    public static HoldAction hold(String name, LongConsumer holdStep) {
        return new HoldAction(name, holdStep);
    }

    /** Convenience alias for common naming. */
    public static HoldAction position(String name, LongConsumer holdStep) {
        return new HoldAction(name, holdStep);
    }

    /** Optional: end (COMPLETE) when this condition becomes true. Otherwise, runs until canceled. */
    public HoldAction until(BooleanSupplier stopCondition) {
        if (!ensureMutable("until")) return this;
        this.stopCondition = stopCondition;
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        // stopCondition is configuration; we keep it
        return this;
    }

    private void runStep(long nowMillis) {
        if (holdStep == null) {
            endActionWithError("HoldAction holdStep is null Action=[" + name + "]");
            return;
        }

        try {
            holdStep.accept(nowMillis);
        } catch (Exception e) {
            endActionWithError("HoldAction holdStep threw: " + e.toString());
        }
    }

    private boolean checkDone() {
        if (inTerminalState()) return true;

        if (stopCondition == null) {
            // Intentionally never completes on its own.
            return false;
        }

        try {
            if (stopCondition.getAsBoolean()) {
                endAction(ActionState.COMPLETE);
                return true;
            }
            return false;
        } catch (Exception e) {
            endActionWithError("HoldAction stopCondition threw: " + e.toString());
            return true;
        }
    }
}

// Examples:

// Hold forever in a Parallel(ANY) race so it gets canceled automatically when the “real” action finishes:
//Action aimAndHold =
//        ParallelAction.any("aim_and_hold",
//                aimToBasket,
//                HoldAction.position("arm_hold", now -> arm.holdPosition())
//        );
