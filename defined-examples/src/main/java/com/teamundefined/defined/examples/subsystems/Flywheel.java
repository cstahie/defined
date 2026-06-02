package com.teamundefined.defined.examples.subsystems;

/**
 * A simulated dual-motor flywheel. Velocity ramps toward a target each tick, and
 * {@link #isReady()} reports when it's within tolerance — the signal an action
 * waits on before firing.
 */
public class Flywheel {
    private double target = 0;
    private double velocity = 0;

    public double rampPerTick = 60;
    public double tolerance = 20;

    public void setTarget(double v) { this.target = v; }
    public double velocity() { return velocity; }
    public double target() { return target; }

    public boolean isReady() {
        return target > 0 && Math.abs(velocity - target) <= tolerance;
    }

    public void tick(long nowMs) {
        if (velocity < target) velocity = Math.min(target, velocity + rampPerTick);
        else if (velocity > target) velocity = Math.max(target, velocity - rampPerTick);
    }
}
