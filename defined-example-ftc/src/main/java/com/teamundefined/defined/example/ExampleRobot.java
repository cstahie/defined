package com.teamundefined.defined.example;

import com.pedropathing.follower.Follower;
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
 * team's {@code Robot.java}. Actions (in the {@code actions} package) reach into these
 * public subsystems to do work; the OpModes just call {@link #update()} every loop
 * (before the {@code ActionRunner} updates).
 *
 * <p>Wire these hardware names in your robot configuration, or rename to match yours.
 */
public class ExampleRobot {
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

    /** Advance every subsystem one loop. Call once per loop, before the runner. */
    public void update() {
        intake.update();
        flywheel.update();
        turret.update(drive.getPose());
        drive.update(); // Pedro follows the active path / applies teleop drive
    }
}
