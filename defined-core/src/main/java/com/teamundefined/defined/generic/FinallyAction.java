package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

/**
 * FinallyAction
 *
 * Runs a primary action, then ALWAYS runs a cleanup action exactly once,
 * regardless of how the primary ended (COMPLETE / ERROR / TIMEOUT / CANCELED).
 *
 * This is your "finally { ... }" for FTC automations.
 *
 * Example:
 *   Action safeLift =
 *       FinallyAction.wrap("lift_with_cleanup", liftAction, stopLiftAction);
 *
 * Behavior:
 *  - While primary is running: ticks primary
 *  - When primary becomes terminal: starts cleanup
 *  - When cleanup becomes terminal: this wrapper ends,
 *      returning the ORIGINAL primary terminal state (unless cleanup errors, see below).
 *
 * Cleanup error behavior:
 *  - If cleanup ERRORs, wrapper becomes ERROR (because cleanup is safety-critical).
 *  - If cleanup TIMEOUTs, wrapper becomes TIMEOUT.
 *  - If cleanup CANCELED, wrapper becomes CANCELED.
 *
 * Cancellation:
 *  - If wrapper is canceled while primary running: cancels primary and still runs cleanup.
 *  - If wrapper is canceled while cleanup running: cancels cleanup and ends CANCELED.
 */
public class FinallyAction extends Action {

    private final Action primary;
    private final Action cleanup;

    private boolean cleanupStarted = false;
    private ActionState primaryTerminal = null;

    private FinallyAction(String name, Action primary, Action cleanup) {
        super(name, now -> {});
        this.primary = primary;
        this.cleanup = cleanup;

        // Propagate required slots from all actions
        if (this.primary != null) {
            this.requiredSlots.addAll(this.primary.requiredSlots());
        }
        if (this.cleanup != null) {
            this.requiredSlots.addAll(this.cleanup.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory */
    public static FinallyAction wrap(String name, Action primary, Action cleanup) {
        return new FinallyAction(name, primary, cleanup);
    }

    @Override
    public Action reset() {
        super.reset();
        cleanupStarted = false;
        primaryTerminal = null;
        if (primary != null) primary.reset();
        if (cleanup != null) cleanup.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        // If we're still in primary phase, cancel primary but DO NOT end yet;
        // we still want cleanup to run on subsequent ticks.
        if (!cleanupStarted) {
            if (primary != null && !primary.inTerminalState()) {
                primary.cancel("Canceled because FinallyAction canceled: " + name);
            }
            // mark primary as canceled if it wasn't terminal yet
            if (primaryTerminal == null) primaryTerminal = ActionState.CANCELED;
            // keep wrapper RUNNING so cleanup can run
            this.state = ActionState.RUNNING;
            return this.state;
        }

        // If we're already cleaning up, cancel cleanup and end canceled.
        if (cleanup != null && !cleanup.inTerminalState()) {
            cleanup.cancel("Canceled because FinallyAction canceled during cleanup: " + name);
        }
        return endActionWithCancel(reason);
    }

    private void runStep(long nowMillis) {
        if (primary == null) {
            endActionWithError("FinallyAction primary is null Action=[" + name + "]");
            return;
        }
        if (cleanup == null) {
            endActionWithError("FinallyAction cleanup is null Action=[" + name + "]");
            return;
        }

        // Phase 1: run primary until it ends
        if (!cleanupStarted) {
            ActionState ps = primary.update(nowMillis);

            if (primary.inTerminalState()) {
                primaryTerminal = ps;
                cleanupStarted = true;

                // start cleanup fresh
                cleanup.reset();
            } else {
                return; // still running primary
            }
        }

        // Phase 2: run cleanup until it ends
        ActionState cs = cleanup.update(nowMillis);

        if (!cleanup.inTerminalState()) return;

        // Cleanup ended - decide wrapper outcome
        if (cs == ActionState.COMPLETE) {
            // return original primary outcome
            if (primaryTerminal == ActionState.COMPLETE) {
                endAction(ActionState.COMPLETE);
            } else if (primaryTerminal == ActionState.TIMEOUT) {
                endActionWithTimeout("Primary timed out (cleanup completed)");
            } else if (primaryTerminal == ActionState.CANCELED) {
                endActionWithCancel("Primary canceled (cleanup completed)");
            } else {
                // ERROR or unknown
                endActionWithError("Primary failed (cleanup completed): " + primary.getErrorMessage());
            }
            return;
        }

        // Cleanup did NOT complete: escalate because cleanup is safety-critical
        if (cs == ActionState.ERROR) {
            endActionWithError("Cleanup failed: " + cleanup.getErrorMessage());
            return;
        }
        if (cs == ActionState.TIMEOUT) {
            endActionWithTimeout("Cleanup timed out");
            return;
        }
        if (cs == ActionState.CANCELED) {
            endActionWithCancel("Cleanup canceled");
        }
    }
}


/*
Examples:

// Stop motors after any exit
Action safeDrive =
    FinallyAction.wrap("drive_safe", driveAction,
        Action.oneShot("drive_stop", now -> drive.stop()));

// Servo safe position on cancel/error
Action safeArm =
    FinallyAction.wrap("arm_safe", armMove,
        Action.oneShot("arm_safe_pos", now -> arm.setSafe()));

 */