package com.teamundefined.defined.ftc;

/**
 * The lifecycle contract for a robot, so {@link RobotOpMode} can drive it without knowing
 * anything about your hardware.
 *
 * <p>Only {@link #init()} is required — everything else is an optional hook with a do-nothing
 * default, so you implement the ones your robot actually needs and ignore the rest.
 *
 * <pre>{@code
 * public class MyRobot extends Robot {
 *     public final Follower drive;
 *     public final Intake intake;
 *
 *     public MyRobot(HardwareMap hw) {
 *         drive = Constants.createFollower(hw);
 *         intake = new Intake(hw);
 *     }
 *
 *     @Override public void init() {
 *         intake.init();
 *     }
 *
 *     @Override public void update(long nowMs) {
 *         drive.update();
 *         intake.update();
 *     }
 *
 *     @Override public void stop() {
 *         intake.stop();
 *     }
 * }
 * }</pre>
 *
 * <h2>When each hook runs</h2>
 * <ol>
 *   <li>{@link #init()} — once, right after construction, during the OpMode's INIT</li>
 *   <li>{@link #initUpdate()} — every cycle between INIT and START (including while the
 *       pre-start menu is open), for things like warming a camera or holding a servo</li>
 *   <li>{@link #start(boolean)} — once when the match begins</li>
 *   <li>{@link #preUpdate(long)} → your OpMode's loop hook → {@link #update(long)} — every
 *       cycle of the match</li>
 *   <li>{@link #stop()} — once when the OpMode ends</li>
 * </ol>
 *
 * <p>The split between {@code preUpdate} and {@code update} exists because sensor reads and
 * localization must happen <em>before</em> your OpMode reads the robot's state, while
 * subsystem outputs must be written <em>after</em>. Put bulk-cache clearing and odometry in
 * {@code preUpdate}; put motor/servo writes in {@code update}.
 */
public abstract class Robot {

    /** Set up hardware. Called once during the OpMode's INIT, after construction. */
    public abstract void init();

    /**
     * Called every cycle between INIT and START, including while the pre-start menu is open.
     * Use for anything that must keep ticking before the match (vision warm-up, servo holds).
     */
    public void initUpdate() {}

    /**
     * Called once when the match starts.
     *
     * @param isTeleOp true for a TeleOp run, false for autonomous — lets one robot class
     *                 configure itself differently for each without duplicating code
     */
    public void start(boolean isTeleOp) {}

    /**
     * First half of the loop, before your OpMode's own logic runs. Clear the bulk cache and
     * update localization here, so everything downstream sees fresh, consistent state.
     *
     * @param nowMs milliseconds since the match started
     */
    public void preUpdate(long nowMs) {}

    /**
     * Second half of the loop, after your OpMode's logic and before the {@code ActionRunner}
     * ticks. Run subsystem state machines and write hardware outputs here.
     *
     * @param nowMs milliseconds since the match started
     */
    public void update(long nowMs) {}

    /**
     * The OpMode's runtime in seconds, pushed in every cycle. Useful for autonomous timing
     * ("stop harvesting at t=25s"). Default ignores it.
     */
    public void setOpModeTime(double seconds) {}

    /**
     * Called once when the OpMode ends — including on an emergency stop. Shut down threads and
     * release hardware here; this is the only place guaranteed to run after a match.
     */
    public void stop() {}
}
