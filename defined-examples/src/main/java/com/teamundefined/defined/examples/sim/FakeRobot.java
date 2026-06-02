package com.teamundefined.defined.examples.sim;

/**
 * A tiny, dependency-free simulation of an FTC robot's actuators and sensors.
 *
 * <p>It models just enough physics for the examples to be meaningful:
 * <ul>
 *   <li>the flywheel spins up toward a target velocity over time;</li>
 *   <li>the intake loads balls while running;</li>
 *   <li>opening the indexer gate while the flywheel is "ready" launches a ball
 *       (incrementing the score).</li>
 * </ul>
 *
 * <p>Because everything is driven by a {@code tick(now)} clock you feed in, the
 * whole robot is deterministic and testable on a laptop — no Control Hub needed.
 */
public class FakeRobot {

    // ---- Drive ----
    public double drivePower = 0;

    // ---- Intake ----
    private boolean intakeOn = false;
    private int ballsLoaded = 0;
    private long lastIntakeLoadMs = -1;
    /** A ball is picked up roughly every this many ms while intaking. */
    public long intakeLoadIntervalMs = 200;
    public int maxBalls = 3;

    // ---- Flywheel ----
    private double flywheelTarget = 0;
    private double flywheelVelocity = 0;
    /** Velocity units gained per ms while spinning up. */
    public double flywheelRampPerMs = 3.0;
    public double flywheelReadyTolerance = 20.0;

    // ---- Indexer / scoring ----
    private boolean gateOpen = false;
    private int ballsScored = 0;

    private long lastTickMs = -1;

    /** Advance the simulation to timestamp {@code nowMs}. Call once per loop. */
    public void tick(long nowMs) {
        long dt = (lastTickMs < 0) ? 0 : Math.max(0, nowMs - lastTickMs);
        lastTickMs = nowMs;

        // Flywheel ramps toward its target.
        if (flywheelVelocity < flywheelTarget) {
            flywheelVelocity = Math.min(flywheelTarget, flywheelVelocity + flywheelRampPerMs * dt);
        } else if (flywheelVelocity > flywheelTarget) {
            flywheelVelocity = Math.max(flywheelTarget, flywheelVelocity - flywheelRampPerMs * dt);
        }

        // Intake loads balls on an interval.
        if (intakeOn && ballsLoaded < maxBalls) {
            if (lastIntakeLoadMs < 0) lastIntakeLoadMs = nowMs;
            if (nowMs - lastIntakeLoadMs >= intakeLoadIntervalMs) {
                ballsLoaded++;
                lastIntakeLoadMs = nowMs;
            }
        }

        // An open gate + a ready flywheel + a loaded ball == a scored shot.
        if (gateOpen && isFlywheelReady() && ballsLoaded > 0) {
            ballsLoaded--;
            ballsScored++;
        }
    }

    // ---- Drive API ----
    public void setDrivePower(double p) { this.drivePower = p; }

    // ---- Intake API ----
    public void setIntake(boolean on) {
        this.intakeOn = on;
        if (on && lastIntakeLoadMs < 0) lastIntakeLoadMs = -1; // (re)armed on next tick
    }
    public boolean isIntaking() { return intakeOn; }
    public int ballsLoaded() { return ballsLoaded; }

    // ---- Flywheel API ----
    public void setFlywheelTarget(double v) { this.flywheelTarget = v; }
    public double flywheelVelocity() { return flywheelVelocity; }
    public boolean isFlywheelReady() {
        return flywheelTarget > 0 && Math.abs(flywheelVelocity - flywheelTarget) <= flywheelReadyTolerance;
    }

    // ---- Indexer API ----
    public void setGate(boolean open) { this.gateOpen = open; }
    public boolean isGateOpen() { return gateOpen; }

    // ---- Scoring ----
    public int ballsScored() { return ballsScored; }

    @Override
    public String toString() {
        return String.format(
                "Robot[loaded=%d scored=%d flywheel=%.0f/%s gate=%s intake=%s]",
                ballsLoaded, ballsScored, flywheelVelocity,
                flywheelTarget == 0 ? "off" : String.valueOf((int) flywheelTarget),
                gateOpen ? "open" : "closed", intakeOn ? "on" : "off");
    }
}
