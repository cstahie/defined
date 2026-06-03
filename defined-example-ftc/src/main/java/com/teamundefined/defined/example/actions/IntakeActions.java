package com.teamundefined.defined.example.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.generic.ToggleAction;
import com.teamundefined.defined.generic.WaitAction;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Subsystem;

import java.util.function.BooleanSupplier;

/** Intake behaviors — the per-subsystem factory pattern from real TeamCode. */
public final class IntakeActions {
    private IntakeActions() {}

    /** Run the intake for a fixed time (claims INTAKE). On the real robot you'd
     *  instead wait on color/distance sensors detecting the artifacts. */
    public static Action intakeFor(ExampleRobot r, long ms) {
        return new SequentialAction("intake_for_" + ms,
                Action.oneShot("intake_on", now -> r.intake.turnOnSync(false)),
                WaitAction.ms("collect", ms),
                Action.oneShot("intake_off", now -> r.intake.turnOffSync()))
                .requires(Subsystem.INTAKE);
    }

    /** Slot-free monitor: toggle the intake on/off with a button. */
    public static Action toggle(ExampleRobot r, BooleanSupplier button) {
        return ToggleAction.onPress("intake_toggle", button,
                Action.oneShot("on", now -> r.intake.turnOnSync(false)),
                Action.oneShot("off", now -> r.intake.turnOffSync()));
    }
}
