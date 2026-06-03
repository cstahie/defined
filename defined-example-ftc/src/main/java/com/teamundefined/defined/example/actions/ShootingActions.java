package com.teamundefined.defined.example.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.generic.WaitAction;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Subsystem;

/** Shooting behaviors composed from Defined primitives. */
public final class ShootingActions {
    public static final double SHOOT_VELOCITY = 1800; // ticks/sec (illustrative)

    private ShootingActions() {}

    /** Spin the flywheel up and resolve once at speed (claims FLYWHEEL). */
    public static Action spinUp(ExampleRobot r) {
        return Action.until("spin_up",
                        now -> r.flywheel.setTargetVelocity(SHOOT_VELOCITY),
                        r.flywheel::isReady)
                .withTimeout(2000)
                .requires(Subsystem.FLYWHEEL);
    }

    /** Open the gates, let the artifacts feed out, then close (claims INDEXER). */
    public static Action fireAll(ExampleRobot r) {
        return new SequentialAction("fire_all",
                Action.oneShot("open_gates", now -> r.indexer.openAll()),
                WaitAction.ms("feed", 600),
                Action.oneShot("close_gates", now -> r.indexer.closeAll()))
                .requires(Subsystem.INDEXER);
    }

    /** One-button score: spin up, fire, idle the flywheel (claims FLYWHEEL + INDEXER). */
    public static Action shootLoaded(ExampleRobot r) {
        return new SequentialAction("shoot_loaded",
                spinUp(r),
                fireAll(r),
                Action.oneShot("flywheel_idle", now -> r.flywheel.stopFlywheelSync()))
                .requires(Subsystem.FLYWHEEL, Subsystem.INDEXER);
    }
}
