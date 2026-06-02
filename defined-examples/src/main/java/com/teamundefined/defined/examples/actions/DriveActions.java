package com.teamundefined.defined.examples.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.Continuous;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.Subsystem;

import java.util.function.DoubleSupplier;

/**
 * Drive behaviors.
 *
 * <p>{@link #driveTo} is a pure-Java stand-in for the real
 * {@code com.teamundefined.defined.pedro.NavigationAction} (which drives a Pedro
 * {@code Follower}). The shape is identical — issue the path once, complete when the
 * drive is no longer busy — so the example stays runnable on a laptop while teaching
 * the same pattern you'd use on the robot.
 */
public final class DriveActions {
    private DriveActions() {}

    /** Navigate to a target pose, completing when the drive settles (claims DRIVE). */
    public static Action driveTo(DummyRobot r, double x, double y, double headingRad) {
        boolean[] issued = {false};
        return Action.until(String.format("drive_to_%.0f_%.0f", x, y),
                        now -> {
                            if (!issued[0]) {            // issue the path exactly once
                                r.drive.driveTo(x, y, headingRad);
                                issued[0] = true;
                            }
                        },
                        () -> issued[0] && !r.drive.isBusy())
                .requires(Subsystem.DRIVE);
    }

    /** Slot-free monitor: continuous manual drive from a stick. */
    public static Action manualDrive(DummyRobot r, DoubleSupplier forward) {
        return Continuous.forever("manual_drive", now -> r.drive.setManualPower(forward.getAsDouble()));
    }
}
