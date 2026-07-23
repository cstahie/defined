package com.teamundefined.defined.ftc;

import com.teamundefined.defined.Log;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * A driver-station menu for the {@code init_loop}, so alliance colour, start position and
 * tuning values can be set on the field with a controller instead of a rebuild.
 *
 * <p>Deciding alliance in code means a re-deploy between matches; deciding it here takes
 * three button presses at the start line.
 *
 * <h2>Wiring</h2>
 * The menu takes no {@code Gamepad}. Every button is a {@link BooleanSupplier} you supply,
 * so it works with any controller layout, any button mapping, and stays testable:
 *
 * <pre>{@code
 * PreStartMenu menu = new PreStartMenu(telemetry, new PreStartMenu.Buttons()
 *         .up(()    -> gamepad1.dpad_up)
 *         .down(()  -> gamepad1.dpad_down)
 *         .left(()  -> gamepad1.dpad_left)
 *         .right(() -> gamepad1.dpad_right)
 *         .start(() -> gamepad1.cross)        // PlayStation X
 *         .stepUp(()   -> gamepad1.left_bumper)
 *         .stepDown(() -> gamepad1.right_bumper))
 *     .add(new PreStartMenu.EnumItem<>("Alliance", Alliance.class,
 *             () -> Config.alliance, v -> Config.alliance = v))
 *     .add(new PreStartMenu.BooleanItem("Telemetry",
 *             () -> Config.TELEMETRY_ON, v -> Config.TELEMETRY_ON = v))
 *     .add(new PreStartMenu.NumberItem("Shot velocity",
 *             () -> Config.shotVelocity, v -> Config.shotVelocity = v, 10));
 *
 * // in init_loop():
 * if (!menu.isComplete()) menu.update(System.currentTimeMillis());
 * }</pre>
 *
 * <p>Suppliers are polled level-high; the menu does its own rising-edge detection, so one
 * press moves one step. Values are read through their getters every frame, so anything that
 * changes them elsewhere (a dashboard, say) shows up immediately.
 *
 * <p>Left/right adjust the selected item by its step, scaled by a multiplier that the
 * step buttons move between 0.001 and 1000 — coarse first, then fine.
 */
public class PreStartMenu {

    private static final String TAG = "PreStartMenu";

    // ---- Items ----

    /** One editable row. Implement this for a control the built-ins do not cover. */
    public interface Item {
        String getName();
        /** Current value, already formatted for display. */
        String getValue();
        /**
         * @param direction      -1 for left, +1 for right
         * @param stepMultiplier current coarse/fine multiplier
         */
        void adjust(int direction, double stepMultiplier);
    }

    /** An on/off flag. Either direction toggles it; the multiplier is ignored. */
    public static class BooleanItem implements Item {
        private final String name;
        private final BooleanSupplier getter;
        private final java.util.function.Consumer<Boolean> setter;

        public BooleanItem(String name, BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
        }

        @Override public String getName() { return name; }
        @Override public String getValue() { return getter.getAsBoolean() ? "ON" : "OFF"; }
        @Override public void adjust(int direction, double stepMultiplier) {
            setter.accept(!getter.getAsBoolean());
        }
    }

    /** A numeric value adjusted by {@code step * multiplier}, optionally clamped. */
    public static class NumberItem implements Item {
        private final String name;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        private final double step;
        private final double min, max;
        private final int decimals;

        public NumberItem(String name, DoubleSupplier getter, DoubleConsumer setter, double step) {
            this(name, getter, setter, step, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 3);
        }

        public NumberItem(String name, DoubleSupplier getter, DoubleConsumer setter,
                          double step, double min, double max, int decimals) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
            this.step = step;
            this.min = min;
            this.max = max;
            this.decimals = decimals;
        }

        @Override public String getName() { return name; }

        @Override public String getValue() {
            StringBuilder sb = new StringBuilder(16);
            com.teamundefined.defined.Units.appendDouble(sb, getter.getAsDouble(), Math.max(1, decimals));
            return sb.toString();
        }

        @Override public void adjust(int direction, double stepMultiplier) {
            double next = getter.getAsDouble() + direction * step * stepMultiplier;
            if (next < min) next = min;
            if (next > max) next = max;
            setter.accept(next);
        }
    }

    /** Cycles through an enum's constants, wrapping at both ends. */
    public static class EnumItem<E extends Enum<E>> implements Item {
        private final String name;
        private final E[] values;
        private final java.util.function.Supplier<E> getter;
        private final java.util.function.Consumer<E> setter;

        public EnumItem(String name, Class<E> type,
                        java.util.function.Supplier<E> getter,
                        java.util.function.Consumer<E> setter) {
            this.name = name;
            this.values = type.getEnumConstants();
            this.getter = getter;
            this.setter = setter;
        }

        @Override public String getName() { return name; }

        @Override public String getValue() {
            E v = getter.get();
            return v == null ? "—" : v.name();
        }

        @Override public void adjust(int direction, double stepMultiplier) {
            if (values == null || values.length == 0) return;
            E current = getter.get();
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) { index = i; break; }
            }
            // Wrap, so the list is a loop rather than a dead end at each edge.
            index = Math.floorMod(index + direction, values.length);
            setter.accept(values[index]);
        }
    }

    /** Cycles through a fixed list of strings, wrapping at both ends. */
    public static class ChoiceItem implements Item {
        private final String name;
        private final String[] choices;
        private final java.util.function.Supplier<String> getter;
        private final java.util.function.Consumer<String> setter;

        public ChoiceItem(String name, String[] choices,
                          java.util.function.Supplier<String> getter,
                          java.util.function.Consumer<String> setter) {
            this.name = name;
            this.choices = choices;
            this.getter = getter;
            this.setter = setter;
        }

        @Override public String getName() { return name; }

        @Override public String getValue() {
            String v = getter.get();
            return v == null ? "—" : v;
        }

        @Override public void adjust(int direction, double stepMultiplier) {
            if (choices == null || choices.length == 0) return;
            String current = getter.get();
            int index = 0;
            for (int i = 0; i < choices.length; i++) {
                if (choices[i].equals(current)) { index = i; break; }
            }
            index = Math.floorMod(index + direction, choices.length);
            setter.accept(choices[index]);
        }
    }

    // ---- Buttons ----

    /**
     * The controls, as level-high suppliers. Anything left unset simply never fires, so a
     * menu of only booleans can skip the step buttons entirely.
     */
    public static class Buttons {
        BooleanSupplier up = () -> false;
        BooleanSupplier down = () -> false;
        BooleanSupplier left = () -> false;
        BooleanSupplier right = () -> false;
        BooleanSupplier start = () -> false;
        BooleanSupplier stepUp = () -> false;
        BooleanSupplier stepDown = () -> false;

        public Buttons up(BooleanSupplier s)        { if (s != null) this.up = s;       return this; }
        public Buttons down(BooleanSupplier s)      { if (s != null) this.down = s;     return this; }
        public Buttons left(BooleanSupplier s)      { if (s != null) this.left = s;     return this; }
        public Buttons right(BooleanSupplier s)     { if (s != null) this.right = s;    return this; }
        /** Confirms the menu and ends it. */
        public Buttons start(BooleanSupplier s)     { if (s != null) this.start = s;    return this; }
        /** Coarser adjustment (×10). */
        public Buttons stepUp(BooleanSupplier s)    { if (s != null) this.stepUp = s;   return this; }
        /** Finer adjustment (÷10). */
        public Buttons stepDown(BooleanSupplier s)  { if (s != null) this.stepDown = s; return this; }
    }

    /** Rising-edge detector over a level-high supplier. */
    private static final class Edge {
        private final BooleanSupplier source;
        private boolean last = false;
        private boolean justPressed = false;

        Edge(BooleanSupplier source) { this.source = source; }

        void poll() {
            boolean now = source.getAsBoolean();
            justPressed = now && !last;
            last = now;
        }

        boolean wasJustPressed() { return justPressed; }
    }

    // ---- State ----

    private final Telemetry telemetry;
    private final List<Item> items = new ArrayList<>();

    private final Edge up, down, left, right, start, stepUp, stepDown;

    private int index = 0;
    private double stepMultiplier = 1.0;
    private boolean complete = false;
    private String title = "PRE-MATCH CONFIG";

    // Reused across frames so the menu allocates nothing per loop.
    private final List<String> content = new ArrayList<>();
    private final List<String> footer = new ArrayList<>();

    public PreStartMenu(Telemetry telemetry, Buttons buttons) {
        this.telemetry = telemetry;
        this.up = new Edge(buttons.up);
        this.down = new Edge(buttons.down);
        this.left = new Edge(buttons.left);
        this.right = new Edge(buttons.right);
        this.start = new Edge(buttons.start);
        this.stepUp = new Edge(buttons.stepUp);
        this.stepDown = new Edge(buttons.stepDown);
    }

    /** Adds a row. Rows appear in the order added. */
    public PreStartMenu add(Item item) {
        if (item != null) items.add(item);
        return this;
    }

    /** Heading shown above the rows. */
    public PreStartMenu title(String title) {
        if (title != null) this.title = title;
        return this;
    }

    /** True once the driver has pressed start. */
    public boolean isComplete() {
        return complete;
    }

    /** The currently selected row, or {@code null} if the menu is empty. */
    public Item getSelected() {
        return items.isEmpty() ? null : items.get(index);
    }

    /**
     * Every row as {@code "name: value"} — handy for echoing the confirmed settings back to
     * the driver after the menu closes, so a mis-set alliance is caught before the match.
     */
    public List<String> getConfiguredValues() {
        List<String> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            out.add(item.getName() + ": " + item.getValue());
        }
        return out;
    }

    /**
     * Polls the buttons, applies any change and redraws. Call once per {@code init_loop}.
     *
     * @return true once the menu is finished — the same value as {@link #isComplete()}
     */
    public boolean update(long now) {
        if (complete) return true;

        up.poll(); down.poll(); left.poll(); right.poll();
        start.poll(); stepUp.poll(); stepDown.poll();

        if (start.wasJustPressed()) {
            complete = true;
            Log.i(TAG, "Menu confirmed - starting");
            return true;
        }

        if (!items.isEmpty()) {
            if (up.wasJustPressed() && index > 0) index--;
            if (down.wasJustPressed() && index < items.size() - 1) index++;

            Item current = items.get(index);
            if (left.wasJustPressed()) current.adjust(-1, stepMultiplier);
            if (right.wasJustPressed()) current.adjust(1, stepMultiplier);

            if (stepUp.wasJustPressed()) {
                stepMultiplier = Math.min(stepMultiplier * 10.0, 1000.0);
            }
            if (stepDown.wasJustPressed()) {
                stepMultiplier = Math.max(stepMultiplier / 10.0, 0.001);
            }
        }

        display();
        return false;
    }

    private void display() {
        content.clear();
        footer.clear();

        if (items.isEmpty()) {
            content.add("No items configured");
            content.add("");
            content.add("Press START to continue");
        } else {
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                String marker = (i == index) ? " > " : "   ";
                content.add(marker + item.getName() + ": " + item.getValue());
            }
            footer.add("UP/DOWN Select | LEFT/RIGHT Adjust");
            StringBuilder step = new StringBuilder("Step x");
            com.teamundefined.defined.Units.appendDouble(step, stepMultiplier, 3);
            step.append(" | START to begin");
            footer.add(step.toString());
        }

        telemetry.clearAll();
        telemetry.addLine("== " + title + " ==");
        telemetry.addLine("");
        for (int i = 0; i < content.size(); i++) telemetry.addLine(content.get(i));
        if (!footer.isEmpty()) {
            telemetry.addLine("");
            telemetry.addLine("----------------------");
            for (int i = 0; i < footer.size(); i++) telemetry.addLine(footer.get(i));
        }
        telemetry.update();
    }
}
