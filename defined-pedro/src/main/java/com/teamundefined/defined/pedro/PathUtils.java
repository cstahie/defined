package com.teamundefined.defined.pedro;

import com.pedropathing.follower.Follower;
import com.pedropathing.geometry.BezierLine;
import com.pedropathing.geometry.Pose;
import com.pedropathing.paths.PathChain;

/**
 * Pedro path helpers used by {@link NavigationAction}, exposed for direct use too.
 *
 * <p>The headline helper, {@link #buildOptimizedPath}, builds a three‑segment path
 * that rotates the robot into an efficient driving orientation, drives straight,
 * then rotates to the final heading — avoiding slow sideways strafing. In Team
 * Undefined's testing this cut path time by ~30‑40% versus a naive {@code BezierLine}.
 */
public final class PathUtils {

    private PathUtils() {}

    /** Which end of the robot leads while driving a path. */
    public enum DriveDirection {
        /** Front of the robot leads (normal forward driving). */
        DRIVE_WITH_FRONT,
        /** Back of the robot leads (reverse driving). */
        DRIVE_WITH_BACK,
        /** Pick whichever needs less total rotation. */
        AUTO
    }

    /** Normalizes an angle (radians) to {@code [-PI, PI]}. */
    public static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * Chooses forward vs. backward driving by comparing total rotation (initial turn
     * to the driving heading + final turn to the target heading) for each option.
     */
    public static DriveDirection detectOptimalDriveDirection(Pose currentPose, Pose targetPose) {
        double dx = targetPose.getX() - currentPose.getX();
        double dy = targetPose.getY() - currentPose.getY();

        if (Math.abs(dx) < 0.1 && Math.abs(dy) < 0.1) {
            return DriveDirection.DRIVE_WITH_FRONT; // already there — just rotate in place
        }

        double directionToTarget = Math.atan2(dy, dx);
        double currentHeading = currentPose.getHeading();
        double targetHeading = targetPose.getHeading();

        double totalForward = Math.abs(normalizeAngle(directionToTarget - currentHeading))
                + Math.abs(normalizeAngle(targetHeading - directionToTarget));

        double backwardHeading = normalizeAngle(directionToTarget + Math.PI);
        double totalBackward = Math.abs(normalizeAngle(backwardHeading - currentHeading))
                + Math.abs(normalizeAngle(targetHeading - backwardHeading));

        return totalForward <= totalBackward
                ? DriveDirection.DRIVE_WITH_FRONT
                : DriveDirection.DRIVE_WITH_BACK;
    }

    /**
     * Build a three‑segment path that minimizes strafing.
     *
     * @param follower the Pedro follower (provides {@code pathBuilder()})
     * @param currentPose start pose
     * @param targetPose end pose (position + final heading)
     * @param direction drive direction, or {@link DriveDirection#AUTO}
     * @param percentageRotateStart fraction (0–1) by which the initial rotation completes
     * @param percentageRotateEnd fraction (0–1) at which the final rotation begins
     */
    public static PathChain buildOptimizedPath(
            Follower follower,
            Pose currentPose,
            Pose targetPose,
            DriveDirection direction,
            double percentageRotateStart,
            double percentageRotateEnd) {

        DriveDirection effective = (direction == DriveDirection.AUTO)
                ? detectOptimalDriveDirection(currentPose, targetPose)
                : direction;

        double dx = targetPose.getX() - currentPose.getX();
        double dy = targetPose.getY() - currentPose.getY();
        double directionAngle = Math.atan2(dy, dx);

        double optimalHeading = (effective == DriveDirection.DRIVE_WITH_FRONT)
                ? directionAngle
                : directionAngle + Math.PI;
        optimalHeading = normalizeAngle(optimalHeading);

        Pose mid1 = new Pose(
                currentPose.getX() + dx * percentageRotateStart,
                currentPose.getY() + dy * percentageRotateStart,
                optimalHeading);
        Pose mid2 = new Pose(
                currentPose.getX() + dx * percentageRotateEnd,
                currentPose.getY() + dy * percentageRotateEnd,
                optimalHeading);

        return follower.pathBuilder()
                .addPath(new BezierLine(currentPose, mid1))
                .setLinearHeadingInterpolation(currentPose.getHeading(), optimalHeading)
                .addPath(new BezierLine(mid1, mid2))
                .setLinearHeadingInterpolation(optimalHeading, optimalHeading)
                .addPath(new BezierLine(mid2, targetPose))
                .setLinearHeadingInterpolation(optimalHeading, targetPose.getHeading())
                .build();
    }
}
