package com.teamundefined.defined.examples;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.Continuous;
import com.teamundefined.defined.generic.ParallelAction;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.generic.ToggleAction;
import com.teamundefined.defined.generic.WaitUntilAction;
import com.teamundefined.defined.examples.sim.FakeGamepad;
import com.teamundefined.defined.examples.sim.FakeRobot;
import com.teamundefined.defined.examples.sim.Subsystem;

/**
 * The "playbook": every automated behavior on the example robot, built from
 * Defined action primitives. This is the pattern a real team follows — keep
 * subsystem hardware in one place and express behavior as composable actions.
 */
public final class RobotActions {

    public static final double SHOOT_VELOCITY = 300;

    private RobotActions() {}

    /** Run the intake until the magazine holds {@code targetBalls} balls. */
    public static Action loadBalls(FakeRobot robot, int targetBalls) {
        return Action.until("load_balls",
                        now -> robot.setIntake(true),
                        () -> robot.ballsLoaded() >= targetBalls)
                .withOnComplete(now -> robot.setIntake(false))
                .requires(Subsystem.INTAKE);
    }

    /** Spin the flywheel up and resolve once it is at speed. */
    public static Action spinUp(FakeRobot robot, double velocity) {
        return Action.until("spin_up",
                        now -> robot.setFlywheelTarget(velocity),
                        robot::isFlywheelReady)
                .requires(Subsystem.FLYWHEEL);
    }

    /** Open the gate, wait until the magazine is empty, then close it. */
    public static Action fireAll(FakeRobot robot) {
        return new SequentialAction("fire_all",
                Action.oneShot("open_gate", now -> robot.setGate(true)),
                WaitUntilAction.until("magazine_empty", () -> robot.ballsLoaded() == 0),
                Action.oneShot("close_gate", now -> robot.setGate(false)))
                .requires(Subsystem.INDEXER);
    }

    /**
     * The full one-button scoring routine: load three balls while spinning up the
     * flywheel in parallel (saving cycle time), then fire everything.
     */
    public static Action scoreThree(FakeRobot robot) {
        Action loadAndSpin = ParallelAction.all("load_and_spin",
                loadBalls(robot, 3),
                spinUp(robot, SHOOT_VELOCITY));

        return new SequentialAction("score_three",
                loadAndSpin,
                fireAll(robot),
                Action.oneShot("flywheel_off", now -> robot.setFlywheelTarget(0)));
    }

    /**
     * TeleOp scoring group: spin up, fire whatever is loaded, then idle the
     * flywheel. Requires FLYWHEEL + INDEXER so the {@code ActionRunner} arbitrates
     * it against any other action wanting those subsystems.
     */
    public static Action spinUpAndFire(FakeRobot robot) {
        return new SequentialAction("spin_and_fire",
                spinUp(robot, SHOOT_VELOCITY),
                fireAll(robot),
                Action.oneShot("flywheel_off", now -> robot.setFlywheelTarget(0)))
                .requires(Subsystem.FLYWHEEL, Subsystem.INDEXER);
    }

    // ---- TeleOp monitors (slot-free, run forever) ----

    /** Continuous robot-centric drive from the left stick. */
    public static Action driveControl(FakeRobot robot, FakeGamepad gp) {
        return Continuous.forever("drive", now -> robot.setDrivePower(-gp.leftStickY));
    }

    /** Toggle the intake on/off with the Cross (X) button. */
    public static Action intakeToggle(FakeRobot robot, FakeGamepad gp) {
        return ToggleAction.onPress("intake_toggle", () -> gp.cross,
                Action.oneShot("intake_on", now -> robot.setIntake(true)),
                Action.oneShot("intake_off", now -> robot.setIntake(false)));
    }
}
