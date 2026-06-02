package com.teamundefined.defined.examples.opmodes;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.runner.ActionRunner;
import com.teamundefined.defined.runner.ToggleStartGroupAction;
import com.teamundefined.defined.examples.DummyRobot;
import com.teamundefined.defined.examples.actions.DriveActions;
import com.teamundefined.defined.examples.actions.IntakeActions;
import com.teamundefined.defined.examples.actions.ShootingActions;
import com.teamundefined.defined.examples.actions.TurretActions;
import com.teamundefined.defined.examples.sim.FakeGamepad;

/**
 * Example TELEOP — an {@link ActionRunner} arbitrating driver-triggered behavior.
 * This mirrors how Team Undefined's real {@code MainTeleOp} is organized:
 *
 * <ul>
 *   <li>slot-free <b>monitors</b> run every loop (manual drive, intake toggle, turret toggle);</li>
 *   <li>a button starts a slot-managed <b>group</b> ({@code shootLoaded}) via
 *       {@link ToggleStartGroupAction}, and the runner makes sure it never collides
 *       with anything else using FLYWHEEL/INDEXER.</li>
 * </ul>
 *
 * <p>On a real robot this is your {@code loop()} after wiring gamepads — the
 * structure is identical, you just feed real {@code gamepad1.*} fields.
 */
public final class DummyTeleOp {
    private DummyTeleOp() {}

    public static DummyRobot run(boolean verbose) {
        DummyRobot r = new DummyRobot();
        FakeGamepad gp = new FakeGamepad();
        ActionRunner runner = new ActionRunner();

        // --- monitors: always running, no slots ---
        runner.addMonitor(DriveActions.manualDrive(r, () -> -gp.leftStickY));
        runner.addMonitor(IntakeActions.toggle(r, () -> gp.cross));       // X toggles intake
        runner.addMonitor(TurretActions.trackToggle(r, () -> gp.square)); // Square toggles tracking

        // --- Triangle starts/stops the one-button scoring group on the runner ---
        runner.addMonitor(new ToggleStartGroupAction("score", () -> gp.triangle, runner,
                () -> ShootingActions.shootLoaded(r),
                () -> Action.oneShot("idle", n -> { r.flywheel.setTarget(0); r.indexer.closeGate(); })));

        // --- scripted "driver" so the example runs itself ---
        long now = 0;
        for (int loop = 0; loop < 400; loop++) {
            gp.cross = (loop == 10);                 // tap X  → intake ON
            gp.square = (loop == 15);                // tap [] → tracking ON
            gp.triangle = (loop == 150);             // tap /\ → spin up & fire
            gp.leftStickY = (loop < 50) ? -0.6 : 0;  // drive forward early

            r.tick(now);
            runner.update(now);   // inject the loop clock for deterministic behavior
            if (verbose && loop % 40 == 0) System.out.println("  loop=" + loop + "  " + r.telemetry());
            now += 20;
        }
        if (verbose) System.out.println("  TELEOP — scored " + r.indexer.scored());
        return r;
    }
}
