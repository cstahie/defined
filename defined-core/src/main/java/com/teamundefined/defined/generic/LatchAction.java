package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * LatchAction
 *
 * Samples a condition until it becomes true.
 * Once true, it LATCHES and stays true forever (until reset()).
 *
 * Typical use:
 *   - object detection (pixel / cone / specimen seen once)
 *   - limit switches that bounce
 *   - vision detections that flicker
 *
 * Example:
 *   Action seenPixel =
 *       LatchAction.once("seen_pixel", () -> sensor.hasPixel());
 *
 * Semantics:
 *   - While condition is false → RUNNING
 *   - First time condition is true → COMPLETE
 *   - After that → remains COMPLETE
 */
public class LatchAction extends Action {

    private final BooleanSupplier condition;
    private boolean latched = false;

    private LatchAction(String name, BooleanSupplier condition) {
        super(name, now -> {});
        this.condition = condition;

        this.step = this::runStep;
        this.isComplete = () -> latched || inTerminalState();
    }

    /** Factory: latch once condition becomes true. */
    public static LatchAction once(String name, BooleanSupplier condition) {
        return new LatchAction(name, condition);
    }

    @Override
    public Action reset() {
        super.reset();
        latched = false;
        return this;
    }

    private void runStep(long nowMillis) {
        if (latched) {
            // Already latched; nothing to do.
            return;
        }

        if (condition == null) {
            endActionWithError("LatchAction condition is null Action=[" + name + "]");
            return;
        }

        boolean result;
        try {
            result = condition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("LatchAction condition threw: " + e.toString());
            return;
        }

        if (result) {
            latched = true;
            endAction(ActionState.COMPLETE);
        }
    }

    /** For telemetry/debug. */
    public boolean isLatched() {
        return latched;
    }
}

/*
Examples:

//Vision flicker protection:
Action tagSeen = LatchAction.once("tag_seen", () -> limelight.hasTarget());

// Intake sensor:
Action pixelDetected = LatchAction.once("pixel_detected", intake::hasPixel);

// Use with Ensure + Repeat
Action grabVerified =
    EnsureAction.after(
        "verify_grab",
        RepeatAction.until("retry_grab", grabAction, pixelDetected::isLatched),
        pixelDetected::isLatched
    );
 */
