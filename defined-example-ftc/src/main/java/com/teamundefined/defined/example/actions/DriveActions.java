package com.teamundefined.defined.example.actions;

import com.pedropathing.geometry.Pose;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Subsystem;
import com.teamundefined.defined.pedro.NavigationAction;

import java.util.function.BooleanSupplier;

/**
 * Drive behaviors — thin wrappers over the real {@link NavigationAction} from
 * {@code defined-pedro}. This is the pattern your real {@code DriveActions} uses
 * ({@code auto_navigateToPosition(...)}).
 */
public final class DriveActions {
    private DriveActions() {}

    /** Autonomous: navigate to a field pose, claiming the DRIVE slot. */
    public static Action navigateTo(ExampleRobot r, Pose target) {
        return NavigationAction.forAuto(r.drive, target)
                .requiresDrive(Subsystem.DRIVE);
    }

    /** Autonomous: minimal-strafe optimized navigation to a pose. */
    public static Action navigateOptimized(ExampleRobot r, Pose target) {
        return NavigationAction.forAutoOptimized(r.drive, target, 0.3, 0.8)
                .requiresDrive(Subsystem.DRIVE);
    }

    /** TeleOp: drive to a pose but bail out the instant the driver moves the stick. */
    public static Action navigateWithOverride(ExampleRobot r, Pose target, BooleanSupplier joystickActive) {
        return NavigationAction.optimizedWithOverride(r.drive, target, 0.3, 0.8, joystickActive)
                .requiresDrive(Subsystem.DRIVE);
    }
}
