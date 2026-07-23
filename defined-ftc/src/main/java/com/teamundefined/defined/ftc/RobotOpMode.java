package com.teamundefined.defined.ftc;

import com.teamundefined.defined.TelemetrySnapshot;

/**
 * A complete OpMode base: your {@link Robot}, an {@code ActionRunner}, a pre-start config
 * menu and non-blocking telemetry, all wired together and ticked in the right order.
 *
 * <p>This is the "everything on" starting point. A working TeleOp is:
 *
 * <pre>{@code
 * @TeleOp(name = "My TeleOp")
 * public class MyTeleOp extends RobotOpMode<MyRobot> {
 *
 *     @Override protected MyRobot createRobot() {
 *         return new MyRobot(hardwareMap);
 *     }
 *
 *     @Override protected void onInit() {
 *         enablePreStartMenu(Config.class, "Config.alliance", "Telemetry.LOG_ON");
 *         runner.addMonitor(IntakeActions.toggle(robot, () -> gamepad1.cross));
 *     }
 *
 *     @Override protected void onLoop(long nowMs) {
 *         robot.drive.setTeleOpDrive(-gamepad1.left_stick_y, -gamepad1.left_stick_x,
 *                                    -gamepad1.right_stick_x);
 *     }
 *
 *     @Override protected void fillSnapshot(TelemetrySnapshot s) {
 *         s.put("intake", robot.intake.getState().toString());
 *     }
 * }
 * }</pre>
 *
 * <h2>Loop order</h2>
 * <ol>
 *   <li>{@link Robot#preUpdate(long)} — sensor reads, localization</li>
 *   <li>{@link #onLoop(long)} — your driver controls and per-cycle logic</li>
 *   <li>{@link Robot#setOpModeTime(double)} and {@link Robot#update(long)} — subsystems</li>
 *   <li>{@code runner.update(now)} — actions</li>
 *   <li>telemetry — snapshot and display, both throttled</li>
 * </ol>
 *
 * <h2>Telemetry</h2>
 * Values are captured into a reused {@link TelemetrySnapshot} (no per-loop allocation) and
 * formatted on a background thread, so string building never lands in the loop. Override
 * {@link #fillSnapshot} to record values and {@link #formatTelemetry} to lay them out.
 * Capture runs every {@link #telemetryRefreshCycles()} loops — every 20 by default, because
 * the Driver Station cannot show more than that anyway.
 *
 * <p>Turn it off for competition by overriding {@link #isTelemetryEnabled()}.
 */
public abstract class RobotOpMode<R extends Robot> extends ActionOpMode {

    /** Your robot. Created by {@link #createRobot()} during INIT, before {@link #onInit()}. */
    protected R robot;

    /** Reused every cycle — never reallocated, so telemetry costs no GC. */
    protected TelemetrySnapshot telemetrySnapshot;

    /** Formats snapshots off the loop thread. */
    protected AsyncTelemetryProcessor telemetryProcessor;

    private int loopCounter = 0;

    /** Build your robot here. Called once during INIT; {@code hardwareMap} is ready. */
    protected abstract R createRobot();

    @Override
    protected final void onInit() {
        telemetrySnapshot = new TelemetrySnapshot(telemetrySnapshotCapacity());
        telemetryProcessor = new AsyncTelemetryProcessor();

        robot = createRobot();
        if (robot != null) robot.init();

        onRobotInit();
    }

    /**
     * Set up monitors, starting groups and the pre-start menu. Called once during INIT,
     * after the robot is constructed and {@link Robot#init()} has run.
     */
    protected abstract void onRobotInit();

    @Override
    protected void onInitLoop(long nowMs) {
        if (robot != null) robot.initUpdate();
        super.onInitLoop(nowMs); // shows the confirmed menu values
    }

    @Override
    public void start() {
        super.start();
        if (robot != null) robot.start(isTeleOp());
    }

    @Override
    protected void onLoopInternal(long nowMs) {
        if (robot != null) robot.preUpdate(nowMs);

        onLoop(nowMs);

        if (robot != null) {
            robot.setOpModeTime(time);
            robot.update(nowMs);
        }
    }

    @Override
    public void stop() {
        // Cancel actions first so nothing re-commands hardware while we are shutting down.
        runner.emergencyCancelAllActions();

        if (robot != null) robot.stop();

        if (telemetryProcessor != null) telemetryProcessor.shutdown();
    }

    // ---- Telemetry ----

    /**
     * Record this cycle's values into {@code snapshot} — cheap key/value puts only, no
     * string formatting. Called at most every {@link #telemetryRefreshCycles()} loops.
     */
    protected void fillSnapshot(TelemetrySnapshot snapshot) {}

    /**
     * Lay the captured values out for display. Runs on a background thread, so this is where
     * expensive formatting belongs — never in {@link #onLoop(long)}.
     */
    protected void formatTelemetry(TelemetrySnapshot snapshot,
                                   AsyncTelemetryProcessor.FormattedTelemetry output) {
        snapshot.forEach(e -> output.addData(e.key, e.value));
    }

    /** Override and return false to disable telemetry entirely (competition). */
    protected boolean isTelemetryEnabled() {
        return true;
    }

    /** Loops between snapshot captures. Lower is more responsive, higher is cheaper. */
    protected int telemetryRefreshCycles() {
        return 20;
    }

    /** Expected number of telemetry entries, used to pre-size the snapshot map. */
    protected int telemetrySnapshotCapacity() {
        return 30;
    }

    /**
     * True in a TeleOp run. Detected from the {@code @TeleOp} annotation, and passed to
     * {@link Robot#start(boolean)} so one robot class can behave differently in each mode.
     */
    protected boolean isTeleOp() {
        return getClass().isAnnotationPresent(
                com.qualcomm.robotcore.eventloop.opmode.TeleOp.class);
    }

    /** Called by {@link ActionOpMode} after the runner ticks. */
    @Override
    protected void afterRunnerUpdate(long nowMs) {
        if (!isTelemetryEnabled()) return;

        loopCounter++;
        if (loopCounter >= telemetryRefreshCycles()) {
            loopCounter = 0;
            fillSnapshot(telemetrySnapshot);
            telemetryProcessor.submit(telemetrySnapshot, this::formatTelemetry);
        }

        // Non-blocking: shows whatever the background thread has finished formatting.
        telemetryProcessor.displayIfReady(telemetry);
    }
}
