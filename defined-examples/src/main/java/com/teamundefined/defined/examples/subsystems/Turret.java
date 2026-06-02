package com.teamundefined.defined.examples.subsystems;

/**
 * A simulated vision turret. When tracking, its aim error shrinks toward zero each
 * tick; {@link #onTarget()} is the signal an aiming action waits on.
 */
public class Turret {
    private boolean tracking = false;
    private double errorDeg = 30; // degrees off target

    public void startTracking() { tracking = true; }
    public void stopTracking() { tracking = false; }
    public boolean isTracking() { return tracking; }

    public double errorDeg() { return errorDeg; }
    public boolean onTarget() { return Math.abs(errorDeg) < 2.0; }

    public void tick(long nowMs) {
        if (tracking && errorDeg != 0) {
            errorDeg *= 0.6; // converge on target
            if (Math.abs(errorDeg) < 0.5) errorDeg = 0;
        }
    }
}
