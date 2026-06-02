package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * ManualOverrideAction
 *
 * Pauses an inner action while an override condition is true (e.g., driver holds a button).
 *
 * - Does NOT cancel the inner action.
 * - While override is active, inner.update() is NOT called (so automation "freezes").
 * - When override is released, inner continues from where it left off.
 *
 * IMPORTANT FTC NOTE:
 * If you pause automation, your TeleOp code must actively run the manual controls
 * for the same subsystems during the override. This action only stops automation ticking.
 *
 * Example:
 *   Action auto = ...;
 *   Action autoWithOverride =
 *       ManualOverrideAction.when(() -> gamepad1.a, auto)
 *           .withMessage("Driver override");
 */
public class ManualOverrideAction extends Action {

    private final BooleanSupplier overrideCondition;
    private final Action inner;

    private String overrideMessage = "Manual override";

    private ManualOverrideAction(String name, BooleanSupplier overrideCondition, Action inner) {
        super(name, now -> {});
        this.overrideCondition = overrideCondition;
        this.inner = inner;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: pauses inner while overrideCondition is true. */
    public static ManualOverrideAction when(BooleanSupplier overrideCondition, Action inner) {
        return new ManualOverrideAction("manual_override", overrideCondition, inner);
    }

    /** Factory with explicit name. */
    public static ManualOverrideAction when(String name, BooleanSupplier overrideCondition, Action inner) {
        return new ManualOverrideAction(name, overrideCondition, inner);
    }

    /** Optional: message to store in errorMessage while paused (useful for telemetry). */
    public ManualOverrideAction withMessage(String msg) {
        if (!ensureMutable("withMessage")) return this;
        if (msg != null && !msg.isBlank()) this.overrideMessage = msg;
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        // If someone cancels this wrapper, we cancel the inner too (consistent with your other wrappers)
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because ManualOverrideAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("ManualOverrideAction inner is null Action=[" + name + "]");
            return;
        }

        if (overrideCondition == null) {
            endActionWithError("ManualOverrideAction overrideCondition is null Action=[" + name + "]");
            return;
        }

        boolean override;
        try {
            override = overrideCondition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("ManualOverrideAction overrideCondition threw: " + e.toString());
            return;
        }

        if (override) {
            // Pause inner: do NOT tick it.
            // Store a status message for telemetry/debug (not an ERROR state).
            this.errorMessage = overrideMessage + " Action=[" + name + "]";
            return;
        }

        // Clear override message once released (keeps telemetry clean)
        if (this.errorMessage != null && this.errorMessage.contains(overrideMessage)) {
            this.errorMessage = null;
        }

        ActionState s = inner.update(nowMillis);

        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (s == ActionState.ERROR) {
            endActionWithError("ManualOverrideAction inner failed: " + inner.getErrorMessage());
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("ManualOverrideAction inner timed out");
            return;
        }
        if (s == ActionState.CANCELED) {
            endActionWithCancel("ManualOverrideAction inner canceled");
        }
    }
}