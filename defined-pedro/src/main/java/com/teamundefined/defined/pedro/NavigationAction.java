package com.teamundefined.defined.pedro;

import com.pedropathing.follower.Follower;
import com.pedropathing.geometry.BezierLine;
import com.pedropathing.geometry.Pose;
import com.pedropathing.paths.PathChain;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Log;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Drive to a target pose (or along a {@link PathChain}) using Pedro Pathing, as a
 * composable Defined {@link Action}.
 *
 * <p>This is the full‑featured navigation action extracted from Team Undefined's
 * robot, made generic over Pedro's {@link Follower} (it no longer depends on any
 * team‑specific classes). Features:
 *
 * <ul>
 *   <li><b>A→B navigation</b> — pass a target {@link Pose} and it builds a
 *       {@link BezierLine} from the current pose with linear heading interpolation.</li>
 *   <li><b>Pre‑built or deferred paths</b> — pass a {@link PathChain}, or a
 *       {@link Supplier} that builds one at execution time using the live pose.</li>
 *   <li><b>Optimized paths</b> — {@code forAutoOptimized(...)} builds a three‑segment
 *       path that minimizes strafing (see {@link PathUtils}).</li>
 *   <li><b>Joystick override</b> — instantly cancels and hands control back to the
 *       driver when a supplied condition becomes true (TeleOp safety).</li>
 *   <li><b>Waypoints</b> — fire callbacks as the robot passes field coordinates,
 *       without expensive path queries (see {@link Waypoint}).</li>
 *   <li><b>Heading‑tolerant completion</b> — completes only when within position and
 *       (if an explicit heading target was given) heading tolerance.</li>
 * </ul>
 *
 * <p>It requires the DRIVE {@link com.teamundefined.defined.Slot} you assign via
 * {@link #requiresDrive(com.teamundefined.defined.Slot)} so the {@code ActionRunner}
 * guarantees only one navigation runs at a time. As always with Pedro, keep calling
 * {@link Follower#update()} every loop.
 */
public class NavigationAction extends Action {
    private static final String TAG = "NavigationAction";
    private static final double HEADING_TOLERANCE_RAD = Math.toRadians(3.0);

    /**
     * A coordinate along the path that fires a callback when the robot passes its
     * closest approach. Detection is O(1) per tick — it watches the distance stop
     * decreasing — so there are no path searches.
     */
    public static class Waypoint {
        public final double x;
        public final double y;
        public volatile boolean reached;
        public final LongConsumer onReached;

        private double lastDistance = Double.MAX_VALUE;
        private boolean hasSeenDecrease = false;

        public Waypoint(double x, double y, LongConsumer onReached) {
            this.x = x;
            this.y = y;
            this.onReached = onReached;
        }

        public Waypoint(double x, double y) {
            this(x, y, null);
        }

        boolean checkReached(double currentDistance) {
            if (reached) return false;
            if (lastDistance == Double.MAX_VALUE) {
                lastDistance = currentDistance;
                return false;
            }
            if (currentDistance < lastDistance) hasSeenDecrease = true;
            boolean passed = hasSeenDecrease && (currentDistance > lastDistance);
            lastDistance = currentDistance;
            return passed;
        }

        void reset() {
            reached = false;
            lastDistance = Double.MAX_VALUE;
            hasSeenDecrease = false;
        }
    }

    private final Follower follower;
    private final Pose targetPose;       // null when using a PathChain
    private final boolean usePrebuiltPath;
    private final BooleanSupplier joystickOverride;

    private PathChain path;
    private boolean pathStarted = false;
    private Supplier<PathChain> pathChainSupplier = null;

    private List<Waypoint> waypoints = null;
    private int currentWaypointIndex = 0;

    private Pose finalTargetPose = null;
    private Supplier<Pose> finalPoseSupplier = null;
    private Double headingOverride = null;

    private double poseTolerance = 2.0;
    private boolean holdEnd = false;
    private DoubleSupplier speedSupplier = () -> 1.0;
    /** When true, on finish/cancel/error the action breaks following and resumes teleop drive. */
    private boolean resumeTeleOpOnFinish = true;

    // ---- Constructors ----

    /** Simple A→B navigation: builds a BezierLine from the current pose to {@code targetPose}. */
    public NavigationAction(String name, Follower follower, Pose targetPose, BooleanSupplier joystickOverride) {
        super(name, now -> {});
        this.follower = follower;
        this.targetPose = targetPose;
        this.usePrebuiltPath = false;
        this.joystickOverride = (joystickOverride != null) ? joystickOverride : () -> false;
        wireCallbacks();
    }

    /** Navigation along a pre‑built {@link PathChain}. */
    public NavigationAction(String name, Follower follower, PathChain pathChain, BooleanSupplier joystickOverride) {
        super(name, now -> {});
        this.follower = follower;
        this.targetPose = null;
        this.usePrebuiltPath = true;
        this.path = pathChain;
        this.joystickOverride = (joystickOverride != null) ? joystickOverride : () -> false;
        wireCallbacks();
    }

    private void wireCallbacks() {
        this.step = this::runStep;
        this.isComplete = this::isNavigationComplete;
        this.onStart = this::handleStart;
        this.onComplete = this::handleFinishResumeDrive;
        this.onCancel = this::handleFinishResumeDrive;
        this.onError = this::handleFinishResumeDrive;
    }

    // ---- Fluent configuration ----

    /** Require a DRIVE slot so the runner enforces exclusivity. */
    public NavigationAction requiresDrive(com.teamundefined.defined.Slot driveSlot) {
        super.requires(driveSlot);
        return this;
    }

    /** Set the final pose used for heading/position tolerance checks (for PathChains). */
    public NavigationAction withFinalPose(Pose pose) { this.finalTargetPose = pose; return this; }

    /** Set a final pose resolved at execution time (after a deferred chain builds). */
    public NavigationAction withDynamicFinalPose(Supplier<Pose> poseSupplier) { this.finalPoseSupplier = poseSupplier; return this; }

    /** End at this heading (radians) instead of the target pose's heading. */
    public NavigationAction withHeadingOverride(double heading) { this.headingOverride = heading; return this; }

    /** Attach waypoint triggers, in the order they are encountered. */
    public NavigationAction withTriggers(List<Waypoint> waypoints) {
        this.waypoints = waypoints;
        this.currentWaypointIndex = 0;
        return this;
    }

    /** Position tolerance (field units) for completion. Default 2.0. */
    public NavigationAction withPoseTolerance(double tolerance) { this.poseTolerance = tolerance; return this; }

    /** Whether Pedro should actively hold the final pose. */
    public NavigationAction holdEnd(boolean hold) { this.holdEnd = hold; return this; }

    /** Drive speed (0–1) supplier, sampled when the path starts. */
    public NavigationAction withSpeed(DoubleSupplier speedSupplier) {
        if (speedSupplier != null) this.speedSupplier = speedSupplier;
        return this;
    }

    /**
     * Whether to break following and resume teleop drive when the action ends.
     * Set {@code false} in autonomous so Pedro keeps control between consecutive
     * paths (eliminating a stop‑wait‑accelerate cycle). Default {@code true}.
     */
    public NavigationAction resumeTeleOpOnFinish(boolean resume) { this.resumeTeleOpOnFinish = resume; return this; }

    // ---- Completion logic ----

    private Pose getEffectiveTargetPose() {
        Pose effective = null;
        if (finalTargetPose != null) effective = finalTargetPose;
        else if (targetPose != null) effective = targetPose;
        else if (path != null) effective = path.endPose();

        if (effective != null && headingOverride != null) {
            effective = new Pose(effective.getX(), effective.getY(), headingOverride);
        }
        return effective;
    }

    private boolean hasExplicitHeadingTarget() {
        return finalTargetPose != null || targetPose != null || headingOverride != null;
    }

    private boolean isAtTarget() {
        Pose target = getEffectiveTargetPose();
        if (target == null) return false;
        Pose p = follower.getPose();
        boolean positionOk = Math.abs(p.getX() - target.getX()) < poseTolerance
                && Math.abs(p.getY() - target.getY()) < poseTolerance;
        if (!positionOk) return false;
        if (!hasExplicitHeadingTarget()) return true;
        return Math.abs(PathUtils.normalizeAngle(p.getHeading() - target.getHeading())) < HEADING_TOLERANCE_RAD;
    }

    private boolean isNavigationComplete() {
        if (!pathStarted) return false;
        if (isAtTarget()) return true;
        if (follower.isBusy()) return false;

        // Follower idle: accept only if within tolerance of the (optional) target.
        Pose effectiveTarget = getEffectiveTargetPose();
        if (effectiveTarget != null) {
            Pose currentPose = follower.getPose();
            if (Math.abs(currentPose.getX() - effectiveTarget.getX()) > poseTolerance
                    || Math.abs(currentPose.getY() - effectiveTarget.getY()) > poseTolerance) {
                return false;
            }
            if (hasExplicitHeadingTarget()) {
                double headingError = Math.abs(PathUtils.normalizeAngle(
                        currentPose.getHeading() - effectiveTarget.getHeading()));
                if (headingError > HEADING_TOLERANCE_RAD) return false;
            }
        }
        return true;
    }

    // ---- Step / lifecycle ----

    private void runStep(long nowMillis) {
        if (follower == null) {
            endActionWithError("NavigationAction follower is null Action=[" + name + "]");
            return;
        }

        if (joystickOverride.getAsBoolean()) {
            follower.breakFollowing();
            follower.startTeleopDrive();
            endActionWithCancel("Joystick override detected");
            return;
        }

        if (!pathStarted && path != null) {
            follower.followPath(path, speedSupplier.getAsDouble(), holdEnd);
            pathStarted = true;
        }

        if (waypoints != null && currentWaypointIndex < waypoints.size()) {
            Waypoint current = waypoints.get(currentWaypointIndex);
            if (!current.reached) {
                Pose robotPose = follower.getPose();
                double dx = current.x - robotPose.getX();
                double dy = current.y - robotPose.getY();
                double distance = Math.hypot(dx, dy);
                if (current.checkReached(distance)) {
                    current.reached = true;
                    if (current.onReached != null) current.onReached.accept(nowMillis);
                    currentWaypointIndex++;
                }
            }
        }
    }

    private void handleStart(long nowMillis) {
        Pose currentPose = follower.getPose();

        if (usePrebuiltPath) {
            if (path == null && pathChainSupplier != null) {
                path = pathChainSupplier.get();
            }
            if (finalTargetPose == null && finalPoseSupplier != null) {
                finalTargetPose = finalPoseSupplier.get();
            }
            if (path == null || path.size() == 0) {
                endActionWithError("PathChain is null or empty Action=[" + name + "]");
                return;
            }
        } else {
            double finalHeading = (headingOverride != null) ? headingOverride : targetPose.getHeading();
            path = follower.pathBuilder()
                    .addPath(new BezierLine(currentPose, targetPose))
                    .setLinearHeadingInterpolation(currentPose.getHeading(), finalHeading)
                    .build();
        }
        Log.i(TAG, () -> "Navigation start: " + name);
    }

    private void handleFinishResumeDrive(long nowMillis) {
        if (resumeTeleOpOnFinish && follower != null) {
            follower.breakFollowing();
            follower.startTeleopDrive();
        }
    }

    @Override
    public Action reset() {
        super.reset();
        if (!usePrebuiltPath) path = null;
        pathStarted = false;
        currentWaypointIndex = 0;
        if (waypoints != null) for (Waypoint w : waypoints) w.reset();
        return this;
    }

    public Pose getFinalPose() {
        if (targetPose != null) return targetPose;
        if (path != null) return path.endPose();
        return null;
    }

    // ---- Factories ----

    /** TeleOp A→B with joystick override. */
    public static NavigationAction toPreset(Follower follower, Pose targetPose, BooleanSupplier joystickOverride) {
        String name = String.format("navigate_to_%.0f_%.0f", targetPose.getX(), targetPose.getY());
        return new NavigationAction(name, follower, targetPose, joystickOverride);
    }

    /** Autonomous A→B (no override). */
    public static NavigationAction forAuto(Follower follower, Pose targetPose) {
        String name = String.format("auto_nav_%.1f_%.1f", targetPose.getX(), targetPose.getY());
        return new NavigationAction(name, follower, targetPose, () -> false).resumeTeleOpOnFinish(false);
    }

    /** Autonomous PathChain (no override). */
    public static NavigationAction forAuto(Follower follower, PathChain pathChain) {
        return new NavigationAction("auto_nav_complex", follower, pathChain, () -> false).resumeTeleOpOnFinish(false);
    }

    /** Autonomous named PathChain (no override). */
    public static NavigationAction forAuto(String name, Follower follower, PathChain pathChain) {
        return new NavigationAction(name, follower, pathChain, () -> false).resumeTeleOpOnFinish(false);
    }

    /** Autonomous deferred PathChain — built at execution time from the live pose. */
    public static NavigationAction forAutoDeferred(String name, Follower follower, Supplier<PathChain> chainSupplier) {
        NavigationAction action = new NavigationAction(name, follower, (PathChain) null, () -> false)
                .resumeTeleOpOnFinish(false);
        action.pathChainSupplier = chainSupplier;
        return action;
    }

    /** Autonomous optimized (minimal‑strafe) navigation. */
    public static NavigationAction forAutoOptimized(Follower follower, Pose targetPose,
                                                    PathUtils.DriveDirection direction,
                                                    double percentageRotateStart, double percentageRotateEnd) {
        Pose currentPose = follower.getPose();
        PathChain optimized = PathUtils.buildOptimizedPath(
                follower, currentPose, targetPose, direction, percentageRotateStart, percentageRotateEnd);
        String name = String.format("auto_nav_optimized_%.0f_%.0f", targetPose.getX(), targetPose.getY());
        return new NavigationAction(name, follower, optimized, () -> false)
                .withFinalPose(targetPose)
                .resumeTeleOpOnFinish(false);
    }

    /** Autonomous optimized navigation with AUTO direction detection. */
    public static NavigationAction forAutoOptimized(Follower follower, Pose targetPose,
                                                    double percentageRotateStart, double percentageRotateEnd) {
        return forAutoOptimized(follower, targetPose, PathUtils.DriveDirection.AUTO,
                percentageRotateStart, percentageRotateEnd);
    }

    /** Deferred optimized navigation — builds the optimized path at execution time. */
    public static NavigationAction forAutoOptimizedDeferred(Follower follower, Pose targetPose,
                                                            PathUtils.DriveDirection direction,
                                                            double percentageRotateStart, double percentageRotateEnd) {
        String name = String.format("auto_nav_opt_deferred_%.0f_%.0f", targetPose.getX(), targetPose.getY());
        NavigationAction action = new NavigationAction(name, follower, (PathChain) null, () -> false)
                .resumeTeleOpOnFinish(false);
        action.pathChainSupplier = () -> PathUtils.buildOptimizedPath(
                follower, follower.getPose(), targetPose, direction, percentageRotateStart, percentageRotateEnd);
        return action.withFinalPose(targetPose);
    }

    /** Deferred optimized navigation with AUTO direction detection. */
    public static NavigationAction forAutoOptimizedDeferred(Follower follower, Pose targetPose,
                                                            double percentageRotateStart, double percentageRotateEnd) {
        return forAutoOptimizedDeferred(follower, targetPose, PathUtils.DriveDirection.AUTO,
                percentageRotateStart, percentageRotateEnd);
    }

    /** TeleOp optimized navigation with joystick override. */
    public static NavigationAction optimizedWithOverride(Follower follower, Pose targetPose,
                                                         PathUtils.DriveDirection direction,
                                                         double percentageRotateStart, double percentageRotateEnd,
                                                         BooleanSupplier joystickOverride) {
        Pose currentPose = follower.getPose();
        PathChain optimized = PathUtils.buildOptimizedPath(
                follower, currentPose, targetPose, direction, percentageRotateStart, percentageRotateEnd);
        String name = String.format("nav_optimized_%.0f_%.0f", targetPose.getX(), targetPose.getY());
        return new NavigationAction(name, follower, optimized, joystickOverride)
                .withFinalPose(targetPose);
    }

    /** TeleOp optimized navigation with AUTO direction detection and joystick override. */
    public static NavigationAction optimizedWithOverride(Follower follower, Pose targetPose,
                                                         double percentageRotateStart, double percentageRotateEnd,
                                                         BooleanSupplier joystickOverride) {
        return optimizedWithOverride(follower, targetPose, PathUtils.DriveDirection.AUTO,
                percentageRotateStart, percentageRotateEnd, joystickOverride);
    }
}
