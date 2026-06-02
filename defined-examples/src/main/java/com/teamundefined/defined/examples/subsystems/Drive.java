package com.teamundefined.defined.examples.subsystems;

/**
 * A simulated mecanum drive. Stands in for a Pedro {@code Follower}: it tracks a
 * pose and can "navigate" toward a target a little each tick. On a real robot this
 * is where you'd call Pedro ({@code follower.followPath(...)}, {@code isBusy()}).
 */
public class Drive {
    public double x = 0, y = 0, heading = 0;   // pose: inches, radians

    private double tx, ty, th;
    private boolean following = false;
    private double manualPower = 0;

    public double inchesPerTick = 1.5;
    public double radPerTick = 0.06;

    /** Begin navigating to a target pose (like {@code follower.followPath}). */
    public void driveTo(double x, double y, double heading) {
        this.tx = x; this.ty = y; this.th = heading;
        this.following = true;
    }

    /** True while still driving to the target (like {@code follower.isBusy()}). */
    public boolean isBusy() { return following; }

    public void setManualPower(double power) { this.manualPower = power; }
    public double manualPower() { return manualPower; }

    /** Advance the simulation one loop toward the target. */
    public void tick(long nowMs) {
        if (!following) return;

        double dx = tx - x, dy = ty - y;
        double dist = Math.hypot(dx, dy);
        if (dist > inchesPerTick) { x += dx / dist * inchesPerTick; y += dy / dist * inchesPerTick; }
        else { x = tx; y = ty; }

        double dh = th - heading;
        if (Math.abs(dh) > radPerTick) heading += Math.signum(dh) * radPerTick;
        else heading = th;

        if (Math.hypot(tx - x, ty - y) < 0.5 && Math.abs(th - heading) < 0.05) {
            x = tx; y = ty; heading = th;
            following = false;
        }
    }
}
