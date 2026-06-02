package com.teamundefined.defined.examples;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import com.teamundefined.defined.runner.ActionRunner;
import com.teamundefined.defined.examples.actions.DriveActions;
import com.teamundefined.defined.examples.actions.ShootingActions;
import com.teamundefined.defined.examples.opmodes.DummyAuto;
import com.teamundefined.defined.examples.opmodes.DummyTeleOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the example robot's autonomous and teleop actually work against the
 * simulator — so the "how to structure your code" example is genuinely functional.
 */
class DummyRobotTest {

    @Test
    void autonomousNavigatesIntakesAndScores() {
        DummyRobot r = DummyAuto.run(false);
        assertEquals(3, r.indexer.scored(), "auto gulps a 3-ball cluster and scores it");
        assertEquals(0, r.indexer.balls());
        // Ended parked near (12, 12).
        assertEquals(12, r.drive.x, 0.5);
        assertEquals(12, r.drive.y, 0.5);
    }

    @Test
    void teleopScoresAfterDriverInput() {
        DummyRobot r = DummyTeleOp.run(false);
        assertTrue(r.indexer.scored() >= 3, "intake + score button puts the cluster through the goal");
        assertTrue(r.turret.isTracking(), "Square toggled turret tracking on");
    }

    @Test
    void driveToCompletesWhenPoseReached() {
        DummyRobot r = new DummyRobot();
        Action nav = DriveActions.driveTo(r, 30, 20, 0);
        long now = 0;
        for (int i = 0; i < 200 && !nav.inTerminalState(); i++) {
            r.tick(now);
            nav.update(now);
            now += 20;
        }
        assertEquals(ActionState.COMPLETE, nav.getState());
        assertEquals(30, r.drive.x, 0.5);
        assertEquals(20, r.drive.y, 0.5);
    }

    @Test
    void runnerArbitratesShootingGroupAgainstFlywheelConflict() {
        DummyRobot r = new DummyRobot();
        ActionRunner runner = new ActionRunner();

        boolean[] holdCanceled = {false};
        Action hold = Action.until("hold_flywheel", n -> r.flywheel.setTarget(150), () -> false)
                .requires(Subsystem.FLYWHEEL)
                .withOnCancel(n -> holdCanceled[0] = true);

        runner.startGroup(hold);
        runner.update(0);
        assertTrue(runner.isSlotInUse(Subsystem.FLYWHEEL));

        // shootLoaded needs FLYWHEEL too → must preempt the hold.
        runner.startGroup(ShootingActions.shootLoaded(r));
        runner.update(20);
        assertTrue(holdCanceled[0], "the flywheel hold is cancelled to free the slot for shooting");
    }
}
