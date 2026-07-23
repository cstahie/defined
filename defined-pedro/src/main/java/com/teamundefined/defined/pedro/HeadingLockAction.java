package com.teamundefined.defined.pedro;

import com.pedropathing.follower.Follower;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Log;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Field-centric heading control that works <em>with</em> Pedro's tuned heading PID
 * instead of fighting it: the right stick's direction picks a target heading and
 * Pedro's {@link Follower#turnTo(double)} rotates the robot there. Moving the left
 * stick (translation) instantly hands control back to normal teleop.
 *
 * <p>This is a slot-free monitor — it does not claim the DRIVE slot, because it
 * cooperates with Pedro rather than replacing the drive.
 *
 * <pre>{@code
 * runner.addMonitor(new HeadingLockAction.Builder(follower)
 *         .rightStick(() -> -gamepad1.right_stick_x, () -> -gamepad1.right_stick_y)
 *         .leftStickActive(() -> Math.hypot(gamepad1.left_stick_x, gamepad1.left_stick_y) > 0.1)
 *         .snapTo45(true)
 *         .build());
 * }</pre>
 */
public class HeadingLockAction extends Action {
    private static final String TAG = "HeadingLockAction";

    private final Follower follower;
    private final DoubleSupplier rightStickX;
    private final DoubleSupplier rightStickY;
    private final BooleanSupplier leftStickActive;
    private final BooleanSupplier enabled;
    // Suppliers, not values: these are typically live dashboard knobs, so they must be
    // read every tick rather than captured when the action is built.
    private final DoubleSupplier deadband;
    private final BooleanSupplier snapTo45;

    private boolean isLocking = false;
    private double targetHeading = 0;

    private HeadingLockAction(Builder b) {
        super("heading_lock", now -> {});
        this.follower = b.follower;
        this.rightStickX = b.rightStickX;
        this.rightStickY = b.rightStickY;
        this.leftStickActive = b.leftStickActive;
        this.enabled = b.enabled;
        this.deadband = b.deadband;
        this.snapTo45 = b.snapTo45;

        this.step = this::runStep;
        this.isComplete = () -> false; // continuous monitor
    }

    private void runStep(long nowMillis) {
        if (enabled != null && !enabled.getAsBoolean()) {
            if (isLocking) {
                follower.startTeleopDrive();
                isLocking = false;
            }
            return;
        }

        double rx = rightStickX.getAsDouble();
        double ry = rightStickY.getAsDouble();
        double magnitude = Math.hypot(rx, ry);

        // Left-stick (translation) override: return to normal teleop immediately.
        if (leftStickActive != null && leftStickActive.getAsBoolean() && isLocking) {
            Log.i(TAG, "Left stick override - returning to teleop");
            follower.startTeleopDrive();
            isLocking = false;
            return;
        }

        if (magnitude > deadband.getAsDouble()) {
            double joystickAngle = Math.atan2(rx, ry); // up = 0, right = +90°
            if (snapTo45.getAsBoolean()) joystickAngle = snapTo45Degrees(joystickAngle);

            double angleDiff = Math.abs(normalizeAngle(joystickAngle - targetHeading));
            if (!isLocking || angleDiff > Math.toRadians(10)) {
                targetHeading = joystickAngle;
                follower.turnTo(targetHeading);
                isLocking = true;
            }
        } else if (isLocking && !follower.isBusy()) {
            follower.startTeleopDrive();
            isLocking = false;
        }
    }

    private static double snapTo45Degrees(double angle) {
        double snapped = Math.round(Math.toDegrees(angle) / 45.0) * 45.0;
        return Math.toRadians(snapped);
    }

    private static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    @Override
    public Action reset() {
        super.reset();
        isLocking = false;
        targetHeading = 0;
        return this;
    }

    /** Fluent builder — only {@code follower} and the right-stick suppliers are required. */
    public static class Builder {
        private final Follower follower;
        private DoubleSupplier rightStickX = () -> 0;
        private DoubleSupplier rightStickY = () -> 0;
        private BooleanSupplier leftStickActive = () -> false;
        private BooleanSupplier enabled = null;
        private DoubleSupplier deadband = () -> 0.1;
        private BooleanSupplier snapTo45 = () -> false;

        public Builder(Follower follower) { this.follower = follower; }

        public Builder rightStick(DoubleSupplier x, DoubleSupplier y) {
            this.rightStickX = x; this.rightStickY = y; return this;
        }
        public Builder leftStickActive(BooleanSupplier s) { this.leftStickActive = s; return this; }
        public Builder enabledWhen(BooleanSupplier s) { this.enabled = s; return this; }

        public Builder deadband(double d) { this.deadband = () -> d; return this; }
        public Builder snapTo45(boolean s) { this.snapTo45 = () -> s; return this; }

        /** Deadband read every tick — use for a live-tunable dashboard value. */
        public Builder deadband(DoubleSupplier d) { if (d != null) this.deadband = d; return this; }

        /** 45° snapping read every tick — use for a live-tunable dashboard toggle. */
        public Builder snapTo45(BooleanSupplier s) { if (s != null) this.snapTo45 = s; return this; }

        public HeadingLockAction build() { return new HeadingLockAction(this); }
    }
}
