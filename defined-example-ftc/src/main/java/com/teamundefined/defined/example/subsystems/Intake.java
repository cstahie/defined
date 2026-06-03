package com.teamundefined.defined.example.subsystems;

import com.qualcomm.robotcore.hardware.CRServo;

/** Two continuous-rotation servos pulling artifacts in. Mirrors the real Intake API. */
public class Intake {
    private final CRServo servo1, servo2;
    private double power = 0;

    public Intake(CRServo servo1, CRServo servo2) {
        this.servo1 = servo1;
        this.servo2 = servo2;
    }

    public void turnOnSync(boolean reversed) { setPower(reversed ? -1.0 : 1.0); }
    public void turnOffSync() { setPower(0); }

    public void setPower(double power) {
        this.power = power;
        servo1.setPower(power);
        servo2.setPower(-power); // mounted opposite
    }

    public boolean isPoweredOn() { return power != 0; }
    public double getPower() { return power; }

    /** Per-loop hook (e.g. soft-start / current ramp on the real robot). */
    public void update() {}
}
