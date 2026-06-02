package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Switch-case style action selection.
 *
 * - Evaluates cases once (first tick), in insertion order.
 * - Selects the first case whose condition returns true; otherwise uses default.
 * - Executes the selected action until it becomes terminal.
 * - Propagates ERROR/TIMEOUT from child into parent (with context message).
 *
 * Notes:
 * - Actions are assumed to be single-use objects owned by this SwitchAction (reset() resets children).
 * - Mutations while running are blocked via ensureMutable().
 */
public class SwitchAction extends Action {

    private static final class CaseEntry {
        final String label;
        final BooleanSupplier condition;
        final Action action;

        CaseEntry(String label, BooleanSupplier condition, Action action) {
            this.label = label;
            this.condition = condition;
            this.action = action;
        }
    }

    private final List<CaseEntry> cases = new ArrayList<>();
    private Action defaultAction = null;

    private Action selectedAction = null;
    private String selectedCase = null;
    private boolean evaluated = false;

    public SwitchAction(String name) {
        super(name, now -> {});

        this.step = this::runStep;

        this.isComplete = () -> this.inTerminalState()
                || (selectedAction == null ? evaluated : selectedAction.inTerminalState());
    }

    public SwitchAction addCase(BooleanSupplier condition, Action action) {
        return addCase(null, condition, action);
    }

    public SwitchAction addCase(String label, BooleanSupplier condition, Action action) {
        if (!ensureMutable("addCase")) return this;

        if (condition == null) {
            endActionWithError("SwitchAction addCase: condition is null Action=[" + name + "]");
            return this;
        }
        if (action == null) {
            endActionWithError("SwitchAction addCase: action is null Action=[" + name + "]");
            return this;
        }

        cases.add(new CaseEntry(label, condition, action));
        // Propagate required slots from the added action
        if (action != null) {
            this.requiredSlots.addAll(action.requiredSlots());
        }
        return this;
    }

    public SwitchAction withDefault(Action action) {
        if (!ensureMutable("withDefault")) return this;
        this.defaultAction = action;
        // Propagate required slots from the default action
        if (action != null) {
            this.requiredSlots.addAll(action.requiredSlots());
        }
        return this;
    }

    public String getSelectedCase() {
        return selectedCase != null ? selectedCase : "None";
    }

    @Override
    public Action reset() {
        super.reset();

        evaluated = false;
        selectedAction = null;
        selectedCase = null;

        for (CaseEntry c : cases) {
            c.action.reset();
        }
        if (defaultAction != null) defaultAction.reset();

        return this;
    }

    public SwitchAction invert() {
        // Invert means: default becomes "then" of inverted, and original first match becomes "else" is not well-defined.
        // For SwitchAction, invert doesn't make semantic sense universally, so we don't provide it.
        // Use IfAction for invertible binary branching.
        return this; // intentionally no-op; remove if you prefer throwing in dev
    }

    private void runStep(long nowMillis) {
        if (!evaluated) {
            evaluated = true;
            selectedAction = selectAction();
            if (selectedAction == null) {
                // nothing to do; isComplete will return true now
                return;
            }
        }

        ActionState childState = selectedAction.update(nowMillis);

        if (childState == ActionState.ERROR) {
            endActionWithError("SwitchAction case '" + getSelectedCase() + "' failed: " + selectedAction.getErrorMessage());
            return;
        }

        if (childState == ActionState.TIMEOUT) {
            endActionWithTimeout("SwitchAction case '" + getSelectedCase() + "' timed out");
        }
    }

    private Action selectAction() {
        for (int i = 0; i < cases.size(); i++) {
            CaseEntry c = cases.get(i);

            boolean match;
            try {
                match = c.condition.getAsBoolean();
            } catch (Exception e) {
                endActionWithError("SwitchAction failed evaluating case " + i + ": " + e.toString());
                return null;
            }

            if (match) {
                selectedCase = (c.label != null && !c.label.isBlank())
                        ? c.label
                        : (c.action.name != null ? c.action.name : "Case " + i);
                return c.action;
            }
        }

        if (defaultAction != null) {
            selectedCase = "DEFAULT";
            return defaultAction;
        }

        selectedCase = "NONE";
        return null;
    }

    /**
     * Value-based switch.
     *
     * - Reads a value once at start.
     * - Selects action by key equality; otherwise default.
     * - Executes selected action and propagates ERROR/TIMEOUT into parent.
     */
    public static class ValueSwitch<T> extends Action {

        private final Supplier<T> valueSupplier;
        private final Map<T, Action> cases = new LinkedHashMap<>();
        private Action defaultAction = null;

        private boolean evaluated = false;
        private T selectedValue = null;
        private Action selectedAction = null;

        public ValueSwitch(String name, Supplier<T> valueSupplier) {
            super(name, now -> {});
            this.valueSupplier = valueSupplier;

            this.step = this::runStep;

            this.isComplete = () -> this.inTerminalState()
                    || (selectedAction == null ? evaluated : selectedAction.inTerminalState());
        }

        public ValueSwitch<T> addCase(T value, Action action) {
            if (!ensureMutable("addCase")) return this;

            if (action == null) {
                endActionWithError("ValueSwitch addCase: action is null Action=[" + name + "]");
                return this;
            }

            cases.put(value, action);
            // Propagate required slots from the added action
            if (action != null) {
                this.requiredSlots.addAll(action.requiredSlots());
            }
            return this;
        }

        public ValueSwitch<T> withDefault(Action action) {
            if (!ensureMutable("withDefault")) return this;
            this.defaultAction = action;
            // Propagate required slots from the default action
            if (action != null) {
                this.requiredSlots.addAll(action.requiredSlots());
            }
            return this;
        }

        public T getSelectedValue() {
            return selectedValue;
        }

        @Override
        public Action reset() {
            super.reset();

            evaluated = false;
            selectedValue = null;
            selectedAction = null;

            for (Action a : cases.values()) a.reset();
            if (defaultAction != null) defaultAction.reset();

            return this;
        }

        private void runStep(long nowMillis) {
            if (!evaluated) {
                evaluated = true;

                if (valueSupplier == null) {
                    endActionWithError("ValueSwitch valueSupplier is null Action=[" + name + "]");
                    return;
                }

                try {
                    selectedValue = valueSupplier.get();
                } catch (Exception e) {
                    endActionWithError("ValueSwitch failed evaluating value: " + e.toString());
                    return;
                }

                selectedAction = cases.getOrDefault(selectedValue, defaultAction);
                if (selectedAction == null) {
                    // nothing to do; completes immediately
                    return;
                }
            }

            ActionState childState = selectedAction.update(nowMillis);

            if (childState == ActionState.ERROR) {
                endActionWithError("ValueSwitch case '" + selectedValue + "' failed: " + selectedAction.getErrorMessage());
                return;
            }

            if (childState == ActionState.TIMEOUT) {
                endActionWithTimeout("ValueSwitch case '" + selectedValue + "' timed out");
            }
        }
    }
}