package com.teamundefined.defined.ftc.samples;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Slot;
import com.teamundefined.defined.generic.Continuous;
import com.teamundefined.defined.generic.EdgeTriggerAction;
import com.teamundefined.defined.generic.SequentialAction;
import com.teamundefined.defined.generic.ToggleAction;
import com.teamundefined.defined.generic.WaitAction;
import com.teamundefined.defined.ftc.ActionOpMode;
import com.teamundefined.defined.ftc.AndroidLog;

/**
 * A minimal but complete example of using Defined in a real FTC TeleOp.
 *
 * <p>It shows the whole pattern end‑to‑end:
 * <ul>
 *   <li>declare subsystem {@link Slot}s as an enum;</li>
 *   <li>extend {@link ActionOpMode} (it owns the runner + clock);</li>
 *   <li>register slot‑free monitors (drive, intake toggle);</li>
 *   <li>start a slot‑managed group on a button edge.</li>
 * </ul>
 *
 * <p>Wire two motors named {@code "intake"} and {@code "shooter"} in your robot
 * configuration to run it. Team Undefined uses PlayStation controllers, so the
 * buttons below are {@code cross} and {@code triangle}.
 */
@TeleOp(name = "Defined Sample TeleOp", group = "defined")
public class SampleTeleOp extends ActionOpMode {

    private enum Sub implements Slot { INTAKE, SHOOTER }

    private DcMotor intake;
    private DcMotor shooter;

    @Override
    protected void onInit() {
        AndroidLog.install(); // route engine logs to logcat (optional)

        intake = hardwareMap.get(DcMotor.class, "intake");
        shooter = hardwareMap.get(DcMotor.class, "shooter");

        // Continuous, slot‑free monitor: nothing to drive here, just telemetry.
        runner.addMonitor(Continuous.forever("telemetry", now -> {
            telemetry.addData("intake", intake.getPower());
            telemetry.addData("shooter", shooter.getPower());
            telemetry.update();
        }));

        // Cross (X) toggles the intake on/off. Slot‑free because the on/off
        // one‑shots declare no slots.
        runner.addMonitor(ToggleAction.onPress("intake_toggle", () -> gamepad1.cross,
                Action.oneShot("intake_on", now -> intake.setPower(1.0)),
                Action.oneShot("intake_off", now -> intake.setPower(0.0))));

        // Triangle fires a one‑button shoot routine. EdgeTriggerAction inherits the
        // SHOOTER slot from its inner action, so we start it as a group: the runner
        // guarantees only one SHOOTER action runs at a time.
        runner.addMonitor(Continuous.forever("score_button", now -> {
            if (gamepad1.triangle) runner.startGroup(shootRoutine());
        }));
    }

    /** Spin up, dwell, then idle the shooter. Owns the SHOOTER slot. */
    private Action shootRoutine() {
        return new SequentialAction("shoot",
                Action.oneShot("spin_up", now -> shooter.setPower(1.0)),
                WaitAction.ms("dwell", 800),
                Action.oneShot("idle", now -> shooter.setPower(0.0)))
                .requires(Sub.SHOOTER);
    }

    // Unused but shown for completeness: a rising‑edge one‑shot you could add as a
    // started group instead of the polling monitor above.
    @SuppressWarnings("unused")
    private Action shootOnEdge() {
        return EdgeTriggerAction.rising("shoot_edge", () -> gamepad1.triangle, shootRoutine());
    }
}
