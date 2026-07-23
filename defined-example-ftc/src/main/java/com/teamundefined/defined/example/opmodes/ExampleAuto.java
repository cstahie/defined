package com.teamundefined.defined.example.opmodes;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;

import com.teamundefined.defined.ftc.RobotOpMode;
import com.teamundefined.defined.example.ExampleRobot;
import com.teamundefined.defined.example.Poses;
import com.teamundefined.defined.example.actions.AutonomyActions;

/**
 * Example AUTONOMOUS, structured like a real one: set the start pose, then on
 * {@code start()} kick off the composed routine through the same {@code ActionRunner}
 * used in TeleOp.
 *
 * <p>There is no {@code onLoop} at all — {@link RobotOpMode} already ticks the robot and the
 * runner every cycle, and in autonomous the actions do the rest.
 */
@Autonomous(name = "Example Auto", group = "defined")
public class ExampleAuto extends RobotOpMode<ExampleRobot> {

    @Override
    protected ExampleRobot createRobot() {
        return new ExampleRobot(hardwareMap);
    }

    @Override
    protected void onRobotInit() {
        robot.drive.setStartingPose(Poses.START);
    }

    @Override
    public void start() {
        super.start();
        // Auto runs through the runner too — exactly like TeleOp.
        runner.startGroup(AutonomyActions.autonomousRoutine(robot));
    }

}
