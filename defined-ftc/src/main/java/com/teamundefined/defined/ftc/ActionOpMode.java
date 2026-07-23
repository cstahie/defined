package com.teamundefined.defined.ftc;

import com.teamundefined.defined.runner.ActionRunner;

/**
 * A ready-made OpMode base that owns an {@link ActionRunner} and ticks it every
 * loop with a monotonic clock, so the whole robot stays deterministic.
 *
 * <p>Subclass it and override {@link #onInit()} to register your monitors and
 * starting groups:
 *
 * <pre>{@code
 * @TeleOp(name = "My TeleOp")
 * public class MyTeleOp extends ActionOpMode {
 *     @Override protected void onInit() {
 *         // Optional: let the driver pick alliance etc. before START
 *         enablePreStartMenu(Config.class, "Config.alliance", "Telemetry.LOG_ON");
 *
 *         runner.addMonitor(IntakeActions.toggle(robot, () -> gamepad1.cross));
 *     }
 * }
 * }</pre>
 *
 * <p>The runner is updated automatically in {@link #loop()}; override
 * {@link #onLoop(long)} if you need per-cycle work alongside it.
 *
 * <p>Logging needs no setup — {@link com.teamundefined.defined.Log} finds
 * {@code android.util.Log} by itself. Raise {@code Log.verbosity} to see the engine's
 * state transitions.
 *
 * <p>Extends {@link ConfigurableOpMode}, so the pre-start menu is available here too.
 */
public abstract class ActionOpMode extends ConfigurableOpMode {

    /** The shared runner. Register monitors and start groups against it. */
    protected final ActionRunner runner = new ActionRunner();

    private long startNanos = 0;

    /** Current loop timestamp in milliseconds since {@code start()}. */
    protected final long nowMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    @Override
    public final void init() {
        startNanos = System.nanoTime();
        onInit();
    }

    @Override
    public void start() {
        // Reset the clock so monitors see t=0 when the match begins.
        startNanos = System.nanoTime();
    }

    /**
     * The timestamp fed to the robot, the runner and every action, once per loop.
     *
     * <p>Defaults to {@link #nowMs()} — milliseconds since START — which keeps timing
     * deterministic and testable. Override to return {@code System.currentTimeMillis()} if
     * your existing actions compare the injected timestamp against wall-clock values;
     * mixing the two silently breaks every timeout.
     */
    protected long timestampMs() {
        return nowMs();
    }

    @Override
    public final void loop() {
        long now = timestampMs();
        onLoopInternal(now);
        runner.update(now);
        afterRunnerUpdate(now);
    }

    /** Set up monitors / starting groups. Called once during init. */
    protected abstract void onInit();

    /** Optional per-cycle hook, called before the runner updates. */
    protected void onLoop(long nowMs) {}

    /**
     * Extension point for subclasses that need to wrap {@link #onLoop(long)} with their own
     * work — {@link RobotOpMode} uses it to tick the robot either side of your logic.
     * Override {@link #onLoop(long)} instead unless you are building a base class.
     */
    protected void onLoopInternal(long nowMs) {
        onLoop(nowMs);
    }

    /**
     * Runs after the runner has ticked, once every action has had its turn. Used by
     * {@link RobotOpMode} for telemetry, which should reflect the state actions just produced.
     */
    protected void afterRunnerUpdate(long nowMs) {}
}
