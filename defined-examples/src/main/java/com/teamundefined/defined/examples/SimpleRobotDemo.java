package com.teamundefined.defined.examples;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.runner.ActionRunner;
import com.teamundefined.defined.runner.ToggleStartGroupAction;
import com.teamundefined.defined.examples.sim.FakeGamepad;
import com.teamundefined.defined.examples.sim.FakeRobot;

/**
 * A runnable, end-to-end example of the Defined engine driving a (simulated) FTC
 * robot. Run it with:
 *
 * <pre>{@code ./gradlew :defined-examples:run}</pre>
 *
 * <p>It demonstrates two phases, exactly like a real match:
 * <ol>
 *   <li><b>Autonomous</b> — a single composed {@link Action} ({@code scoreThree})
 *       runs to completion, with no driver input.</li>
 *   <li><b>TeleOp</b> — an {@link ActionRunner} arbitrates slot-managed groups
 *       while slot-free monitors handle continuous drive and the intake toggle.</li>
 * </ol>
 */
public class SimpleRobotDemo {

    public static void main(String[] args) {
        System.out.println("=== Defined demo: simulated FTC robot ===\n");
        runAutonomous();
        System.out.println();
        runTeleop();
    }

    /** AUTONOMOUS: load three balls while spinning up, then fire them all. */
    static FakeRobot runAutonomous() {
        System.out.println("--- AUTONOMOUS (one composed action) ---");
        FakeRobot robot = new FakeRobot();
        Action auto = RobotActions.scoreThree(robot);

        long now = 0;
        while (!auto.inTerminalState() && now < 10_000) {
            robot.tick(now);
            auto.update(now);
            if (now % 200 == 0) System.out.println("  t=" + now + "ms  " + robot);
            now += 20;
        }
        System.out.println("  AUTO result: " + auto.getState() + " -> scored " + robot.ballsScored() + " balls");
        return robot;
    }

    /** TELEOP: runner-managed scoring group + slot-free monitors for drive & intake. */
    static FakeRobot runTeleop() {
        System.out.println("--- TELEOP (runner + monitors + scripted driver) ---");
        FakeRobot robot = new FakeRobot();
        FakeGamepad gp = new FakeGamepad();
        ActionRunner runner = new ActionRunner();

        // Slot-free monitors run every loop, forever.
        runner.addMonitor(RobotActions.driveControl(robot, gp));
        runner.addMonitor(RobotActions.intakeToggle(robot, gp));

        // Triangle toggles the spin-up-and-fire group on the runner (press to start,
        // press again to abort). ToggleStartGroupAction is itself slot-free.
        runner.addMonitor(new ToggleStartGroupAction("score", () -> gp.triangle, runner,
                () -> RobotActions.spinUpAndFire(robot),
                () -> Action.oneShot("idle", n -> { robot.setFlywheelTarget(0); robot.setGate(false); })));

        long now = 0;
        for (int loop = 0; loop < 400; loop++) {
            // ---- scripted "driver" ----
            gp.cross = (loop == 10);                 // tap X -> intake ON
            gp.triangle = (loop == 120);             // tap /\ -> spin up & fire
            gp.leftStickY = (loop < 60) ? -0.5 : 0;  // drive forward early on

            robot.tick(now);
            runner.update(now);   // feed the simulated clock for deterministic behavior

            if (loop % 40 == 0) System.out.println("  loop=" + loop + "  " + robot);
            now += 20;
        }
        System.out.println("  TELEOP result: scored " + robot.ballsScored() + " balls");
        return robot;
    }
}
