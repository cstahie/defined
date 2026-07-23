package com.teamundefined.defined.pedro;

import com.pedropathing.follower.Follower;
import com.pedropathing.geometry.Pose;

import com.teamundefined.defined.Action;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A slot-free monitor that fires callbacks when the robot enters or leaves a field zone.
 * Useful for protected-area awareness (auto-stopping near an opponent's human-player zone)
 * or for triggering behavior on arrival (spinning up when entering a shooting zone).
 *
 * <p>A zone is one or more {@link Perimeter}s — triangles or axis-aligned rectangles — and
 * the robot counts as "in zone" while it is inside <b>any</b> of them.
 *
 * <pre>{@code
 * // Enter/exit callbacks over an arbitrary set of perimeters
 * runner.addMonitor(ZoneMonitor.of("shooting", follower::getPose, nearZone, farZone)
 *         .onEnter(() -> turret.startTracking())
 *         .onExit(() -> turret.stopTracking()));
 *
 * // Or a single boolean callback, which also fires for the initial state
 * runner.addMonitor(ZoneMonitor.create("shooting", follower::getPose,
 *         inZone -> { if (inZone) turret.startTracking(); else turret.stopTracking(); },
 *         nearZone, farZone));
 *
 * // Quick axis-aligned box
 * runner.addMonitor(ZoneMonitor.rect("base_zone", follower, 0, 0, 18, 18)
 *         .onEnter(() -> telemetry.addLine("Parked!")));
 * }</pre>
 *
 * <p><b>First sample:</b> by default the monitor reports the robot's initial state on its
 * first tick — if you start already inside the zone, the enter callback fires immediately.
 * That is usually what you want (a subsystem gets switched on at init rather than waiting
 * for you to drive out and back in). Call {@link #suppressFirstSample()} for edge-only
 * behavior, where nothing fires until the state actually changes.
 *
 * <p>Bounds are inclusive, in your localizer's field units (Pedro uses inches by default).
 * This action never completes — add it as a monitor, not as a slot action.
 */
public class ZoneMonitor extends Action {

    private final Supplier<Pose> poseSupplier;
    private final Perimeter[] perimeters;

    private Runnable onEnter = null;
    private Runnable onExit = null;
    private Consumer<Boolean> onChange = null;

    private boolean inside = false;
    private boolean initialized = false;
    private boolean fireOnFirstSample = true;

    private ZoneMonitor(String name, Supplier<Pose> poseSupplier, Perimeter... perimeters) {
        super(name, now -> {});
        this.poseSupplier = poseSupplier;
        this.perimeters = (perimeters != null) ? perimeters : new Perimeter[0];
        this.step = this::runStep;
        this.isComplete = () -> false; // continuous monitor
    }

    // ---- Factories ----

    /** Monitor an arbitrary set of perimeters using any pose source. */
    public static ZoneMonitor of(String name, Supplier<Pose> poseSupplier, Perimeter... perimeters) {
        return new ZoneMonitor(name, poseSupplier, perimeters);
    }

    /** Monitor an arbitrary set of perimeters using the follower's current pose. */
    public static ZoneMonitor of(String name, Follower follower, Perimeter... perimeters) {
        return new ZoneMonitor(name, follower::getPose, perimeters);
    }

    /**
     * Monitor perimeters with a single boolean callback, invoked with {@code true} on entry
     * and {@code false} on exit (including for the initial state — see the class docs).
     */
    public static ZoneMonitor create(String name, Supplier<Pose> poseSupplier,
                                     Consumer<Boolean> onZoneChange, Perimeter... perimeters) {
        return new ZoneMonitor(name, poseSupplier, perimeters).onChange(onZoneChange);
    }

    /** Monitor a rectangular zone using the follower's current pose. */
    public static ZoneMonitor rect(String name, Follower follower,
                                   double minX, double minY, double maxX, double maxY) {
        return new ZoneMonitor(name, follower::getPose, Perimeter.rectangle(minX, minY, maxX, maxY));
    }

    /** Monitor a rectangular zone using an arbitrary pose source. */
    public static ZoneMonitor rect(String name, Supplier<Pose> poseSupplier,
                                   double minX, double minY, double maxX, double maxY) {
        return new ZoneMonitor(name, poseSupplier, Perimeter.rectangle(minX, minY, maxX, maxY));
    }

    // ---- Configuration ----

    public ZoneMonitor onEnter(Runnable r) { this.onEnter = r; return this; }

    public ZoneMonitor onExit(Runnable r) { this.onExit = r; return this; }

    /** Single callback for both directions: {@code true} = entered, {@code false} = exited. */
    public ZoneMonitor onChange(Consumer<Boolean> c) { this.onChange = c; return this; }

    /**
     * Edge-only mode: do not report the initial state on the first tick. Nothing fires until
     * the robot actually crosses the boundary.
     */
    public ZoneMonitor suppressFirstSample() { this.fireOnFirstSample = false; return this; }

    /** True while the most recent pose sample was inside the zone. */
    public boolean isInside() { return inside; }

    // ---- Step ----

    private void runStep(long nowMillis) {
        Pose p = poseSupplier.get();
        if (p == null) return;

        boolean nowInside = isInAnyZone(p, perimeters);

        if (!initialized) {
            initialized = true;
            inside = nowInside;
            // Report the starting state unless the caller asked for edge-only behavior.
            // Only an actual "inside" is worth announcing on tick one; firing an exit for a
            // robot that simply started outside would be noise.
            if (fireOnFirstSample && nowInside) fire(true);
            return;
        }

        if (nowInside != inside) {
            inside = nowInside;
            fire(nowInside);
        }
    }

    private void fire(boolean entered) {
        if (onChange != null) onChange.accept(entered);
        if (entered) {
            if (onEnter != null) onEnter.run();
        } else {
            if (onExit != null) onExit.run();
        }
    }

    @Override
    public Action reset() {
        super.reset();
        inside = false;
        initialized = false;
        return this;
    }

    /**
     * True if {@code pose} is inside any of {@code perimeters}. Exposed for one-off checks
     * that do not need a running monitor.
     */
    public static boolean isInAnyZone(Pose pose, Perimeter... perimeters) {
        if (pose == null || perimeters == null) return false;
        for (Perimeter perimeter : perimeters) {
            if (perimeter != null && perimeter.contains(pose)) return true;
        }
        return false;
    }
}
