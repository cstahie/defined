package com.teamundefined.defined;

import java.util.ArrayList;
import java.util.List;

/**
 * Spreads periodic hardware reads across loop iterations so at most <b>one</b> runs per cycle.
 *
 * <p>On a REV Control Hub, sensor reads are the expensive part of the loop: an I2C color
 * sensor, a distance sensor and a motor-current read all landing on the same cycle produce a
 * visible latency spike. Each of those rarely needs to be sampled every loop. Register them
 * here with the interval each actually needs, call {@link #update(long)} once per loop, and
 * the scheduler runs the single most-overdue task — keeping per-cycle cost flat.
 *
 * <p>Selection is "least-run first": among all tasks that are due, the one executed fewest
 * times wins, so a slow 1 s task is never starved by a fast 20 ms one.
 *
 * <p>{@code T} is your own task-type key, typically an enum:
 *
 * <pre>{@code
 * enum Read { COLOR, DISTANCE, BATTERY }
 *
 * HardwareScheduler<Read> hw = new HardwareScheduler<>();
 * hw.register(Read.COLOR,    "left_color", 100, () -> leftColor.read(),  true);
 * hw.register(Read.DISTANCE, "front_dist", 500, () -> frontDist.read(),  true);
 *
 * // once per loop:
 * hw.update(System.currentTimeMillis());
 * }</pre>
 *
 * <p>A callback that throws is logged and swallowed — one flaky I2C device must not take
 * down the OpMode. Not thread-safe; drive it from the OpMode loop.
 */
public class HardwareScheduler<T> {

    private static final String TAG = "HARDWARE_SCHEDULER";

    /** A unit of periodic work. */
    @FunctionalInterface
    public interface UpdateCallback {
        void execute();
    }

    /** One registered task and its schedule. */
    public class ScheduledUpdate {
        public final T type;
        public final String name;
        public long intervalMs;
        public final UpdateCallback callback;
        public boolean enabled;
        public long nextUpdateMs;
        public int updateCount;

        ScheduledUpdate(T type, String name, long intervalMs, UpdateCallback callback, boolean enabled) {
            this.type = type;
            this.name = name;
            this.intervalMs = intervalMs;
            this.callback = callback;
            this.enabled = enabled;
            this.nextUpdateMs = 0; // due immediately on the first cycle
            this.updateCount = 0;
        }

        public boolean isDue(long currentTimeMs) {
            return enabled && currentTimeMs >= nextUpdateMs;
        }

        void execute(long currentTimeMs) {
            try {
                callback.execute();
            } catch (Exception e) {
                // Hardware/I2C failure — log and carry on rather than killing the loop.
                Log.i(TAG, () -> "Callback error [" + name + "]: " + e.getMessage());
            }
            long now = System.currentTimeMillis();
            nextUpdateMs = now + intervalMs;

            Log.i(TAG, 150, () -> "Executed " + name + " [" + type + "], took "
                    + (now - currentTimeMs) + "ms, next in " + intervalMs + "ms");
            updateCount++;
        }

        /** Enabling defers the next run by a full interval; disabling stops it entirely. */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (enabled) {
                nextUpdateMs = System.currentTimeMillis() + intervalMs;
            }
        }

        /** Changes the period, restarting the countdown from now. */
        public void setIntervalMs(long ms) {
            this.intervalMs = ms;
            nextUpdateMs = System.currentTimeMillis() + intervalMs;
        }
    }

    private final List<ScheduledUpdate> updates = new ArrayList<>();

    /**
     * Registers a periodic task.
     *
     * @param type       your task-type key, used by the {@code *All*} lookups
     * @param name       unique label, used by the by-name lookups and in logs
     * @param intervalMs how often this task should run
     * @param callback   the work to perform
     * @param enabled    whether it starts enabled
     */
    public void register(T type, String name, long intervalMs, UpdateCallback callback, boolean enabled) {
        updates.add(new ScheduledUpdate(type, name, intervalMs, callback, enabled));
    }

    /**
     * Runs at most one due task. Call once per loop iteration.
     *
     * @param currentTimeMs current time in milliseconds
     * @return the type that ran, or {@code null} if nothing was due
     */
    public T update(long currentTimeMs) {
        ScheduledUpdate best = null;

        // Indexed loop, no iterator: this runs every cycle and allocation here is GC pressure.
        for (int i = 0; i < updates.size(); i++) {
            ScheduledUpdate update = updates.get(i);
            if (!update.isDue(currentTimeMs)) continue;
            // Fewest runs wins, so infrequent tasks are never starved by frequent ones.
            if (best == null || update.updateCount < best.updateCount) {
                best = update;
            }
        }

        if (best == null) return null;

        best.execute(currentTimeMs);
        return best.type;
    }

    /** Enables/disables the first task registered with {@code type}. */
    public void setAllEnabled(T type, boolean enabled) {
        for (int i = 0; i < updates.size(); i++) {
            ScheduledUpdate update = updates.get(i);
            if (update.type == type) {
                update.setEnabled(enabled);
                return;
            }
        }
    }

    public void setEnabled(String name, boolean enabled) {
        ScheduledUpdate u = get(name);
        if (u != null) u.setEnabled(enabled);
    }

    public boolean isEnabled(String name) {
        ScheduledUpdate u = get(name);
        return u != null && u.enabled;
    }

    public void setIntervalMs(String name, long ms) {
        ScheduledUpdate u = get(name);
        if (u != null) u.setIntervalMs(ms);
    }

    /** @return the task registered under {@code name}, or {@code null} */
    public ScheduledUpdate get(String name) {
        for (int i = 0; i < updates.size(); i++) {
            ScheduledUpdate update = updates.get(i);
            if (update.name.equals(name)) return update;
        }
        return null;
    }

    /** Makes every task due on the next cycle. */
    public void resetAllTimings() {
        for (int i = 0; i < updates.size(); i++) {
            updates.get(i).nextUpdateMs = 0;
        }
    }

    /** Makes every task of {@code type} due on the next cycle. */
    public void resetAllTiming(T type) {
        for (int i = 0; i < updates.size(); i++) {
            ScheduledUpdate update = updates.get(i);
            if (update.type == type) update.nextUpdateMs = 0;
        }
    }

    public void resetTiming(String name) {
        ScheduledUpdate u = get(name);
        if (u != null) u.nextUpdateMs = 0;
    }

    public int getCount() {
        return updates.size();
    }
}
