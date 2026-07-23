package com.teamundefined.defined.ftc;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import java.util.List;

/**
 * A {@link LinearOpMode} with a driver-station config menu, for the
 * "write it as a script" style — mostly autonomous and tuning OpModes.
 *
 * <pre>{@code
 * @Autonomous
 * public class MyAuto extends ConfigurableLinearOpMode {
 *     @Override public void runOpMode() {
 *         enablePreStartMenu(Config.class, "Config.alliance", "Auto.shootPreload");
 *
 *         Robot robot = new Robot(hardwareMap);
 *         waitForStartWithMenu();       // menu runs here, then waits for START
 *
 *         while (opModeIsActive()) { ... }
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #waitForStartWithMenu()} replaces {@code waitForStart()}: it runs the menu, shows
 * the confirmed values, then blocks for START as usual. With no menu enabled it simply calls
 * {@code waitForStart()}, so it is always safe to use.
 *
 * <p>Default controls are D-pad to navigate/adjust, X to confirm, L1/R1 for coarse/fine step.
 * Override {@link #menuButtons()} to remap.
 */
public abstract class ConfigurableLinearOpMode extends LinearOpMode {

    /** The menu, or {@code null} when {@link #enablePreStartMenu} was never called. */
    protected PreStartMenu preStartMenu = null;

    /**
     * Builds the pre-start menu from field keys on {@code configClass}.
     * See {@link ConfigFields} for the key format.
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

    /** Every configured row as {@code "name: value"}; empty when no menu is enabled. */
    protected List<String> getConfiguredValues() {
        return preStartMenu == null ? java.util.Collections.emptyList()
                                    : preStartMenu.getConfiguredValues();
    }

    /**
     * Runs the menu, then waits for START. Use instead of {@code waitForStart()}.
     *
     * <p>Safe with no menu enabled — it falls through to a plain {@code waitForStart()}.
     */
    protected void waitForStartWithMenu() {
        if (preStartMenu == null) {
            waitForStart();
            return;
        }

        // Drive the menu until the driver confirms it — or until they just hit START,
        // which must always work even mid-menu.
        while (!isStarted() && !isStopRequested()) {
            if (preStartMenu.update(System.currentTimeMillis())) break;
            sleep(20); // the DS only refreshes so fast; a tight loop buys nothing
        }

        if (!isStarted() && !isStopRequested()) {
            showConfirmedSettings();
            waitForStart();
        }
    }

    /** Echoes the chosen values so a mis-set alliance is caught before the match starts. */
    protected void showConfirmedSettings() {
        telemetry.clearAll();
        telemetry.addLine("== CONFIGURATION COMPLETE ==");
        telemetry.addLine("");
        List<String> values = getConfiguredValues();
        if (values.isEmpty()) {
            telemetry.addLine("  (no fields configured)");
        } else {
            for (int i = 0; i < values.size(); i++) {
                telemetry.addLine(values.get(i));
            }
        }
        telemetry.addLine("");
        telemetry.addLine("Press START to begin match");
        telemetry.update();
    }
}
