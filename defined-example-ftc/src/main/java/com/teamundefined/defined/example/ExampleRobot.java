package com.teamundefined.defined.example;

import com.pedropathing.follower.Follower;

import com.teamundefined.defined.ftc.Robot;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;

import com.teamundefined.defined.example.subsystems.Flywheel;
import com.teamundefined.defined.example.subsystems.Indexer;
import com.teamundefined.defined.example.subsystems.Intake;
import com.teamundefined.defined.example.subsystems.Turret;

/**
 * Owns every subsystem and ticks them each loop — the example's equivalent of a real
 * team's {@code Robot.java}.
 *
 * <p>Extending the library's {@link Robot} means {@link com.teamundefined.defined.ftc.RobotOpMode}
 * drives the whole lifecycle for you: it calls {@link #init()} at INIT, {@link #preUpdate(long)}
 * and {@link #update(long)} each loop in the right order, and {@link #stop()} on exit. Only the
 * hooks this robot actually needs are overridden.
 *
 * <p>Wire these hardware names in your robot configuration, or rename to match yours.
 */
public class ExampleRobot extends Robot {
    public final Follower drive;
    public final Intake intake;
    public final Flywheel flywheel;
    public final Indexer indexer;
    public final Turret turret;

    public ExampleRobot(HardwareMap hw) {
        drive = ExampleConstants.createFollower(hw);

        intake = new Intake(
                hw.get(CRServo.class, "intakeLeft"),
                hw.get(CRServo.class, "intakeRight"));

        flywheel = new Flywheel(
                hw.get(DcMotorEx.class, "flywheel1"),
                hw.get(DcMotorEx.class, "flywheel2"),
                hw.get(Servo.class, "angle"));

        indexer = new Indexer(
                hw.get(Servo.class, "leftGate"),
                hw.get(Servo.class, "centerGate"),
                hw.get(Servo.class, "rightGate"));

        turret = new Turret(hw.get(DcMotorEx.class, "turret"));
    }

    @Override
    public void init() {
        // Nothing extra here — the constructor already grabbed hardware. A real robot would
        // zero encoders, set motor modes, or start a vision pipeline.
    }

    /**
     * Localization first, so anything reading the pose this cycle sees a fresh one.
     * Pedro's own update() is the write half and belongs in {@link #update(long)}.
     */
    @Override
    public void preUpdate(long nowMs) {
        drive.updatePose();
    }

    /** Advance every subsystem. Runs after the OpMode's logic, before the runner. */
    @Override
    public void update(long nowMs) {
        intake.update();
        flywheel.update();
        turret.update(drive.getPose());
        drive.update(); // Pedro follows the active path / applies teleop drive
    }
}
