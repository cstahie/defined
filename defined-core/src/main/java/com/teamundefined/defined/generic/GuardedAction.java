package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * GuardedAction
 *
 * Evaluates a guard condition ONCE at start.
 *
 * - If guard is true: runs the inner action normally.
 * - If guard is false: either
 *      (a) COMPLETE immediately (default), or
 *      (b) ERROR immediately (if configured).
 *
 * Example:
 *   Action safeExtend =
 *       GuardedAction.ifTrue("only_if_clear", () -> !arm.isExtended(), extendArm);
 *
 * Variant:
 *   GuardedAction.ifTrue(...).failIfGuardFalse("Arm already extended");
 */
public class GuardedAction extends Action {

    private final BooleanSupplier guard;
    private final Action inner;

    private boolean evaluated = false;
    private boolean guardPassed = false;

    private boolean failOnFalse = false;
    private String failMessage = null;

    private GuardedAction(String name, BooleanSupplier guard, Action inner) {
        super(name, now -> {});
        this.guard = guard;
        this.inner = inner;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: runs inner only if guard is true at start; otherwise completes immediately. */
    public static GuardedAction ifTrue(String name, BooleanSupplier guard, Action inner) {
        return new GuardedAction(name, guard, inner);
    }

    /** If guard is false, end with ERROR instead of COMPLETE. */
    public GuardedAction failIfGuardFalse(String message) {
        if (!ensureMutable("failIfGuardFalse")) return this;
        this.failOnFalse = true;
        this.failMessage = message;
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        evaluated = false;
        guardPassed = false;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because GuardedAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (!evaluated) {
            evaluated = true;

            if (guard == null) {
                endActionWithError("GuardedAction guard is null Action=[" + name + "]");
                return;
            }

            boolean ok;
            try {
                ok = guard.getAsBoolean();
            } catch (Exception e) {
                endActionWithError("GuardedAction guard threw: " + e.toString());
                return;
            }

            guardPassed = ok;

            if (!guardPassed) {
                if (failOnFalse) {
                    String msg = (failMessage != null && !failMessage.isBlank())
                            ? failMessage
                            : "GuardedAction blocked: guard was false";
                    endActionWithError(msg);
                } else {
                    endAction(ActionState.COMPLETE); // noop success
                }
                return;
            }

            if (inner == null) {
                endActionWithError("GuardedAction inner is null Action=[" + name + "]");
                return;
            }
        }

        if (!guardPassed) {
            // Already ended; nothing to do.
            return;
        }

        ActionState s = inner.update(nowMillis);

        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (s == ActionState.ERROR) {
            endActionWithError("GuardedAction inner failed: " + inner.getErrorMessage());
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("GuardedAction inner timed out");
            return;
        }
        if (s == ActionState.CANCELED) {
            endActionWithCancel("GuardedAction inner canceled");
        }
    }

    /** For telemetry/debug. */
    public boolean wasGuardPassed() {
        return evaluated && guardPassed;
    }
}

