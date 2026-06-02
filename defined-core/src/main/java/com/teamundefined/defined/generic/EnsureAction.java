package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * EnsureAction
 *
 * Runs an inner action, then verifies a condition AFTER the inner action completes.
 *
 * - If inner COMPLETE and ensureCondition is true -> this action COMPLETE
 * - If inner COMPLETE and ensureCondition is false -> this action ERROR
 * - If inner ERROR/TIMEOUT/CANCELED -> propagate as ERROR/TIMEOUT/CANCELED (with context)
 *
 * Example:
 *   Action verifyGrab =
 *       EnsureAction.after("verify_grab", grabAction, () -> sensor.hasObject());
 *
 * Optional:
 *   .withMessage("No object detected after grab");
 *   .withTimeout(500); // wrapper timeout (protects against inner hanging)
 */
public class EnsureAction extends Action {

    private final Action inner;
    private final BooleanSupplier ensureCondition;

    private String failMessage = null;
    private boolean verified = false;

    private EnsureAction(String name, Action inner, BooleanSupplier ensureCondition) {
        super(name, now -> {});
        this.inner = inner;
        this.ensureCondition = ensureCondition;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: run inner, then ensure condition is true. */
    public static EnsureAction after(String name, Action inner, BooleanSupplier ensureCondition) {
        return new EnsureAction(name, inner, ensureCondition);
    }

    /** Optional: custom error message when ensureCondition is false. */
    public EnsureAction withMessage(String message) {
        if (!ensureMutable("withMessage")) return this;
        this.failMessage = message;
        return this;
    }

    @Override
    public EnsureAction withTimeout(long timeoutMs) {
        super.withTimeout(timeoutMs);
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        verified = false;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because EnsureAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (verified) {
            // We already verified and ended (or attempted to); nothing to do.
            return;
        }

        if (inner == null) {
            endActionWithError("EnsureAction inner is null Action=[" + name + "]");
            verified = true;
            return;
        }

        if (ensureCondition == null) {
            endActionWithError("EnsureAction ensureCondition is null Action=[" + name + "]");
            verified = true;
            return;
        }

        ActionState s = inner.update(nowMillis);

        if (s == ActionState.RUNNING || s == ActionState.NONE) {
            return;
        }

        if (s == ActionState.ERROR) {
            endActionWithError("EnsureAction inner failed: " + inner.getErrorMessage());
            verified = true;
            return;
        }

        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("EnsureAction inner timed out");
            verified = true;
            return;
        }

        if (s == ActionState.CANCELED) {
            endActionWithCancel("EnsureAction inner canceled");
            verified = true;
            return;
        }

        // s == COMPLETE -> verify condition
        boolean ok;
        try {
            ok = ensureCondition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("EnsureAction ensureCondition threw: " + e.toString());
            verified = true;
            return;
        }

        if (ok) {
            endAction(ActionState.COMPLETE);
        } else {
            String msg = (failMessage != null && !failMessage.isBlank())
                    ? failMessage
                    : "EnsureAction failed: condition not met after [" + inner.name + "]";
            endActionWithError(msg);
        }

        verified = true;
    }
}

// Example
//
//	•	Use EnsureAction after anything that can “complete” without actually achieving the physical result (intake/grab, latch, shooter feed, hook engage).
//        •	If you want a retry loop, wrap it:
// Action grabVerified =
//        FailsafeAction.withRetry(
//                "grab_retry",
//                EnsureAction.after("verify_grab", grabAction, () -> sensor.hasObject()),
//                2
//        );