package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executes actions sequentially, one after another.
 * Moves to next action only when current action completes.
 * Fails if any action fails.
 */
public class SequentialAction extends Action {
    private final List<Action> actions;
    private int currentIndex = 0;

    public SequentialAction(String name) {
        this(name, new ArrayList<>());
    }

    public SequentialAction(String name, Action... actions) {
        this(name, Arrays.asList(actions));
    }

    public SequentialAction(String name, List<Action> actions) {
        super(name, now -> {});
        this.actions = new ArrayList<>(actions);

        // Propagate required slots from all actions in the sequence
        for (Action action : this.actions) {
            if (action != null) {
                this.requiredSlots.addAll(action.requiredSlots());
            }
        }

        // Set the step function
        this.step = (nowMillis) -> {
            if (this.actions.isEmpty() || currentIndex >= this.actions.size()) return;

            Action current = this.actions.get(currentIndex);
            ActionState currentState = current.update(nowMillis);

            if (currentState == ActionState.COMPLETE) {
                currentIndex++;
                return; //allow next action to run in next cycle
            }
            if (currentState == ActionState.ERROR) {
                super.endActionWithError("Sequential action failed at step " + currentIndex +
                        " (" + current.name + "): " + current.getErrorMessage());
                return;
            }
            if (currentState == ActionState.TIMEOUT) {
                super.endActionWithTimeout("Sequential action timed out at step " + currentIndex +
                        " (" + current.name + ")");
                return;
            }
            if (currentState == ActionState.CANCELED) {
                // Propagate cancellation. Without this branch a CANCELED child is ticked
                // forever and the sequence never reaches a terminal state (deadlock).
                super.endActionWithCancel("Sequential action canceled at step " + currentIndex +
                        " (" + current.name + "): " + current.getErrorMessage());
                return;
            }
        };

        // Set completion check
        this.isComplete = () -> currentIndex >= this.actions.size();
    }

    @Override
    public Action reset() {
        // NOTE: Actions are single-use objects owned by this sequence; reset() resets children.
        super.reset();
        currentIndex = 0;
        for (Action action : this.actions) {
            action.reset();
        }
        return this;
    }

    /**
     * Add an action to the sequence
     */
    public SequentialAction then(Action action) {
        if (!ensureMutable("then")) return this;
        this.actions.add(action);
        // Propagate required slots from the new action
        if (action != null) {
            this.requiredSlots.addAll(action.requiredSlots());
        }
        return this;
    }

    /**
     * Get progress information
     */
    public String getProgress() {
        if (actions.isEmpty()) {
            return "No actions";
        }

        return "Step " + (currentIndex + 1) + "/" + actions.size() +
               (currentIndex < actions.size() ? " (" + actions.get(currentIndex).name + ")" : " (Complete)");
    }

    /**
     * Get current action being executed
     */
    public Action getCurrentAction() {
        return currentIndex < actions.size() ? actions.get(currentIndex) : null;
    }
}