package com.teamundefined.defined.examples.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.ToggleAction;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.Subsystem;

import java.util.function.BooleanSupplier;

/**
 * Intake behaviors. Mirrors the per-subsystem "*Actions" factory pattern from
 * Team Undefined's real TeamCode (IntakeActions, ShootingActions, ...).
 */
public final class IntakeActions {
    private IntakeActions() {}

    /** Run the intake until the magazine holds {@code target} balls (claims INTAKE). */
    public static Action intakeUntil(DummyRobot r, int target) {
        return Action.until("intake_until_" + target,
                        now -> r.intake.on(),
                        () -> r.indexer.balls() >= target)
                .withOnComplete(now -> r.intake.off())
                .requires(Subsystem.INTAKE);
    }

    /** Slot-free monitor: toggle the intake on/off each time {@code button} is pressed. */
    public static Action toggle(DummyRobot r, BooleanSupplier button) {
        return ToggleAction.onPress("intake_toggle", button,
                Action.oneShot("intake_on", now -> r.intake.on()),
                Action.oneShot("intake_off", now -> r.intake.off()));
    }
}
