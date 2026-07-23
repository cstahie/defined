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
    /**
     * Evaluated when the action finishes: when true, break following and resume teleop drive.
     * A supplier (not a flag) because the answer is usually "am I in TeleOp right now?", which
     * is only known at runtime.
     */
    private BooleanSupplier resumeTeleOpOnFinish = () -> true;

    /**
     * Default stuck-detection tunables. Values are Team Undefined's competition-tuned
     * defaults; override per action with {@link #withStuckDetection(long, double, long, double)}
     * or globally by assigning these fields.
     */
    public static class StuckDefaults {
        /** Ignore the first N ms after a path starts (acceleration window). */
        public static long GRACE_MS = 500;
        /** in/s; below this counts as "not moving". */
        public static double MIN_VELOCITY = 2.0;
        /** Must stay below MIN_VELOCITY this long before aborting. */
        public static long SUSTAIN_MS = 350;
        /** Within this distance of the path end, a stall reads as COMPLETE rather than TIMEOUT. */
        public static double COMPLETE_RADIUS = 8.0;
        /** Minimum ms between re-target polls / followPath re-issues. */
        public static long RETARGET_MIN_INTERVAL_MS = 100;
    }

    // Re-targeting: chase a moving target by re-issuing followPath when it shifts in Y.
    // Each re-issue goes through Pedro's breakFollowing (momentary motor-power writes), so
    // re-issues are throttled - jittery detections must not re-issue every scan frame.
    private Supplier<Pose> retargetSupplier = null;
    private double retargetYTolerance = 0;
    private Pose activeRetarget = null;
    private long lastRetargetPollMs = 0;
    private boolean retargetFrozenLogged = false;
    private long retargetMinIntervalMs = StuckDefaults.RETARGET_MIN_INTERVAL_MS;

    // Stuck detection: when the path is being followed but the robot isn't actually moving
    // (wedged against the fence, motors stalling), end early instead of burning the full timeout.
    // OPT-IN - navigations that intentionally press at near-zero velocity (fence dwell, gate ram)
    // must not enable it. pathFollowStartMs marks when the FIRST followPath began; re-targeting
    // re-issues do NOT reset it (the robot is already moving, and resetting would keep the grace
    // window from ever elapsing while detections jitter).
    private BooleanSupplier stuckDetectionEnabled = () -> false;
    private long pathFollowStartMs = 0;
    private long stuckSinceMs = 0;
    private long lastStuckSampleMs = 0;
    private long stuckGraceMs = StuckDefaults.GRACE_MS;
    private double stuckMinVelocity = StuckDefaults.MIN_VELOCITY;
    private long stuckSustainMs = StuckDefaults.SUSTAIN_MS;
    private double stuckCompleteRadius = StuckDefaults.COMPLETE_RADIUS;
    // Velocity sampling interval: getVelocity() allocates and the stall thresholds are hundreds
    // of ms, so per-loop (~100Hz) resolution buys nothing on the Control Hub hot loop.
    private static final long STUCK_SAMPLE_INTERVAL_MS = 50;

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
        this.onComplete = this::handleComplete;
        // A timeout/cancel/error means we did NOT reach the target - always stop pushing motors,
        // unlike handleComplete which can deliberately hand the path off to the next navigation.
        this.onTimeout = this::handleFinishResumeDrive;
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
    public NavigationAction resumeTeleOpOnFinish(boolean resume) { this.resumeTeleOpOnFinish = () -> resume; return this; }

    /**
     * Supplier variant of {@link #resumeTeleOpOnFinish(boolean)}, evaluated when the action
     * ends. Use this when the answer depends on runtime state, e.g.
     * {@code resumeTeleOpOnFinish(() -> robot.isTeleOpActive() || DEMO_MODE)}.
     */
    public NavigationAction resumeTeleOpOnFinish(BooleanSupplier resume) {
        if (resume != null) this.resumeTeleOpOnFinish = resume;
        return this;
    }

    /**
     * Enable stuck detection with the {@link StuckDefaults} tunables.
     *
     * <p>When the robot is commanded to move but stays below the minimum velocity for the
     * sustain window (after a grace window for acceleration):
     * <ul>
     *   <li>within {@code completeRadius} of the path end → COMPLETE (wedged at the destination)</li>
     *   <li>otherwise → TIMEOUT (failed en route; propagates like a normal nav timeout, and
     *       never CANCELED, which composite actions treat as an external abort)</li>
     * </ul>
     *
     * <p>Do <b>not</b> enable on navigations that intentionally press at near-zero velocity
     * far from their path end (fence dwell, gate ram).
     */
    public NavigationAction withStuckDetection() {
        this.stuckDetectionEnabled = () -> true;
        return this;
    }

    /**
     * {@link #withStuckDetection()} behind a live master switch, re-evaluated every tick.
     * Use this to expose a dashboard-tunable kill switch:
     * {@code withStuckDetection(() -> Config.Auto.navStuckDetection)}.
     */
    public NavigationAction withStuckDetection(BooleanSupplier enabled) {
        this.stuckDetectionEnabled = (enabled != null) ? enabled : () -> false;
        return this;
    }

    /** {@link #withStuckDetection()} with per-action tunables. */
    public NavigationAction withStuckDetection(long graceMs, double minVelocity,
                                               long sustainMs, double completeRadius) {
        this.stuckDetectionEnabled = () -> true;
        this.stuckGraceMs = graceMs;
        this.stuckMinVelocity = minVelocity;
        this.stuckSustainMs = sustainMs;
        this.stuckCompleteRadius = completeRadius;
        return this;
    }

    /** Minimum ms between re-target polls. Defaults to {@link StuckDefaults#RETARGET_MIN_INTERVAL_MS}. */
    public NavigationAction withRetargetInterval(long minIntervalMs) {
        this.retargetMinIntervalMs = minIntervalMs;
        return this;
    }

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

        // Follower went idle without reaching the target. Pedro did its best - accept the
        // current pose and finish rather than stalling until the timeout. Returning false here
        // would hang the action (the follower is idle, so nothing will move again).
        Pose effectiveTarget = getEffectiveTargetPose();
        if (effectiveTarget != null) {
            Pose currentPose = follower.getPose();
            double xError = Math.abs(currentPose.getX() - effectiveTarget.getX());
            double yError = Math.abs(currentPose.getY() - effectiveTarget.getY());

            if (xError > poseTolerance || yError > poseTolerance) {
                Log.w(TAG, () -> String.format(
                        "Drive stopped short of target. At (%.1f,%.1f) target (%.1f,%.1f) err X:%.1f Y:%.1f - accepting",
                        currentPose.getX(), currentPose.getY(),
                        effectiveTarget.getX(), effectiveTarget.getY(), xError, yError));
                return true;
            }

            if (hasExplicitHeadingTarget()) {
                double headingError = Math.abs(PathUtils.normalizeAngle(
                        currentPose.getHeading() - effectiveTarget.getHeading()));
                if (headingError > HEADING_TOLERANCE_RAD) {
                    Log.w(TAG, () -> String.format(
                            "Drive stopped with heading off. At %.1f deg target %.1f deg err %.1f deg - accepting",
                            Math.toDegrees(currentPose.getHeading()),
                            Math.toDegrees(effectiveTarget.getHeading()),
                            Math.toDegrees(headingError)));
                    return true;
                }
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
            pathFollowStartMs = nowMillis;
            stuckSinceMs = 0;
        }

        // Re-targeting: chase a moving target by re-issuing followPath when it shifts in Y.
        // The supplier is only polled at the throttled cadence (it may take a scanner lock).
        if (pathStarted && retargetSupplier != null
                && (nowMillis - lastRetargetPollMs) >= retargetMinIntervalMs) {
            lastRetargetPollMs = nowMillis;
            Pose newTarget = retargetSupplier.get();
            if (newTarget == null) {
                // Supplier froze the target (e.g. detections dropped below camera FOV);
                // the last issued path finishes as normal.
                if (activeRetarget != null && !retargetFrozenLogged) {
                    retargetFrozenLogged = true;
                    Log.i(TAG, () -> "Re-target frozen - finishing last issued path");
                }
            } else if (activeRetarget == null
                    || Math.abs(newTarget.getY() - activeRetarget.getY()) > retargetYTolerance) {
                path = buildLinePath(follower, follower.getPose(), newTarget);
                follower.followPath(path, speedSupplier.getAsDouble(), holdEnd);
                activeRetarget = newTarget;
                // Deliberately NOT resetting pathFollowStartMs/stuckSinceMs: the robot is already
                // moving, and resetting the grace window on every re-issue would keep stuck
                // detection from ever arming while detections keep updating.
                Log.i(TAG, () -> String.format("Re-targeted nav to (%.1f, %.1f)",
                        newTarget.getX(), newTarget.getY()));
            }
        }

        // Stuck detection (opt-in): commanded to move but not moving (wedged / stalled).
        if (pathStarted && stuckDetectionEnabled.getAsBoolean()
                && (nowMillis - pathFollowStartMs) > stuckGraceMs
                && (nowMillis - lastStuckSampleMs) >= STUCK_SAMPLE_INTERVAL_MS) {
            lastStuckSampleMs = nowMillis;
            double speed = follower.getVelocity().getMagnitude();
            if (speed < stuckMinVelocity) {
                if (stuckSinceMs == 0) {
                    stuckSinceMs = nowMillis;
                } else if ((nowMillis - stuckSinceMs) > stuckSustainMs) {
                    Pose p = follower.getPose();
                    Pose target = getEffectiveTargetPose();
                    double distToTarget = (target == null) ? Double.MAX_VALUE
                            : Math.hypot(p.getX() - target.getX(), p.getY() - target.getY());
                    long stalledFor = nowMillis - stuckSinceMs;
                    Log.w(TAG, () -> String.format(
                            "STUCK at (%.1f, %.1f) heading %.1f deg - speed %.1f in/s for %dms, %.1f in from target",
                            p.getX(), p.getY(), Math.toDegrees(p.getHeading()), speed,
                            stalledFor, distToTarget));
                    if (distToTarget <= stuckCompleteRadius) {
                        endAction(ActionState.COMPLETE);
                    } else {
                        endActionWithTimeout("Stuck - robot not moving while following path");
                    }
                    return;
                }
            } else {
                stuckSinceMs = 0; // moving again, reset the stall timer
            }
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
                    .setGlobalDeceleration()
                    .addPath(new BezierLine(currentPose, targetPose))
                    .setLinearHeadingInterpolation(currentPose.getHeading(), finalHeading)
                    .build();
        }
        Log.i(TAG, () -> "Navigation start: " + name);
    }

    private void handleFinishResumeDrive(long nowMillis) {
        if (follower != null && resumeTeleOpOnFinish.getAsBoolean()) {
            follower.breakFollowing();
            follower.startTeleopDrive();
        }
    }

    /**
     * On success, only break following when we are resuming driver control. In autonomous the
     * path is deliberately left active so the next navigation's followPath() overrides it
     * seamlessly, eliminating the stop-wait-accelerate cycle between consecutive paths. If this
     * is the last navigation, Pedro naturally completes and holds position.
     */
    private void handleComplete(long nowMillis) {
        handleFinishResumeDrive(nowMillis);
        Log.i(TAG, () -> "Navigation completed - reached target");
    }

    @Override
    public Action reset() {
        super.reset();
        // Only preserve the path for truly pre-built paths (passed in the constructor).
        // Supplier-built paths must be rebuilt from the live pose at the next start - preserving
        // them would re-follow a path anchored at a stale (possibly mid-chase) pose.
        if (!usePrebuiltPath || pathChainSupplier != null) path = null;
        pathStarted = false;
        activeRetarget = null;
        lastRetargetPollMs = 0;
        retargetFrozenLogged = false;
        pathFollowStartMs = 0;
        stuckSinceMs = 0;
        lastStuckSampleMs = 0;
        currentWaypointIndex = 0;
        if (waypoints != null) for (Waypoint w : waypoints) w.reset();
        return this;
    }

    /** Straight-line path from {@code from} to {@code to} with linear heading interpolation. */
    private static PathChain buildLinePath(Follower follower, Pose from, Pose to) {
        return follower.pathBuilder()
                .setGlobalDeceleration()
                .addPath(new BezierLine(from, to))
                .setLinearHeadingInterpolation(from.getHeading(), to.getHeading())
                .build();
    }

    /**
     * Navigation that chases a moving target: {@code targetSupplier} is polled (throttled to
     * {@link StuckDefaults#RETARGET_MIN_INTERVAL_MS}) and the path is re-issued whenever the
     * target moves more than {@code yTolerance} in Y.
     *
     * <p>The supplier should return {@code null} only for a deliberate freeze (e.g. the target
     * left the camera's view); it must otherwise hold its last valid target, since a null simply
     * lets the last issued path finish.
     */
    public static NavigationAction forAutoRetargeting(String name, Follower follower,
                                                      Supplier<Pose> targetSupplier,
                                                      double yTolerance) {
        NavigationAction action = new NavigationAction(name, follower, (PathChain) null, () -> false);
        action.retargetSupplier = targetSupplier;
        action.retargetYTolerance = yTolerance;
        action.pathChainSupplier = () -> {
            Pose target = targetSupplier.get();
            if (target == null) return null;
            action.activeRetarget = target;
            return buildLinePath(follower, follower.getPose(), target);
        };
        return action;
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
