package com.teamundefined.defined.runner;

import com.teamundefined.defined.Log;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Slot;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * ActionRunner with:
 *  - Multi-slot group actions (same Action instance registered under multiple slots)
 *  - Monitors (slot-free)
 *  - Coalescing pending: at most ONE pending action per slot
 *  - Defers starting pending actions until conflicting running actions are TERMINAL
 *    (works great with FinallyAction, because it holds slots until cleanup finishes)
 *
 * Key rule:
 *  - startGroup() never "steals" slots from an action that is not terminal.
 *    It cancels conflicting actions and stages the new action as pending.
 */
public class ActionRunner {
    private final Map<Slot, Action> running = new HashMap<>();
    private final Map<Slot, Action> pending = new HashMap<>(); // single pending per slot
    private final java.util.ArrayList<Action> monitors = new java.util.ArrayList<>();

    public void startGroup(Action action) {
        if (action == null) return;

        // If already installed and not terminal, do nothing (DON'T reset every loop)
        boolean alreadyRunning = true;
        for (Slot s : action.requiredSlots()) {
            if (running.get(s) != action) { // must be the same instance in every slot
                alreadyRunning = false;
                break;
            }
        }
        if (alreadyRunning && !action.inTerminalState()) {
            return;
        }

        // If no required slots, just run it as a monitor-like action? For now, run immediately nowhere.
        // (You can decide to reject/throw here if you want.)
        if (action.requiredSlots() == null || action.requiredSlots().isEmpty()) {
            // No slots -> treat like fire-and-forget running action in MISC? Up to you.
            // Safer: just start it as a monitor so it ticks.
            monitors.add(action.reset());
            return;
        }

        // First, see if we conflict with any non-terminal action
        boolean hasConflict = false;
        for (Slot s : action.requiredSlots()) {
            Action cur = running.get(s);
            if (cur != null && cur != action && !cur.inTerminalState()) {
                hasConflict = true;
                // Request cancellation (FinallyAction will keep running until cleanup completes)
                Log.w("ActionRunner", "Conflict on slot " + s + ": cancelling '" + cur.name +
                      "' (state=" + cur.getState() + ") to start '" + action.name + "'");
                cur.cancel("Replaced by " + action.name);
            }
        }

        if (hasConflict) {
            // Coalesce: store as pending for EACH required slot (overwrite any older pending there)
            // NOTE: do NOT reset() yet; we only reset when it actually starts, to avoid repeated resets.
            for (Slot s : action.requiredSlots()) {
                pending.put(s, action);
            }
            return;
        }

        // No conflicts -> start now
        Log.i("ActionRunner", "Starting action '" + action.name + "' on slots: " + action.requiredSlots());
        installRunning(action.reset());
    }

    /**
     * Advance all monitors and running actions using wall-clock time.
     * Prefer {@link #update(long)} and feed a monotonic clock for deterministic,
     * testable behavior.
     */
    public void update() {
        update(System.currentTimeMillis());
    }

    /**
     * Advance all monitors and running actions to timestamp {@code nowMillis}.
     * Passing the same clock you feed to {@link Action#update(long)} keeps the
     * whole robot loop deterministic.
     */
    public void update(long nowMillis) {
        // 1) run monitors first (they can cancel/override behaviors)
        for (int i = 0; i < monitors.size(); i++) {
            Action m = monitors.get(i);
            if (!m.inTerminalState()) m.update(nowMillis);
        }
        monitors.removeIf(Action::inTerminalState);

        // 2) update each unique running action only once (identity-based)
        java.util.Set<Action> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(running.values());
        for (Action a : unique) {
            //Log.i("AR", "updating " + a);
            a.update(nowMillis);
            //Log.i("AR", "updated " + a);
        }

        // 3) remove slots whose action is terminal
        running.entrySet().removeIf(e -> e.getValue() == null || e.getValue().inTerminalState());

        // 4) try to start pending actions whose required slots are now all free
        flushPending();
    }

    public void cancelSlot(Slot slot, String reason) {
        Action a = running.get(slot);
        if (a != null && !a.inTerminalState()) a.cancel(reason);

        // remove all slots pointing to same action
        running.entrySet().removeIf(e -> e.getValue() == a);

        // also clear pending for that slot (and any slots pointing to same pending action)
        Action p = pending.get(slot);
        if (p != null) {
            pending.entrySet().removeIf(e -> e.getValue() == p);
        } else {
            pending.remove(slot);
        }
    }

    public void addMonitor(Action monitor) {
        if (monitor == null) return;
        if (!monitor.requiredSlots().isEmpty()) {
            throw new IllegalArgumentException("Monitor cannot require slots: " + monitor.name);
        }
        monitors.add(monitor.reset());
    }

    /** Registers the same action under all its required slots. */
    private void installRunning(Action action) {
        for (Slot s : action.requiredSlots()) {
            running.put(s, action);
        }
    }

    /**
     * Start pending actions when their required slots are free.
     *
     * Because multiple slots can point to the same pending Action (group),
     * we must consider unique pending Actions and only start each once.
     *
     * Also: pending is "single per slot", so newer calls overwrite older.
     */
    private void flushPending() {
        if (pending.isEmpty()) return;

        // Build unique set of pending actions
        java.util.Set<Action> uniquePending =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        uniquePending.addAll(pending.values());

        for (Action a : uniquePending) {
            // If action has no required slots, ignore (shouldn't happen here)
            if (a.requiredSlots() == null || a.requiredSlots().isEmpty()) {
                // remove all references to it
                pending.entrySet().removeIf(e -> e.getValue() == a);
                continue;
            }

            // Check if ALL required slots are free (or already mapped to this same action)
            boolean conflict = false;
            for (Slot s : a.requiredSlots()) {
                Action cur = running.get(s);
                if (cur != null && cur != a && !cur.inTerminalState()) {
                    conflict = true;
                    break;
                }
            }

            if (conflict) continue;

            // Slots are free -> start it now
            a.reset();
            installRunning(a);

            // Remove this action from pending (all slots that reference it)
            pending.entrySet().removeIf(e -> e.getValue() == a);
        }

        // Optional: clean any pending entries that somehow point to terminal actions
        pending.entrySet().removeIf(e -> e.getValue() == null || e.getValue().inTerminalState());
    }

    /**
     * Check if a slot is currently in use by a non-terminal action.
     * Use this in monitors to avoid interfering with active actions.
     */
    public boolean isSlotInUse(Slot slot) {
        Action a = running.get(slot);
        return a != null && !a.inTerminalState();
    }

    /**
     * Check if a slot has a pending action waiting to start.
     */
    public boolean isSlotPending(Slot slot) {
        Action a = pending.get(slot);
        return a != null && !a.inTerminalState();
    }

    /**
     * Check if a slot is occupied (either running or pending).
     * Returns true if the slot is NOT available for new actions.
     */
    public boolean isSlotOccupied(Slot slot) {
        return isSlotInUse(slot) || isSlotPending(slot);
    }

    /**
     * Check if multiple slots are ALL free (none in use or pending).
     */
    public boolean areSlotsAvailable(Slot... slots) {
        for (Slot s : slots) {
            if (isSlotOccupied(s)) return false;
        }
        return true;
    }

    public void emergencyCancelAllActions(){
        Log.i("ActionRunner", "Emergency cancel all actions");
        for (Map.Entry<Slot, Action> e : running.entrySet()) {
            Action action = e.getValue();
            action.cancel("Emergency stop");
        }
        for (Map.Entry<Slot, Action> e : pending.entrySet()) {
            Action action = e.getValue();
            action.cancel("Emergency stop");
        }

        running.clear();
        pending.clear();

        // now reset toggle actions from monitors
        for(Action a : monitors) {
            if (! (a instanceof ToggleStartGroupAction)) continue;

            ToggleStartGroupAction ta = (ToggleStartGroupAction)a;
            ta.reset();
            Log.i("ActionRunner", "ES Resetting toggler" + ta.name);
        }
    }
}