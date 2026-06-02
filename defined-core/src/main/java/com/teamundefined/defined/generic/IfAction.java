package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * Conditional action execution based on a predicate.
 * Executes either 'then' or 'else' action based on condition evaluation.
 */
public class IfAction extends Action {
    private final BooleanSupplier condition;
    private final Action thenAction;
    private final Action elseAction;
    private Action selectedAction = null;
    private boolean conditionEvaluated = false;

    public IfAction(String name, BooleanSupplier condition, Action thenAction, Action elseAction) {
        super(name, now -> {});
        this.condition = condition;
        this.thenAction = thenAction;
        this.elseAction = elseAction;

        // Propagate required slots from all actions
        if (this.thenAction != null) {
            this.requiredSlots.addAll(this.thenAction.requiredSlots());
        }
        if (this.elseAction != null) {
            this.requiredSlots.addAll(this.elseAction.requiredSlots());
        }

        // Set the step function
        this.step = this::runStep;

        // Set completion check
        this.isComplete = () -> this.inTerminalState()
                || (selectedAction == null ? conditionEvaluated : selectedAction.inTerminalState());
    }

    private void runStep(long nowMillis) {
        // Evaluate condition once at the start
        if (!conditionEvaluated) {
            conditionEvaluated = true;
            try {
                boolean result = condition.getAsBoolean();
                selectedAction = result ? thenAction : elseAction;

                // Skip if the selected action is null
                if (selectedAction == null) {
                    // No action to execute, complete immediately
                    return;
                }
            } catch (Exception e) {
                this.endActionWithError("IfAction condition evaluation failed: " + e.getMessage());
                return;
            }
        }

        // Execute selected action
        if (selectedAction != null) {
            ActionState childState = selectedAction.update(nowMillis);

            // Propagate terminal states
            if (childState == ActionState.ERROR) {
                super.endActionWithError("IfAction branch failed: " + selectedAction.getErrorMessage());
            } else if (childState == ActionState.TIMEOUT) {
                super.endActionWithTimeout("IfAction branch timed out: " + selectedAction.name);
            }
        }
    }

    /**
     * Factory method for if-then (no else)
     */
    public static IfAction ifThen(String name, BooleanSupplier condition, Action thenAction) {
        return new IfAction(name, condition, thenAction, null);
    }

    /**
     * Factory method for if-then-else
     */
    public static IfAction ifThenElse(String name, BooleanSupplier condition,
                                      Action thenAction, Action elseAction) {
        return new IfAction(name, condition, thenAction, elseAction);
    }

    /**
     * Get which branch was selected
     */
    public String getSelectedBranch() {
        if (!conditionEvaluated) return "Not evaluated";
        if (selectedAction == thenAction) return "THEN";
        if (selectedAction == elseAction) return "ELSE";
        return "NONE";
    }

    /**
     * Create an inverted version of this condition
     */
    public IfAction invert() {
        if (elseAction == null) {
            // invert IF-THEN to IF-(do nothing)-ELSE(thenAction)
            return new IfAction(name + "_inverted", () -> !condition.getAsBoolean(), null, thenAction);
        }
        return new IfAction(name + "_inverted", () -> !condition.getAsBoolean(), elseAction, thenAction);
    }

    @Override
    public Action reset() {
        super.reset();
        conditionEvaluated = false;
        selectedAction = null;
        if (thenAction != null) thenAction.reset();
        if (elseAction != null) elseAction.reset();
        return this;
    }
}