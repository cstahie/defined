package com.teamundefined.defined.example;

import com.pedropathing.geometry.Pose;

/**
 * Named field positions, like a real team's {@code Poses.java}. Coordinates are in
 * Pedro's field units (inches); headings in radians. These are illustrative.
 */
public final class Poses {
    private Poses() {}

    public static final Pose START        = new Pose(8,  8,  0);
    public static final Pose BALL_CLUSTER = new Pose(24, 36, 0);
    public static final Pose SHOOT        = new Pose(60, 60, Math.toRadians(45));
    public static final Pose PARK         = new Pose(12, 12, 0);
}
