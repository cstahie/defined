package com.teamundefined.defined.example.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.ToggleAction;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Subsystem;

import java.util.function.BooleanSupplier;

/** Turret behaviors: lock onto the goal, or toggle continuous tracking. */
public final class TurretActions {
    private TurretActions() {}

    /** Track until locked on the goal, then complete (claims TURRET). */
    public static Action aim(ExampleRobot r) {
        return Action.until("aim",
                        now -> r.turret.startTracking(),
                        r.turret::isLocked)
                .withTimeout(1500)
                .requires(Subsystem.TURRET);
    }

    /** Slot-free monitor: toggle continuous tracking on/off with a button. */
    public static Action trackToggle(ExampleRobot r, BooleanSupplier button) {
        return ToggleAction.onPress("turret_toggle", button,
                Action.oneShot("track_on", now -> r.turret.startTracking()),
                Action.oneShot("track_off", now -> r.turret.stopTracking()));
    }
}
