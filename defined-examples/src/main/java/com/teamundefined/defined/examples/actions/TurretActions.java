package com.teamundefined.defined.examples.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.ToggleAction;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.Subsystem;

import java.util.function.BooleanSupplier;

/** Turret behaviors: lock onto the goal, or toggle continuous tracking. */
public final class TurretActions {
    private TurretActions() {}

    /** Track until the turret is on target, then complete (claims TURRET). */
    public static Action aim(DummyRobot r) {
        return Action.until("aim",
                        now -> r.turret.startTracking(),
                        r.turret::onTarget)
                .requires(Subsystem.TURRET);
    }

    /** Slot-free monitor: toggle continuous tracking on/off with a button. */
    public static Action trackToggle(DummyRobot r, BooleanSupplier button) {
        return ToggleAction.onPress("turret_toggle", button,
                Action.oneShot("track_on", now -> r.turret.startTracking()),
                Action.oneShot("track_off", now -> r.turret.stopTracking()));
    }
}
