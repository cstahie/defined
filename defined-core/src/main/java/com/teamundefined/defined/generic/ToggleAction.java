package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * ToggleAction
 *
 * Press-to-toggle between ON and OFF actions using EdgeTriggerAction (rising edge).
 *
 * Typical FTC uses:
 * - intake on/off
 * - shooter spin-up on/off
 * - slow mode on/off
 * - servo latch open/close
 *
 * Behavior:
 * - Each rising edge flips internal boolean state.
 * - When state becomes ON  -> starts onAction
 * - When state becomes OFF -> starts offAction (optional; can be null)
 * - This action is "continuous": it does not complete unless canceled.
 *
 * Example:
 *   Action intakeToggle =
 *       ToggleAction.onPress("intake_toggle", () -> gamepad1.a,
 *           Action.oneShot("intake_on",  now -> intake.setPower(1.0)),
 *           Action.oneShot("intake_off", now -> intake.setPower(0.0))
 *       );
 *
 * If you want "toggle but also hold a continuous action while ON":
 * - use onHold instead of onPress (see factory).

 * Variants:
 *  - onPress:            ON runs onAction, OFF runs offAction (optional)
 *  - onHold:             same + ticks onHold continuously while ON
 *  - onPressCancelable:  ON starts a long-running onAction, OFF cancels it (and optionally runs offAction)
 */
public class ToggleAction extends Action {

    private final BooleanSupplier button;
    private final Action onAction;
    private final Action offAction;
    private final Action onHold;
    private final boolean cancelOnOff;

    private boolean isOn = false;

    // edge detection state
    private boolean initialized = false;
    private boolean last = false;

    // currently running transition action (spin up / spin down / etc)
    private Action current = null;

    private ToggleAction(String name,
                         BooleanSupplier button,
                         Action onAction,
                         Action offAction,
                         Action onHold,
                         boolean cancelOnOff) {
        super(name, now -> {});
        this.button = button;
        this.onAction = onAction;
        this.offAction = offAction;
        this.onHold = onHold;
        this.cancelOnOff = cancelOnOff;

        // NOTE: think carefully about slot propagation. Usually you do NOT want the toggle to hold slots forever.
        // If you want this ToggleAction to be slotted, keep this. If not, remove it and schedule child actions via runner.
        if (this.onAction != null) this.requiredSlots.addAll(this.onAction.requiredSlots());
        if (this.offAction != null) this.requiredSlots.addAll(this.offAction.requiredSlots());
        if (this.onHold != null) this.requiredSlots.addAll(this.onHold.requiredSlots());

        this.step = this::runStep;
        this.isComplete = () -> false; // never completes unless canceled
    }

    public static ToggleAction onPress(String name,
                                       BooleanSupplier button,
                                       Action onAction,
                                       Action offAction) {
        return new ToggleAction(name, button, onAction, offAction, null, false);
    }

    public static ToggleAction onHold(String name,
                                      BooleanSupplier button,
                                      Action onAction,
                                      Action offAction,
                                      Action onHold) {
        return new ToggleAction(name, button, onAction, offAction, onHold, false);
    }

    public static ToggleAction onPressCancelable(String name,
                                                 BooleanSupplier button,
                                                 Action onAction,
                                                 Action offAction) {
        return new ToggleAction(name, button, onAction, offAction, null, true);
    }

    public ToggleAction startOn(boolean startOn) {
        if (!ensureMutable("startOn")) return this;
        this.isOn = startOn;
        return this;
    }

    public boolean isOn() { return isOn; }

    @Override
    public Action reset() {
        super.reset();
        initialized = false;
        last = false;

        // keep isOn as-is (your original behavior)

        // don't hard reset children every time; only reset when we select one to run
        current = null;
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (current != null && !current.inTerminalState()) current.cancel("ToggleAction canceled: " + name);
        if (onHold != null && !onHold.inTerminalState()) onHold.cancel("ToggleAction canceled: " + name);
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        // ---- rising edge detection (re-usable forever) ----
        boolean cur = button.getAsBoolean();
        if (!initialized) {
            initialized = true;
            last = cur;
        } else {
            boolean rising = !last && cur;
            last = cur;

            if (rising) flip(nowMillis);
        }

        // ---- tick current transition action until done ----
        if (current != null && !current.inTerminalState()) {
            current.update(nowMillis);
        }

        // ---- optional continuous hold while ON ----
        if (isOn && onHold != null) {
            onHold.update(nowMillis);
        }
    }

    private void flip(long nowMillis) {
        boolean newOn = !isOn;

        // ON -> OFF
        if (isOn && !newOn) {
            if (cancelOnOff && onAction != null && !onAction.inTerminalState()) {
                onAction.cancel("Toggled OFF: " + name);
            }
            current = offAction;
            if (current != null) current.reset();
            isOn = false;
            return;
        }

        // OFF -> ON
        if (!isOn && newOn) {
            current = onAction;
            if (current != null) current.reset();
            isOn = true;
        }
    }
}

/*
Examples
1. Intake toggle
Action intakeToggle =
    ToggleAction.onPress("intake_toggle", () -> gamepad1.a,
        Action.oneShot("intake_on",  now -> intake.setPower(1.0)),
        Action.oneShot("intake_off", now -> intake.setPower(0.0))
    );

2. Shooter toggle + continuous hold
Action shooterToggle =
    ToggleAction.onHold("shooter_toggle", () -> gamepad1.b,
        Action.oneShot("spinup", now -> shooter.setTargetRpm(3200)),
        Action.oneShot("stop",   now -> shooter.stop()),
        HoldAction.position("rpm_hold", now -> shooter.holdRpm())
    );

3. Start an automation on A, cancel it on next A press:
Action pickupToggle =
    ToggleAction.onPressCancelable(
        "pickup_toggle",
        () -> gamepad1.a,
        pickupAutomation,
        Action.oneShot("stop_intake", now -> intake.stop())
    );
 */