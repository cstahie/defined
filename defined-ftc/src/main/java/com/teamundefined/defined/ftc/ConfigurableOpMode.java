package com.teamundefined.defined.ftc;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import java.util.List;

/**
 * An {@link OpMode} with a driver-station config menu already wired up.
 *
 * <p>Extend this and call {@link #enablePreStartMenu} in {@code init()} to let the driver
 * set alliance, start position and tuning values on the field — no rebuild between matches:
 *
 * <pre>{@code
 * @TeleOp
 * public class MyTeleOp extends ConfigurableOpMode {
 *     @Override public void init() {
 *         enablePreStartMenu(Config.class,
 *                 "Config.alliance",
 *                 "Telemetry.TELEMETRY_ON",
 *                 "Flywheel.SHOT_VELOCITY");
 *     }
 *
 *     @Override public void loop() {
 *         // drive the robot; the menu is already finished by now
 *     }
 * }
 * }</pre>
 *
 * <p>The menu runs during {@code init_loop} and ends when the driver presses X (cross).
 * Override {@link #onInitLoop(long)} for anything else that should happen while waiting;
 * it is only called once the menu is done, so your telemetry never fights the menu's.
 *
 * <p>Default controls are D-pad to navigate/adjust, X to confirm, L1/R1 for coarse/fine step.
 * Override {@link #menuButtons()} to remap.
 *
 * <p>If you also want an {@code ActionRunner}, extend {@link ActionOpMode} instead — it
 * builds on this class.
 */
public abstract class ConfigurableOpMode extends OpMode {

    /** The menu, or {@code null} when {@link #enablePreStartMenu} was never called. */
    protected PreStartMenu preStartMenu = null;

    private boolean menuComplete = false;

    /**
     * Builds the pre-start menu from field keys on {@code configClass}.
     * See {@link ConfigFields} for the key format.
     *
     * @param configClass your config holder, e.g. {@code Config.class}
     * @param fieldKeys   keys such as {@code "Config.alliance"}, {@code "Telemetry.LOG_ON"}
     */
    protected void enablePreStartMenu(Class<?> configClass, String... fieldKeys) {
        preStartMenu = new PreStartMenu(telemetry, menuButtons()).title(menuTitle());
        ConfigFields.addAll(preStartMenu, configClass, fieldKeys);
    }

    /** Adds a hand-built row — for anything {@link ConfigFields} cannot express. */
    protected void addMenuItem(PreStartMenu.Item item) {
        if (preStartMenu == null) {
            preStartMenu = new PreStartMenu(telemetry, menuButtons()).title(menuTitle());
        }
        preStartMenu.add(item);
    }

    /** Override to remap the menu controls. Defaults to D-pad + X + bumpers on gamepad1. */
    protected PreStartMenu.Buttons menuButtons() {
        return new PreStartMenu.Buttons()
                .up(() -> gamepad1.dpad_up)
                .down(() -> gamepad1.dpad_down)
                .left(() -> gamepad1.dpad_left)
                .right(() -> gamepad1.dpad_right)
                .start(() -> gamepad1.cross)             // PlayStation X / Xbox A
                .stepUp(() -> gamepad1.left_bumper)      // L1 — coarser
                .stepDown(() -> gamepad1.right_bumper);  // R1 — finer
    }

    /** Override to change the menu heading. */
    protected String menuTitle() {
        return "PRE-MATCH CONFIG";
    }

    /** True when there is no menu, or the driver has confirmed it. */
    protected boolean isPreStartMenuComplete() {
        return preStartMenu == null || menuComplete;
    }

    /** Every configured row as {@code "name: value"}; empty when no menu is enabled. */
    protected List<String> getConfiguredValues() {
        return preStartMenu == null ? java.util.Collections.emptyList()
                                    : preStartMenu.getConfiguredValues();
    }

    @Override
    public void init_loop() {
        if (preStartMenu != null && !menuComplete) {
            if (preStartMenu.update(System.currentTimeMillis())) {
                menuComplete = true;
                showConfirmedSettings();
            }
            return; // menu owns telemetry until it is finished
        }
        onInitLoop(System.currentTimeMillis());
    }

    /**
     * Called repeatedly between INIT and START, once the menu (if any) is complete.
     * Use it to warm up subsystems or show status. Default shows the confirmed settings.
     */
    protected void onInitLoop(long nowMs) {
        showConfirmedSettings();
    }

    /** Echoes the chosen values so a mis-set alliance is caught before the match starts. */
    protected void showConfirmedSettings() {
        if (preStartMenu == null) return;
        telemetry.clearAll();
        telemetry.addLine("== CONFIGURATION COMPLETE ==");
        telemetry.addLine("");
        List<String> values = getConfiguredValues();
        for (int i = 0; i < values.size(); i++) {
            telemetry.addLine(values.get(i));
        }
        telemetry.addLine("");
        telemetry.addLine("Press START to begin");
        telemetry.update();
    }
}
