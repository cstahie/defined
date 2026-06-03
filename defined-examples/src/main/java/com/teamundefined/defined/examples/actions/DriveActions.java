package com.teamundefined.defined.examples.actions;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.generic.Continuous;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.Subsystem;

import java.util.function.DoubleSupplier;

/**
 * Drive behaviors for the <b>desktop</b> demo.
 *
 * <p><b>NOTE:</b> {@link #driveTo} is a pure-Java <i>stand-in</i> so this module can run
 * on a laptop with no hardware. It is NOT how you drive a real robot. On an actual
 * robot use the real {@code NavigationAction} from {@code defined-pedro} — see
 * {@code defined-example-ftc}'s {@code DriveActions.navigateTo(...)}, which wraps
 * {@code NavigationAction.forAuto(follower, pose)}. The action <i>shape</i> is identical
 * (issue once, complete when the drive is no longer busy), which is the point.
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
