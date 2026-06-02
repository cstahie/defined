package com.teamundefined.defined.examples.subsystems;

/**
 * A simulated indexer / magazine. Holds collected balls; when its gate is open and
 * the flywheel is ready, it releases one ball per tick and counts it as scored.
 */
public class Indexer {
    private boolean gateOpen = false;
    private int balls = 0;
    private int scored = 0;

    public int maxBalls = 3;
    public long loadIntervalMs = 200;
    private long lastLoadMs = -1;

    public void openGate() { gateOpen = true; }
    public void closeGate() { gateOpen = false; }
    public boolean isGateOpen() { return gateOpen; }

    public int balls() { return balls; }
    public int scored() { return scored; }

    /** Called by the robot coordinator while the intake is running. */
    public void load(long nowMs) {
        if (balls >= maxBalls) return;
        if (lastLoadMs < 0) lastLoadMs = nowMs;
        if (nowMs - lastLoadMs >= loadIntervalMs) {
            balls++;
            lastLoadMs = nowMs;
        }
    }

    /** Score a ball when the gate is open and the flywheel is up to speed. */
    public void tick(long nowMs, boolean flywheelReady) {
        if (gateOpen && flywheelReady && balls > 0) {
            balls--;
            scored++;
        }
    }
}
