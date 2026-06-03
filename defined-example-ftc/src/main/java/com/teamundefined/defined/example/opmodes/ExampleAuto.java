package com.teamundefined.defined.example.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;

import com.teamundefined.defined.ftc.ActionOpMode;
import com.teamundefined.defined.ftc.AndroidLog;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Poses;
import com.teamundefined.defined.example.actions.AutonomyActions;

/**
 * Example AUTONOMOUS, structured like a real one: build the robot, set the start pose,
 * then on {@code start()} kick off the composed routine through the same
 * {@code ActionRunner} used in TeleOp.
 */
@Autonomous(name = "Example Auto", group = "defined")
public class ExampleAuto extends ActionOpMode {

    private ExampleRobot robot;

    @Override
    protected void onInit() {
        AndroidLog.install();
        robot = new ExampleRobot(hardwareMap);
        robot.drive.setStartingPose(Poses.START);
    }

    @Override
    public void start() {
        super.start();
        // Auto runs through the runner too — exactly like TeleOp.
        runner.startGroup(AutonomyActions.autonomousRoutine(robot));
    }

    @Override
    protected void onLoop(long nowMs) {
        robot.update(); // Pedro needs drive.update() every loop to follow paths
    }
}
