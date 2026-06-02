package com.teamundefined.defined.ftc;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

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
 *         AndroidLog.install();
 *         runner.addMonitor(GamepadButtons.toggleIntake(...));
 *     }
 * }
 * }</pre>
 *
 * <p>The runner is updated automatically in {@link #loop()}; override
 * {@link #onLoop(long)} if you need per-cycle work alongside it.
 */
public abstract class ActionOpMode extends OpMode {

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

    @Override
    public final void loop() {
        long now = nowMs();
        onLoop(now);
        runner.update(now);
    }

    /** Set up monitors / starting groups. Called once during init. */
    protected abstract void onInit();

    /** Optional per-cycle hook, called before the runner updates. */
    protected void onLoop(long nowMs) {}
}
