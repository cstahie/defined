package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * RequireAction
 *
 * A hard precondition check.
 *
 * - Evaluates condition ONCE at start.
 * - If condition is FALSE -> ERROR immediately.
 * - If condition is TRUE  -> COMPLETE immediately.
 *
 * This is NOT a guard.
 * Guards skip actions silently.
 * RequireAction FAILS loudly and aborts the automation.
 *
 * Example:
 *   RequireAction.that("needs_pixel", () -> sensor.hasPixel());
 *
 * Typical usage:
 *   new SequentialAction("pickup_seq",
 *       RequireAction.that("pixel_present", () -> sensor.hasPixel()),
 *       grabAction,
 *       liftAction
 *   );
 */
public final class RequireAction extends Action {

    private final BooleanSupplier condition;
    private boolean checked = false;

    private RequireAction(String name, BooleanSupplier condition) {
        super(name, now -> {});
        this.condition = condition;

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: fail immediately if condition is false. */
    public static RequireAction that(String name, BooleanSupplier condition) {
        return new RequireAction(name, condition);
    }

    @Override
    public Action reset() {
        super.reset();
        checked = false;
        return this;
    }

    private void runStep(long nowMillis) {
        if (checked) return;
        checked = true;

        if (condition == null) {
            endActionWithError("RequireAction condition is null Action=[" + name + "]");
            return;
        }

        boolean ok;
        try {
            ok = condition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("RequireAction condition threw: " + e.toString());
            return;
        }

        if (!ok) {
            endActionWithError("Requirement failed Action=[" + name + "]");
            return;
        }

        // Condition satisfied → continue sequence
        endAction(ActionState.COMPLETE);
    }
}


/*
Prevents silent bad states
// BAD (silent skip, hard to debug)
if (!sensor.hasPixel()) return;

// GOOD (fails fast, visible in logs/telemetry)
RequireAction.that("needs_pixel", sensor::hasPixel);


// Pairs perfectly with your other primitives
Action pickup =
    new SequentialAction("pickup_seq",
        RequireAction.that("pixel_present", () -> intake.hasPixel()),
        intakeClose,
        liftUp
    );

If hasPixel() is false:
	•	sequence stops
	•	error propagates
	•	Parallel/Deadline/FailFast logic cancels safely


*/