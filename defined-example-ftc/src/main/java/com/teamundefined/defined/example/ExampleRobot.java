package com.teamundefined.defined.example;

import com.pedropathing.follower.Follower;

import com.teamundefined.defined.HardwareScheduler;
import com.teamundefined.defined.SectionProfiler;
import com.teamundefined.defined.SystemMonitor;
import com.teamundefined.defined.ftc.Robot;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.VoltageSensor;

import java.util.List;

import com.teamundefined.defined.example.subsystems.Flywheel;
import com.teamundefined.defined.example.subsystems.Indexer;
import com.teamundefined.defined.example.subsystems.Intake;
import com.teamundefined.defined.example.subsystems.Turret;

/**
 * Owns every subsystem and ticks them each loop — the example's equivalent of a real
 * team's {@code Robot.java}.
 *
 * <p>Extending the library's {@link Robot} means {@link com.teamundefined.defined.ftc.RobotOpMode}
 * drives the whole lifecycle for you: it calls {@link #init()} at INIT, {@link #preUpdate(long)}
 * and {@link #update(long)} each loop in the right order, and {@link #stop()} on exit. Only the
 * hooks this robot actually needs are overridden.
 *
 * <p>Wire these hardware names in your robot configuration, or rename to match yours.
 */
public class ExampleRobot extends Robot {

    /** Loop sections we measure. Keep the list short — you profile what you suspect. */
    public enum Section { PRE_UPDATE, SUBSYSTEMS }

    /** Periodic hardware reads, scheduled so at most one runs per loop. */
    public enum Read { FLYWHEEL_CURRENT, BATTERY }

    public final Follower drive;
    public final Intake intake;
    public final Flywheel flywheel;
    public final Indexer indexer;
    public final Turret turret;

    /** Where loop time goes. Read it with {@code profiler.getFormattedStats()}. */
    public final SectionProfiler<Section> profiler =
            new SectionProfiler<>(ExampleConfig.profilerOn);

    /** Memory, CPU, GC and cycle time. */
    public final SystemMonitor systemMonitor = new SystemMonitor(0.8);

    /**
     * Spreads expensive I2C/USB reads across loops so no single cycle pays for all of them.
     * Register here, then call {@code hardware.update(now)} once per loop.
     */
    public final HardwareScheduler<Read> hardware = new HardwareScheduler<>();

    /** Every Lynx hub, for manual bulk-cache control. */
    private final List<LynxModule> allHubs;

    public double batteryVolts = 0;
    public double flywheelAmps = 0;

    public ExampleRobot(HardwareMap hw) {
        drive = ExampleConstants.createFollower(hw);

        intake = new Intake(
                hw.get(CRServo.class, "intakeLeft"),
                hw.get(CRServo.class, "intakeRight"));

        flywheel = new Flywheel(
                hw.get(DcMotorEx.class, "flywheel1"),
                hw.get(DcMotorEx.class, "flywheel2"),
                hw.get(Servo.class, "angle"));

        indexer = new Indexer(
                hw.get(Servo.class, "leftGate"),
                hw.get(Servo.class, "centerGate"),
                hw.get(Servo.class, "rightGate"));

        turret = new Turret(hw.get(DcMotorEx.class, "turret"));

        VoltageSensor battery = hw.voltageSensor.iterator().next();

        // Manual bulk caching: we clear the cache once per loop (in preUpdate) and every read
        // after that is served from it, so a loop does at most one hub round-trip.
        allHubs = hw.getAll(LynxModule.class);
        for (LynxModule hub : allHubs) {
            hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);
        }

        // Expensive reads, sampled only as often as they actually need to be. The scheduler
        // runs one per loop, least-run first, so these never bunch up on the same cycle.
        hardware.register(Read.FLYWHEEL_CURRENT, "flywheel_current", 250,
                () -> flywheelAmps = flywheel.getCurrentAmps(), true);
        hardware.register(Read.BATTERY, "battery", 1000,
                () -> batteryVolts = battery.getVoltage(), true);

        systemMonitor.addStandardMetrics();
    }

    @Override
    public void init() {
        // Constructor already grabbed hardware. A real robot would zero encoders, set motor
        // modes, or start a vision pipeline here.
    }

    /**
     * First half of the loop, before the OpMode's own logic runs. Clear the bulk cache so
     * every sensor read this cycle is fresh and consistent, refresh localization, then let
     * the scheduler tick one due hardware read.
     */
    @Override
    public void preUpdate(long nowMs) {
        profiler.start(Section.PRE_UPDATE);

        for (LynxModule hub : allHubs) hub.clearBulkCache();
        drive.updatePose();
        hardware.update(nowMs);       // at most one scheduled read runs
        systemMonitor.update(nowMs);

        profiler.stop(Section.PRE_UPDATE);
    }

    /** Advance every subsystem. Runs after the OpMode's logic, before the runner. */
    @Override
    public void update(long nowMs) {
        profiler.time(Section.SUBSYSTEMS, () -> {
            intake.update();
            flywheel.update();
            turret.update(drive.getPose());
            drive.update(); // Pedro follows the active path / applies teleop drive
        });
    }
}
