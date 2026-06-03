package com.teamundefined.defined.example.subsystems;

import com.pedropathing.geometry.Pose;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/**
 * A motor-driven turret that aims at the goal using the robot's pose. Real robots
 * fuse this with Limelight vision; here we aim from odometry for clarity.
 */
public class Turret {
    // Goal location on the field (illustrative).
    private static final double GOAL_X = 72, GOAL_Y = 72;
    private static final double LOCK_TOLERANCE_RAD = Math.toRadians(2);

    private final DcMotorEx motor;
    private boolean tracking = false;
    private double lastErrorRad = Math.PI;

    public Turret(DcMotorEx motor) {
        this.motor = motor;
        motor.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
    }

    public void startTracking() { tracking = true; }
    public void stopTracking() { tracking = false; motor.setPower(0); }
    public boolean isTracking() { return tracking; }
    public boolean isLocked() { return tracking && Math.abs(lastErrorRad) < LOCK_TOLERANCE_RAD; }

    /** Aim toward the goal each loop, given the current robot pose. */
    public void update(Pose robotPose) {
        if (!tracking || robotPose == null) return;
        double desired = Math.atan2(GOAL_Y - robotPose.getY(), GOAL_X - robotPose.getX());
        lastErrorRad = normalize(desired - robotPose.getHeading());
        motor.setPower(Math.max(-0.5, Math.min(0.5, lastErrorRad))); // simple P
    }

    private static double normalize(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
