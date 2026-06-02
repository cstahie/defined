package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * WatchdogAction
 *
 * Monitors a trigger condition while running a "protected" action.
 * If the trigger fires, it runs an emergency action and cancels the protected action.
 *
 * Example:
 *   Action safeLift =
 *       WatchdogAction.monitor(
 *           "current_limit",
 *           liftAction,
 *           () -> liftMotor.getCurrent() > 4.5,
 *           emergencyStopAction
 *       );
 *
 * Behavior:
 * - Ticks protected action each update().
 * - Checks trigger condition each update().
 * - If trigger becomes true:
 *     - cancels protected action (best effort)
 *     - starts running emergency action
 *     - this wrapper completes when emergency reaches terminal state
 *
 * Notes:
 * - If the protected action finishes before trigger fires, this wrapper ends with the same result.
 * - Optional: failFast mode can end immediately on trigger without waiting for emergency to finish.
 */
public class WatchdogAction extends Action {

    private final Action protectedAction;
    private final BooleanSupplier trigger;
    private final Action emergencyAction;

    private boolean tripped = false;
    private boolean failFast = false;

    private WatchdogAction(String name, Action protectedAction, BooleanSupplier trigger, Action emergencyAction) {
        super(name, now -> {});
        this.protectedAction = protectedAction;
        this.trigger = trigger;
        this.emergencyAction = emergencyAction;

        // Propagate required slots from all actions
        if (this.protectedAction != null) {
            this.requiredSlots.addAll(this.protectedAction.requiredSlots());
        }
        if (this.emergencyAction != null) {
            this.requiredSlots.addAll(this.emergencyAction.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /**
     * Factory:
     *  - protectedAction runs normally
     *  - trigger is checked continuously
     *  - emergencyAction runs if trigger trips
     */
    public static WatchdogAction monitor(String name,
                                         Action protectedAction,
                                         BooleanSupplier trigger,
                                         Action emergencyAction) {
        return new WatchdogAction(name, protectedAction, trigger, emergencyAction);
    }

    /**
     * Convenience factory matching your example signature (no protected action):
     * Just runs emergencyAction once trigger trips; otherwise does nothing forever.
     * (Usually you'll want the 4-arg version.)
     */
    public static WatchdogAction monitor(String name,
                                         BooleanSupplier trigger,
                                         Action emergencyAction) {
        return new WatchdogAction(name, HoldAction.hold(name + "_idle", now -> {}), trigger, emergencyAction);
    }

    /** Optional: if true, end immediately when trigger trips (still cancels protected). */
    public WatchdogAction failFast(boolean enabled) {
        if (!ensureMutable("failFast")) return this;
        this.failFast = enabled;
        return this;
    }

    public boolean isTripped() {
        return tripped;
    }

    @Override
    public Action reset() {
        super.reset();
        tripped = false;
        if (protectedAction != null) protectedAction.reset();
        if (emergencyAction != null) emergencyAction.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (protectedAction != null && !protectedAction.inTerminalState()) {
            protectedAction.cancel("Canceled because WatchdogAction canceled: " + name);
        }
        if (emergencyAction != null && !emergencyAction.inTerminalState()) {
            emergencyAction.cancel("Canceled because WatchdogAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (trigger == null) {
            endActionWithError("WatchdogAction trigger is null Action=[" + name + "]");
            return;
        }
        if (protectedAction == null) {
            endActionWithError("WatchdogAction protectedAction is null Action=[" + name + "]");
            return;
        }

        // 1) Check trigger first (so we can stop dangerous motion ASAP)
        boolean fired;
        try {
            fired = trigger.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("WatchdogAction trigger threw: " + e.toString());
            return;
        }

        if (!tripped && fired) {
            tripped = true;

            // cancel protected action ASAP
            if (!protectedAction.inTerminalState()) {
                protectedAction.cancel("Watchdog tripped: " + name);
            }

            // if no emergency action, end immediately
            if (emergencyAction == null) {
                endActionWithError("Watchdog tripped (no emergency action) Action=[" + name + "]");
                return;
            }

            // Start emergency action fresh
            emergencyAction.reset();

            if (failFast) {
                // Optionally end right away (emergency still needs to be ticked elsewhere if you want it)
                endActionWithError("Watchdog tripped (failFast): " + name);
                return;
            }
        }

        // 2) If tripped, run emergency action until it ends
        if (tripped) {
            if (emergencyAction == null) {
                endActionWithError("Watchdog tripped (no emergency action) Action=[" + name + "]");
                return;
            }

            ActionState es = emergencyAction.update(nowMillis);

            if (es == ActionState.COMPLETE) {
                endAction(ActionState.COMPLETE);
                return;
            }
            if (es == ActionState.ERROR) {
                endActionWithError("Watchdog emergency failed: " + emergencyAction.getErrorMessage());
                return;
            }
            if (es == ActionState.TIMEOUT) {
                endActionWithTimeout("Watchdog emergency timed out");
                return;
            }
            if (es == ActionState.CANCELED) {
                endActionWithCancel("Watchdog emergency canceled");
            }
            return;
        }

        // 3) Not tripped: run protected action normally
        ActionState ps = protectedAction.update(nowMillis);

        if (ps == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (ps == ActionState.ERROR) {
            endActionWithError("Watchdog protected failed: " + protectedAction.getErrorMessage());
            return;
        }
        if (ps == ActionState.TIMEOUT) {
            endActionWithTimeout("Watchdog protected timed out");
            return;
        }
        if (ps == ActionState.CANCELED) {
            endActionWithCancel("Watchdog protected canceled");
        }
    }
}

/*
Examples:

// Protect a lift from stalling current:
Action emergencyStop = Action.oneShot("stop_lift", now -> liftMotor.setPower(0));
Action safeLift =
    WatchdogAction.monitor(
        "lift_current_watchdog",
        liftUpAction,
        () -> liftMotor.getCurrent() > 4.5,
        emergencyStop
    );

// Protect drivetrain if wheel stuck:
Action stopDrive = Action.oneShot("stop_drive", now -> drive.stop());
Action safeDrive =
    WatchdogAction.monitor(
        "drive_watchdog",
        driveForward,
        () -> driveMotorFL.getCurrent() > 6.0,
        stopDrive
    );


*/