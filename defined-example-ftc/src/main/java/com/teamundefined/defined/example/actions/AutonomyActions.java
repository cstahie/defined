package com.teamundefined.defined.example.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.ParallelAction;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Poses;

/**
 * High-level autonomous routines, assembled from the per-subsystem actions — exactly
 * how a real {@code AutonomyActions.autonomousRoutine(robot)} is built. The OpMode
 * starts this via {@code runner.startGroup(...)}.
 */
public final class AutonomyActions {
    private AutonomyActions() {}

    /** Drive to a cluster, gulp it, drive to the goal while spinning up, aim, fire, park. */
    public static Action autonomousRoutine(ExampleRobot r) {
        return new SequentialAction("auto_routine",
                DriveActions.navigateTo(r, Poses.BALL_CLUSTER),
                IntakeActions.intakeFor(r, 1200),
                ParallelAction.all("approach_and_spin",
                        DriveActions.navigateOptimized(r, Poses.SHOOT),
                        ShootingActions.spinUp(r)),
                TurretActions.aim(r),
                ShootingActions.fireAll(r),
                Action.oneShot("flywheel_off", now -> r.flywheel.stopFlywheelSync()),
                DriveActions.navigateTo(r, Poses.PARK));
    }
}
