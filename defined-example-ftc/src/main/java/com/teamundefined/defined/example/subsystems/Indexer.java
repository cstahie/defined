package com.teamundefined.defined.example.subsystems;

import com.qualcomm.robotcore.hardware.Servo;

/**
 * Three servo gates holding artifacts in a row. The real robot also has color
 * sensors here for pattern matching; this example keeps it to gate control so the
 * structure stays clear.
 */
public class Indexer {
    private static final double OPEN = 0.7, CLOSED = 0.2;

    private final Servo left, center, right;

    public Indexer(Servo left, Servo center, Servo right) {
        this.left = left;
        this.center = center;
        this.right = right;
    }

    public void openAll() { left.setPosition(OPEN); center.setPosition(OPEN); right.setPosition(OPEN); }
    public void closeAll() { left.setPosition(CLOSED); center.setPosition(CLOSED); right.setPosition(CLOSED); }

    /** index: 0 = left, 1 = center, 2 = right. */
    public void openGate(int index)  { gate(index).setPosition(OPEN); }
    public void closeGate(int index) { gate(index).setPosition(CLOSED); }

    private Servo gate(int index) {
        switch (index) {
            case 0: return left;
            case 2: return right;
            default: return center;
        }
    }
}
