package com.teamundefined.defined.examples;

import com.teamundefined.defined.examples.subsystems.Drive;
import com.teamundefined.defined.examples.subsystems.Flywheel;
import com.teamundefined.defined.examples.subsystems.Indexer;
import com.teamundefined.defined.examples.subsystems.Intake;
import com.teamundefined.defined.examples.subsystems.Turret;

/**
 * Wires the subsystems together — the example's equivalent of a real team's
 * {@code Robot.java}. It owns the subsystems and advances them each loop; actions
 * (in the {@code actions} package) reach into these subsystems to do work.
 *
 * <p>The {@link #tick(long)} coordinator also handles the cross-subsystem bits a
 * single subsystem can't own alone (intake feeding the indexer; the indexer scoring
 * only when the flywheel is ready).
 */
public class DummyRobot {
    public final Drive drive = new Drive();
    public final Intake intake = new Intake();
    public final Flywheel flywheel = new Flywheel();
    public final Indexer indexer = new Indexer();
    public final Turret turret = new Turret();

    /** Advance every subsystem one loop. Call once per loop, before updating actions. */
    public void tick(long nowMs) {
        drive.tick(nowMs);
        flywheel.tick(nowMs);
        turret.tick(nowMs);
        if (intake.isRunning()) indexer.load(nowMs);   // intake feeds the magazine
        indexer.tick(nowMs, flywheel.isReady());        // score only when up to speed
    }

    public String telemetry() {
        return String.format(
                "pose=(%.0f,%.0f,%.0f°) balls=%d scored=%d flywheel=%.0f/%s turret=%s%s",
                drive.x, drive.y, Math.toDegrees(drive.heading),
                indexer.balls(), indexer.scored(),
                flywheel.velocity(), flywheel.target() == 0 ? "off" : (int) flywheel.target() + "",
                turret.isTracking() ? "tracking" : "idle",
                turret.onTarget() ? "(locked)" : "");
    }
}
