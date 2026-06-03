package com.teamundefined.defined.example.subsystems;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;

/**
 * Dual-motor flywheel with an angle servo. Uses the motors' built-in velocity
 * control; {@link #isReady()} reports when both are at the target — the signal a
 * shooting action waits on. Mirrors the real Flywheel API.
 */
public class Flywheel {
    private final DcMotorEx motor1, motor2;
    private final Servo angleServo;

    private double targetVelocity = 0;       // ticks/sec
    public double tolerance = 30;            // ticks/sec

    public Flywheel(DcMotorEx motor1, DcMotorEx motor2, Servo angleServo) {
        this.motor1 = motor1;
        this.motor2 = motor2;
        this.angleServo = angleServo;
        motor1.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        motor2.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
    }

    public void setTargetVelocity(double velocity) { this.targetVelocity = velocity; }

    public boolean isReady() {
        if (targetVelocity <= 0) return false;
        double avg = (motor1.getVelocity() + motor2.getVelocity()) / 2.0;
        return Math.abs(avg - targetVelocity) <= tolerance;
    }

    public void setAngle(double position) { angleServo.setPosition(position); }

    public void stopFlywheelSync() {
        targetVelocity = 0;
        motor1.setVelocity(0);
        motor2.setVelocity(0);
    }

    /** Push the target to the motors each loop (their PIDF does the rest). */
    public void update() {
        motor1.setVelocity(targetVelocity);
        motor2.setVelocity(targetVelocity);
    }
}
