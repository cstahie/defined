package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DeadlineAction
 *
 * Runs multiple actions in parallel, but ONE action is the "deadline".
 *
 * - While running: ticks all children each update().
 * - When the deadline action reaches a terminal state:
 *      - cancels all other non-terminal actions (best-effort)
 *      - ends THIS action based on the deadline's terminal state:
 *          COMPLETE  -> COMPLETE
 *          TIMEOUT   -> TIMEOUT
 *          ERROR     -> ERROR
 *          CANCELED  -> CANCELED
 *
 * Example:
 *   Action shootWindow =
 *       DeadlineAction.with("shoot_window",
 *           WaitAction.ms("window", 500),
 *           aimAction,
 *           spinUpAction
 *       );
 *
 * This is FTC gold for "do X for N ms while also holding/aiming/spinning".
 */
public class DeadlineAction extends Action {

    private final Action deadline;
    private final List<Action> others = new ArrayList<>();
    private final List<ActionState> lastStates = new ArrayList<>();

    public DeadlineAction(String name, Action deadline, List<Action> others) {
        super(name, now -> {});
        this.deadline = deadline;
        if (others != null) this.others.addAll(others);

        // Propagate required slots from all actions
        if (this.deadline != null) {
            this.requiredSlots.addAll(this.deadline.requiredSlots());
        }
        for (Action action : this.others) {
            if (action != null) {
                this.requiredSlots.addAll(action.requiredSlots());
            }
        }

        // snapshot for telemetry/debug (deadline first)
        lastStates.add(ActionState.NONE);
        for (int i = 0; i < this.others.size(); i++) lastStates.add(ActionState.NONE);

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    public DeadlineAction(String name, Action deadline, Action... others) {
        this(name, deadline, others == null ? List.of() : Arrays.asList(others));
    }

    /** Factory: first argument is deadline, rest are "alongside" actions. */
    public static DeadlineAction with(String name, Action deadline, Action... others) {
        return new DeadlineAction(name, deadline, others);
    }

    /** Add an alongside action (only before running). */
    public DeadlineAction with(Action action) {
        if (!ensureMutable("with")) return this;
        this.others.add(action);
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
        if (deadline != null) deadline.reset();
        for (Action a : others) if (a != null) a.reset();

        // reset snapshots
        for (int i = 0; i < lastStates.size(); i++) lastStates.set(i, ActionState.NONE);
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        // Cancel everyone
        if (deadline != null && !deadline.inTerminalState()) {
            deadline.cancel("Canceled because DeadlineAction canceled: " + name);
        }
        for (Action a : others) {
            if (a != null && !a.inTerminalState()) {
                a.cancel("Canceled because DeadlineAction canceled: " + name);
            }
        }
        return super.cancel(reason);
    }

    /** Optional telemetry helper. */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Deadline: ");
        sb.append(deadline == null ? "null" : deadline.name).append("=").append(lastStates.get(0));
        for (int i = 0; i < others.size(); i++) {
            sb.append(", ");
            Action a = others.get(i);
            sb.append(a == null ? "null" : a.name).append("=").append(lastStates.get(i + 1));
        }
        return sb.toString();
    }

    private void runStep(long nowMillis) {
        if (deadline == null) {
            endActionWithError("DeadlineAction deadline is null Action=[" + name + "]");
            return;
        }

        // 1) Tick deadline first (so it can end the whole thing ASAP)
        if (!deadline.inTerminalState()) {
            deadline.update(nowMillis);
        }
        lastStates.set(0, deadline.getState());

        // 2) Tick alongside actions
        for (int i = 0; i < others.size(); i++) {
            Action a = others.get(i);

            if (a == null) {
                lastStates.set(i + 1, ActionState.ERROR);
                continue;
            }

            if (!a.inTerminalState()) {
                a.update(nowMillis);
            }
            lastStates.set(i + 1, a.getState());
        }

        // 3) If deadline ended, cancel the rest and end ourselves accordingly
        if (deadline.inTerminalState()) {
            // Cancel all non-terminal others
            for (Action a : others) {
                if (a != null && !a.inTerminalState()) {
                    a.cancel("Canceled by DeadlineAction deadline: " + deadline.name);
                }
            }

            ActionState ds = deadline.getState();

            if (ds == ActionState.COMPLETE) {
                endAction(ActionState.COMPLETE);
                return;
            }
            if (ds == ActionState.TIMEOUT) {
                endActionWithTimeout("DeadlineAction deadline timed out: " + deadline.name);
                return;
            }
            if (ds == ActionState.ERROR) {
                endActionWithError("DeadlineAction deadline failed: " + deadline.getErrorMessage());
                return;
            }
            if (ds == ActionState.CANCELED) {
                endActionWithCancel("DeadlineAction deadline canceled: " + deadline.name);
                return;
            }

            // Defensive fallback
            endAction(ActionState.COMPLETE);
        }
    }
}


/*
Example:

//“Run shooter spin-up + aim hold for 500ms, then stop everything automatically”:
Action shootWindow =
    DeadlineAction.with("shoot_window",
        WaitAction.ms("window", 500),
        HoldAction.position("aim_hold", now -> turret.holdAngle()),
        HoldAction.position("flywheel_hold", now -> shooter.holdRpm())
    );

When window completes, the holds get canceled (because Deadline cancels “others”). That’s the whole point.


 */