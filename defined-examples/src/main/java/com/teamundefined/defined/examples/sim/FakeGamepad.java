package com.teamundefined.defined.examples.sim;

/**
 * A stand-in for an FTC PlayStation gamepad. In a real OpMode these fields are
 * updated by the SDK; here a script flips them to simulate driver input.
 *
 * <p>Button names mirror the PlayStation controller (Team Undefined runs PS
 * controllers), so example code reads the same as on the real robot.
 */
public class FakeGamepad {
    public boolean cross;     // X
    public boolean circle;    // O
    public boolean square;    // []
    public boolean triangle;  // /\
    public double leftStickY; // forward/back
}
