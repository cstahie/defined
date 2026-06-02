package com.teamundefined.defined.pedro;

import com.pedropathing.follower.Follower;
import com.pedropathing.geometry.Pose;

import com.teamundefined.defined.Action;

import java.util.function.Supplier;

/**
 * A slot-free monitor that fires callbacks when the robot enters or leaves a
 * rectangular field zone. Useful for protected-area awareness (e.g. auto-stopping
 * near an opponent's human-player zone) or triggering behavior on arrival.
 *
 * <pre>{@code
 * runner.addMonitor(ZoneMonitor.rect("base_zone", follower, 0, 0, 18, 18)
 *         .onEnter(() -> telemetry.addLine("Parked!"))
 *         .onExit(() -> telemetry.addLine("Left base")));
 * }</pre>
 *
 * <p>Bounds are inclusive and given in your localizer's field units (Pedro uses
 * inches by default). The monitor reads pose via the supplied {@link Supplier};
 * {@link #rect(String, Follower, double, double, double, double)} wires it to a
 * {@link Follower} for you.
 */
public class ZoneMonitor extends Action {

    private final Supplier<Pose> poseSupplier;
    private final double minX, minY, maxX, maxY;

    private Runnable onEnter = null;
    private Runnable onExit = null;
    private boolean inside = false;
    private boolean initialized = false;

    private ZoneMonitor(String name, Supplier<Pose> poseSupplier,
                        double minX, double minY, double maxX, double maxY) {
        super(name, now -> {});
        this.poseSupplier = poseSupplier;
        this.minX = Math.min(minX, maxX);
        this.maxX = Math.max(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        this.step = this::runStep;
        this.isComplete = () -> false; // continuous monitor
    }

    /** Monitor a rectangular zone using the follower's current pose. */
    public static ZoneMonitor rect(String name, Follower follower,
                                   double minX, double minY, double maxX, double maxY) {
        return new ZoneMonitor(name, follower::getPose, minX, minY, maxX, maxY);
    }

    /** Monitor a rectangular zone using an arbitrary pose source. */
    public static ZoneMonitor rect(String name, Supplier<Pose> poseSupplier,
                                   double minX, double minY, double maxX, double maxY) {
        return new ZoneMonitor(name, poseSupplier, minX, minY, maxX, maxY);
    }

    public ZoneMonitor onEnter(Runnable r) { this.onEnter = r; return this; }
    public ZoneMonitor onExit(Runnable r) { this.onExit = r; return this; }

    /** True while the most recent pose sample was inside the zone. */
    public boolean isInside() { return inside; }

    private void runStep(long nowMillis) {
        Pose p = poseSupplier.get();
        if (p == null) return;

        boolean nowInside = p.getX() >= minX && p.getX() <= maxX
                && p.getY() >= minY && p.getY() <= maxY;

        if (!initialized) {
            inside = nowInside;
            initialized = true;
            return; // do not fire on the first sample
        }

        if (nowInside && !inside) {
            inside = true;
            if (onEnter != null) onEnter.run();
        } else if (!nowInside && inside) {
            inside = false;
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
}
