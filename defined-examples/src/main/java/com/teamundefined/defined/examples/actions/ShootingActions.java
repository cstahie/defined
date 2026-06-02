package com.teamundefined.defined.examples.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.generic.WaitUntilAction;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.Subsystem;

/**
 * Shooting behaviors built from Defined primitives — the heart of "structure your
 * code as composable actions".
 */
public final class ShootingActions {
    public static final double SHOOT_VELOCITY = 300;

    private ShootingActions() {}

    /** Spin the flywheel up and resolve once it's at speed (claims FLYWHEEL). */
    public static Action spinUp(DummyRobot r) {
        return Action.until("spin_up",
                        now -> r.flywheel.setTarget(SHOOT_VELOCITY),
                        r.flywheel::isReady)
                .requires(Subsystem.FLYWHEEL);
    }

    /** Open the gate, fire everything loaded, then close it (claims INDEXER). */
    public static Action fireAll(DummyRobot r) {
        return new SequentialAction("fire_all",
                Action.oneShot("open_gate", now -> r.indexer.openGate()),
                WaitUntilAction.until("magazine_empty", () -> r.indexer.balls() == 0),
                Action.oneShot("close_gate", now -> r.indexer.closeGate()))
                .requires(Subsystem.INDEXER);
    }

    /**
     * TeleOp one-button score: spin up, fire what's loaded, idle the flywheel.
     * Claims FLYWHEEL + INDEXER so the runner arbitrates it as one group.
     */
    public static Action shootLoaded(DummyRobot r) {
        return new SequentialAction("shoot_loaded",
                spinUp(r),
                fireAll(r),
                Action.oneShot("flywheel_idle", now -> r.flywheel.setTarget(0)))
                .requires(Subsystem.FLYWHEEL, Subsystem.INDEXER);
    }
}
