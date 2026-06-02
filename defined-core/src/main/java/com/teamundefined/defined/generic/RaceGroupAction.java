package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RaceGroupAction
 *
 * "First wins" parallel group:
 * - Ticks all children each update().
 * - As soon as ANY child reaches COMPLETE, this action COMPLETEs
 *   and cancels all other non-terminal children (the "losers").
 *
 * Failure behavior (configurable):
 * - Default: if nobody completes and all become terminal, we end with a summary:
 *     ERROR if any errored, else TIMEOUT if any timed out, else CANCELED/COMPLETE fallback.
 * - Optional: fail fast on first ERROR/TIMEOUT (like an aggressive race).
 *
 * Examples:
 *   Action driveOrTimeout =
 *       RaceGroupAction.race("drive_or_timeout", driveAction, WaitAction.ms("t", 1200));
 *
 *   Action aimRace =
 *       RaceGroupAction.race("aim_race", alignToTag, WaitUntilAction.until("locked", turret::isLocked))
 *           .failFast(true);
 */
public class RaceGroupAction extends Action {

    private final List<Action> actions = new ArrayList<>();
    private final List<ActionState> lastStates = new ArrayList<>();

    private boolean failFast = false;

    public RaceGroupAction(String name, List<Action> actions) {
        super(name, now -> {});
        if (actions != null) this.actions.addAll(actions);

        // Propagate required slots from all actions
        for (Action action : this.actions) {
            if (action != null) {
                this.requiredSlots.addAll(action.requiredSlots());
            }
        }

        for (int i = 0; i < this.actions.size(); i++) lastStates.add(ActionState.NONE);

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    public RaceGroupAction(String name, Action... actions) {
        this(name, actions == null ? List.of() : Arrays.asList(actions));
    }

    /** Factory */
    public static RaceGroupAction race(String name, Action... actions) {
        return new RaceGroupAction(name, actions);
    }

    /** Optional: if true, any child ERROR/TIMEOUT immediately ends the race and cancels others. */
    public RaceGroupAction failFast(boolean enabled) {
        if (!ensureMutable("failFast")) return this;
        this.failFast = enabled;
        return this;
    }

    /** Optional: add another racer (only before running). */
    public RaceGroupAction with(Action action) {
        if (!ensureMutable("with")) return this;
        this.actions.add(action);
        this.lastStates.add(ActionState.NONE);
        // Propagate required slots from the added action
        if (action != null) {
            this.requiredSlots.addAll(action.requiredSlots());
        }
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        for (int i = 0; i < actions.size(); i++) {
            actions.get(i).reset();
            lastStates.set(i, ActionState.NONE);
        }
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        // Cancel all children first
        for (Action a : actions) {
            if (a != null && !a.inTerminalState()) {
                a.cancel("Canceled because RaceGroupAction canceled: " + name);
            }
        }
        return super.cancel(reason);
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Race: ");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(", ");
            Action a = actions.get(i);
            sb.append(a == null ? "null" : a.name).append("=").append(lastStates.get(i));
        }
        return sb.toString();
    }

    private void runStep(long nowMillis) {
        if (actions.isEmpty()) {
            endAction(ActionState.COMPLETE);
            return;
        }

        Action winner = null;

        Action firstError = null;
        Action firstTimeout = null;
        Action firstCanceled = null;

        boolean allTerminal = true;

        // 1) Tick everyone
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);

            if (a == null) {
                lastStates.set(i, ActionState.ERROR);
                if (firstError == null) firstError = a;
                continue;
            }

            if (!a.inTerminalState()) {
                a.update(nowMillis);
            }

            ActionState s = a.getState();
            lastStates.set(i, s);

            if (!a.inTerminalState()) allTerminal = false;

            if (winner == null && s == ActionState.COMPLETE) winner = a;
            if (firstError == null && s == ActionState.ERROR) firstError = a;
            if (firstTimeout == null && s == ActionState.TIMEOUT) firstTimeout = a;
            if (firstCanceled == null && s == ActionState.CANCELED) firstCanceled = a;
        }

        // 2) If we have a winner, cancel losers and end COMPLETE
        if (winner != null) {
            for (Action a : actions) {
                if (a != null && a != winner && !a.inTerminalState()) {
                    a.cancel("Canceled by RaceGroupAction winner: " + winner.name);
                }
            }
            endAction(ActionState.COMPLETE);
            return;
        }

        // 3) Optional fail-fast (no winner yet)
        if (failFast) {
            if (firstError != null) {
                cancelLosers("RaceGroupAction failFast: error");
                endActionWithError("RaceGroupAction failFast ERROR in " + firstError.name + ": " + firstError.getErrorMessage());
                return;
            }
            if (firstTimeout != null) {
                cancelLosers("RaceGroupAction failFast: timeout");
                endActionWithTimeout("RaceGroupAction failFast TIMEOUT in " + firstTimeout.name);
                return;
            }
        }

        // 4) If everyone is terminal and nobody won, end with summary
        if (allTerminal) {
            if (firstError != null) {
                endActionWithError("RaceGroupAction finished: no winner; ERROR in " + firstError.name +
                        ": " + firstError.getErrorMessage());
                return;
            }
            if (firstTimeout != null) {
                endActionWithTimeout("RaceGroupAction finished: no winner; TIMEOUT in " + firstTimeout.name);
                return;
            }
            if (firstCanceled != null) {
                endActionWithCancel("RaceGroupAction finished: no winner; all canceled/ended");
                return;
            }
            // Edge case: all terminal but not error/timeout/canceled/complete -> treat as complete
            endAction(ActionState.COMPLETE);
        }
    }

    private void cancelLosers(String reason) {
        for (Action a : actions) {
            if (a != null && !a.inTerminalState()) {
                a.cancel(reason);
            }
        }
    }
}