package com.teamundefined.defined.examples.opmodes;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.ParallelAction;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.actions.DriveActions;
import com.teamundefined.defined.examples.actions.IntakeActions;
import com.teamundefined.defined.examples.actions.ShootingActions;
import com.teamundefined.defined.examples.actions.TurretActions;

/**
 * Example AUTONOMOUS routine — one composed {@link Action} that runs start to finish
 * with no driver input. This is the shape your real auto takes: a
 * {@link SequentialAction} of navigation + scoring steps, with a
 * {@link ParallelAction} to overlap work (spinning up while driving) and save time.
 *
 * <p>On a real robot, swap {@link DriveActions#driveTo} for the Pedro
 * {@code NavigationAction} and call {@code follower.update()} each loop.
 */
public final class DummyAuto {
    private DummyAuto() {}

    /** Build the full auto routine (drive → intake → drive+spin → aim → fire → park). */
    public static Action build(DummyRobot r) {
        return new SequentialAction("auto",
                DriveActions.driveTo(r, 24, 36, 0),                 // to the ball cluster
                IntakeActions.intakeUntil(r, 3),                    // gulp all three
                ParallelAction.all("approach_and_spin",            // overlap to save time
                        DriveActions.driveTo(r, 60, 60, Math.toRadians(45)),
                        ShootingActions.spinUp(r)),
                TurretActions.aim(r),                               // lock the goal
                ShootingActions.fireAll(r),                         // score the cluster
                Action.oneShot("flywheel_off", now -> r.flywheel.setTarget(0)),
                DriveActions.driveTo(r, 12, 12, 0));                // park
    }

    /** Run the routine against a fresh simulated robot and return it. */
    public static DummyRobot run(boolean verbose) {
        DummyRobot r = new DummyRobot();
        Action auto = build(r);

        long now = 0;
        for (int i = 0; i < 4000 && !auto.inTerminalState(); i++) {
            r.tick(now);
            auto.update(now);
            if (verbose && i % 40 == 0) System.out.println("  t=" + now + "ms  " + r.telemetry());
            now += 20;
        }
        if (verbose) System.out.println("  AUTO " + auto.getState() + " — scored " + r.indexer.scored());
        return r;
    }
}
