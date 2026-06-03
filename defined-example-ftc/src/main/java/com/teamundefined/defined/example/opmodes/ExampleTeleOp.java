package com.teamundefined.defined.example.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.ftc.ActionOpMode;
import com.teamundefined.defined.ftc.AndroidLog;
import com.teamundefined.defined.runner.ToggleStartGroupAction;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.actions.IntakeActions;
import com.teamundefined.defined.example.actions.ShootingActions;
import com.teamundefined.defined.example.actions.TurretActions;

/**
 * Example TELEOP, structured like a real one: build the {@link ExampleRobot}, register
 * slot-free monitors, and start a slot-managed scoring group from a button. The
 * {@link ActionOpMode} base owns the {@code ActionRunner} and ticks it each loop.
 */
@TeleOp(name = "Example TeleOp", group = "defined")
public class ExampleTeleOp extends ActionOpMode {

    private ExampleRobot robot;

    @Override
    protected void onInit() {
        AndroidLog.install();
        robot = new ExampleRobot(hardwareMap);

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
        super.start();
        robot.drive.startTeleopDrive();
    }

    @Override
    protected void onLoop(long nowMs) {
        // Manual drive — only when Pedro isn't busy following a path.
        if (!robot.drive.isBusy()) {
            robot.drive.setTeleOpDrive(-gamepad1.left_stick_y, -gamepad1.left_stick_x, -gamepad1.right_stick_x);
        }
        robot.update(); // tick subsystems before the runner updates actions
    }
}
