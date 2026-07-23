package com.teamundefined.defined.example.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import com.teamundefined.defined.Action;
import com.pedropathing.geometry.Pose;

import com.teamundefined.defined.TelemetrySnapshot;
import com.teamundefined.defined.ftc.AsyncTelemetryProcessor;
import com.teamundefined.defined.ftc.RobotOpMode;
import com.teamundefined.defined.runner.ToggleStartGroupAction;
import com.teamundefined.defined.example.ExampleConfig;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.actions.IntakeActions;
import com.teamundefined.defined.example.actions.ShootingActions;
import com.teamundefined.defined.example.actions.TurretActions;

/**
 * Example TELEOP — the whole robot in about forty lines.
 *
 * <p>{@link RobotOpMode} owns the plumbing: it builds the robot, ticks it either side of
 * {@link #onLoop(long)}, runs the {@code ActionRunner}, shows the pre-start menu, and pushes
 * telemetry off the loop thread. What is left below is only what makes this robot this robot:
 * its controls (onLoop), its monitors (onRobotInit), and its telemetry
 * ({@link #fillSnapshot} captures, {@link #formatTelemetry} lays out).
 */
@TeleOp(name = "Example TeleOp", group = "defined")
public class ExampleTeleOp extends RobotOpMode<ExampleRobot> {

    @Override
    protected ExampleRobot createRobot() {
        return new ExampleRobot(hardwareMap);
    }

    @Override
    protected void onRobotInit() {
        // Logging needs no setup — Defined finds android.util.Log by itself.
        // Log.verbosity = 30;  // uncomment to see the engine's state transitions

        // Let the driver set these on the field, with no rebuild between matches.
        // Name the fields; the base class builds the menu and wires the D-pad for you.
        enablePreStartMenu(ExampleConfig.class,
                "ExampleConfig.alliance",
                "ExampleConfig.startPosition",
                "ExampleConfig.shotVelocity",
                "ExampleConfig.telemetryOn",
                "ExampleConfig.profilerOn");

        // Monitors — slot-free, run every loop.
        runner.addMonitor(IntakeActions.toggle(robot, () -> gamepad1.cross));        // X
        runner.addMonitor(TurretActions.trackToggle(robot, () -> gamepad1.square));  // Square

        // Triangle starts/stops the one-button scoring group (FLYWHEEL + INDEXER).
        runner.addMonitor(new ToggleStartGroupAction("score", () -> gamepad1.triangle, runner,
                () -> ShootingActions.shootLoaded(robot),
                () -> Action.oneShot("idle", n -> {
                    robot.flywheel.stopFlywheelSync();
                    robot.indexer.closeAll();
                })));
    }

    @Override
    public void start() {
        super.start(); // hands the robot its start(isTeleOp) call
        robot.drive.startTeleopDrive();
    }

    @Override
    protected void onLoop(long nowMs) {
        // Manual drive — only when Pedro isn't busy following a path.
        if (!robot.drive.isBusy()) {
            robot.drive.setTeleOpDrive(-gamepad1.left_stick_y, -gamepad1.left_stick_x, -gamepad1.right_stick_x);
        }
        // No robot.update() here — RobotOpMode ticks it around this method for us.
    }

    /**
     * Capture side — runs on the loop thread, so do only cheap key/value puts here. No string
     * formatting, no {@code String.format}; that is the formatter's job, below.
     */
    @Override
    protected void fillSnapshot(TelemetrySnapshot snapshot) {
        snapshot.put("alliance", ExampleConfig.alliance.toString());
        snapshot.put("flywheel", robot.flywheel.isReady() ? "READY" : "spinning");
        snapshot.putBoolean("turret.locked", robot.turret.isLocked());

        // Health and timing, gathered by the robot's monitors (see ExampleRobot).
        snapshot.putDouble("battery.v", robot.batteryVolts, 1);
        snapshot.putDouble("flywheel.a", robot.flywheelAmps, 1);
        snapshot.put("system", robot.systemMonitor.getFormattedStats());
        snapshot.put("profile", robot.profiler.getFormattedStats());

        Pose p = robot.drive.getPose();
        snapshot.putPosition("pose", p.getX(), p.getY(), p.getHeading(), 1);
    }

    /**
     * Format side — runs on a background thread, so heavier layout is fine here. This is the
     * only place that turns snapshot values into display lines.
     */
    @Override
    protected void formatTelemetry(TelemetrySnapshot snapshot,
                                   AsyncTelemetryProcessor.FormattedTelemetry out) {
        out.addData("Alliance", snapshot.get("alliance"));
        out.addData("Pose", snapshot.get("pose"));
        out.addSeparator();
        out.addData("Flywheel", snapshot.get("flywheel") + "  (" + snapshot.get("flywheel.a") + " A)");
        out.addData("Turret", "true".equals(snapshot.get("turret.locked")) ? "LOCKED" : "tracking");
        out.addData("Battery", snapshot.get("battery.v") + " V");
        out.addSeparator();
        out.addLine(snapshot.get("system"));
        out.addLine(snapshot.get("profile"));
    }

    /** Master switch the driver flips from the pre-start menu. */
    @Override
    protected boolean isTelemetryEnabled() {
        return ExampleConfig.telemetryOn;
    }
}
