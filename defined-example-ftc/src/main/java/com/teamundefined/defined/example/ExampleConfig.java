package com.teamundefined.defined.example;

/**
 * The handful of settings a driver may want to change at the field, without a rebuild.
 *
 * <p>Plain static fields on purpose — {@code ExampleTeleOp} edits them through a
 * {@link com.teamundefined.defined.ftc.PreStartMenu} during {@code init_loop}, and the rest
 * of the robot just reads them. Anything you would otherwise recompile to change belongs
 * here: alliance, a starting position, a shot velocity you tweak between matches.
 */
public final class ExampleConfig {

    private ExampleConfig() {}

    public enum Alliance { RED, BLUE }

    public enum StartPosition { NEAR, FAR }

    /** Which goal the turret should aim at. Must be set before the match starts. */
    public static Alliance alliance = Alliance.RED;

    /** Where the robot is placed on the field. */
    public static StartPosition startPosition = StartPosition.NEAR;

    /** Flywheel target for a standard shot, in ticks/second. */
    public static double shotVelocity = 2100;

    /** Master telemetry switch — turn off for competition to save loop time. */
    public static boolean telemetryOn = true;

    /**
     * Section profiling. Read once when ExampleRobot is constructed, so flip it in the menu
     * before START (a SectionProfiler decides at construction whether to measure at all).
     */
    public static boolean profilerOn = true;
}
