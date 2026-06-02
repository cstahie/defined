package com.teamundefined.defined.examples;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import com.teamundefined.defined.runner.ActionRunner;
import com.teamundefined.defined.examples.sim.FakeRobot;
import com.teamundefined.defined.examples.sim.Subsystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the example robot's behaviors actually work end-to-end against the
 * simulator — the "functional robot example" is genuinely functional and tested.
 */
class SimpleRobotDemoTest {

    @Test
    void autonomousScoresExactlyThreeBalls() {
        FakeRobot robot = SimpleRobotDemo.runAutonomous();
        assertEquals(3, robot.ballsScored(), "auto loads and fires all three artifacts");
        assertEquals(0, robot.ballsLoaded());
        assertFalse(robot.isFlywheelReady(), "flywheel idled after the routine");
    }

    @Test
    void teleopScoresAfterDriverInput() {
        FakeRobot robot = SimpleRobotDemo.runTeleop();
        assertTrue(robot.ballsScored() >= 3, "driver intake + score button puts balls through the goal");
    }

    @Test
    void scoreThreeIsADeterministicComposedAction() {
        FakeRobot robot = new FakeRobot();
        Action auto = RobotActions.scoreThree(robot);

        long now = 0;
        while (!auto.inTerminalState() && now < 10_000) {
            robot.tick(now);
            auto.update(now);
            now += 20;
        }
        assertEquals(ActionState.COMPLETE, auto.getState());
        assertEquals(3, robot.ballsScored());
    }

    @Test
    void runnerArbitratesFlywheelSlotBetweenConflictingActions() {
        FakeRobot robot = new FakeRobot();
        ActionRunner runner = new ActionRunner();

        // A long-running flywheel hold...
        boolean[] holdCanceled = {false};
        Action hold = Action.until("hold_flywheel",
                        n -> robot.setFlywheelTarget(150), () -> false)
                .requires(Subsystem.FLYWHEEL)
                .withOnCancel(n -> holdCanceled[0] = true);

        runner.startGroup(hold);
        runner.update();
        assertEquals(ActionState.RUNNING, hold.getState());
        assertTrue(runner.isSlotInUse(Subsystem.FLYWHEEL));

        // ...is preempted when a new action claims the same slot.
        Action override = Action.oneShot("rev", n -> robot.setFlywheelTarget(300))
                .requires(Subsystem.FLYWHEEL);
        runner.startGroup(override);
        runner.update();
        assertTrue(holdCanceled[0], "the holding action is cancelled to free the slot");

        runner.update();
        assertEquals(ActionState.COMPLETE, override.getState());
    }
}
