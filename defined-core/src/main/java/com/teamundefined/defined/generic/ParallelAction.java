package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Executes multiple actions in parallel.
 * Can be configured to complete when ALL actions complete or when ANY action completes.
 */
public class ParallelAction extends Action {
    public enum CompletionMode {
        ALL,    // Complete when all actions are done
        ANY,    // Complete when any action is done (race)
        ALL_NO_FAIL  // Complete when all succeed, fail if any fails
    }

    private final List<Action> actions;
    private final CompletionMode mode;
    private final List<ActionState> lastStates;

    public ParallelAction(String name, CompletionMode mode, Action... actions) {
        this(name, mode, Arrays.asList(actions));
    }

    public ParallelAction(String name, CompletionMode mode, List<Action> actions) {
        super(name, (now) -> {});
        this.actions = new ArrayList<>(actions);
        this.mode = mode;

        // Propagate required slots from all actions running in parallel
        for (Action action : this.actions) {
            if (action != null) {
                this.requiredSlots.addAll(action.requiredSlots());
            }
        }

        this.lastStates = new ArrayList<>(this.actions.size());
        for (int i = 0; i < this.actions.size(); i++) lastStates.add(ActionState.NONE);

        this.step = this::runStep;

        // parent ends itself from step; completion is simply "am I terminal?"
        this.isComplete = this::inTerminalState;
    }

    public ParallelAction add(Action action){
        this.actions.add(action);
        this.lastStates.add(ActionState.NONE);
        return this;
    }

    private void runStep(long nowMillis) {
        if (this.actions.isEmpty()) {
            endAction(ActionState.COMPLETE);
            return;
        }

        // 1) Tick all non-terminal children
        for (int i = 0; i < this.actions.size(); i++) {
            Action a = this.actions.get(i);
            if (!a.inTerminalState()) {
                a.update(nowMillis);
            }
            // keep snapshot accurate every tick
            lastStates.set(i, a.getState());
        }

        // 2) Aggregate child states (works even if a child was already terminal)
        Action firstError = null;
        Action firstTimeout = null;
        Action firstComplete = null;
        Action firstCanceled = null;

        boolean allTerminal = true;

        for (Action a : this.actions) {
            if (!a.inTerminalState()) allTerminal = false;

            if (firstError == null && a.getState() == ActionState.ERROR) firstError = a;
            if (firstTimeout == null && a.getState() == ActionState.TIMEOUT) firstTimeout = a;
            if (firstComplete == null && a.getState() == ActionState.COMPLETE) firstComplete = a;
            if (firstCanceled == null && a.getState() == ActionState.CANCELED) firstCanceled = a;
        }

        // 3) Mode behavior
        if (mode == CompletionMode.ALL_NO_FAIL) {
            // fail fast on any failure
            if (firstError != null) {
                endActionWithError("Parallel failed due to " + firstError.name + ": " + firstError.getErrorMessage());
                return;
            }
            if (firstTimeout != null) {
                endActionWithTimeout("Parallel timed out due to " + firstTimeout.name);
                return;
            }
            if (firstCanceled != null) {
                endActionWithCancel("Parallel canceled due to " + firstCanceled.name + ": " + firstCanceled.getErrorMessage());
                return;
            }

            // succeed only when all COMPLETE
            if (this.actions.stream().allMatch(a -> a.getState() == ActionState.COMPLETE)) {
                endAction(ActionState.COMPLETE);
            }
            return;
        }

        if (mode == CompletionMode.ANY) {
            // winner completes parent
            if (firstComplete != null) {
                // Cancel losers so they don't keep motors/servos running
                for (Action a : this.actions) {
                    if (a != firstComplete && !a.inTerminalState()) {
                        a.cancel("Canceled by Parallel(ANY) winner: " + firstComplete.name);
                    }
                }
                endAction(ActionState.COMPLETE);
                return;
            }

            // If nobody completed and everybody is terminal, end with failure summary
            if (allTerminal) {
                if (firstError != null) {
                    endActionWithError("Parallel(ANY) finished with no winner; error in " + firstError.name +
                            ": " + firstError.getErrorMessage());
                    return;
                }
                if (firstTimeout != null) {
                    endActionWithTimeout("Parallel(ANY) finished with no winner; timeout in " + firstTimeout.name);
                    return;
                }
                // all terminal with no complete/error/timeout means every child was canceled
                endActionWithCancel("Parallel(ANY) finished with no winner; canceled" +
                        (firstCanceled != null ? " in " + firstCanceled.name : ""));
            }
            return;
        }

        // mode == ALL
        if (allTerminal) {
            if (firstError != null) {
                endActionWithError("Parallel(ALL) error in " + firstError.name + ": " + firstError.getErrorMessage());
                return;
            }
            if (firstTimeout != null) {
                endActionWithTimeout("Parallel(ALL) timeout in " + firstTimeout.name);
                return;
            }
            if (firstCanceled != null) {
                // A canceled child must not read as success - the work it owned never finished.
                endActionWithCancel("Parallel(ALL) canceled in " + firstCanceled.name + ": " + firstCanceled.getErrorMessage());
                return;
            }
            endAction(ActionState.COMPLETE);
        }
    }

    public static ParallelAction all(String name, Action... actions) {
        return new ParallelAction(name, CompletionMode.ALL, actions);
    }

    public static ParallelAction any(String name, Action... actions) {
        return new ParallelAction(name, CompletionMode.ANY, actions);
    }

    public static ParallelAction allNoFail(String name, Action... actions) {
        return new ParallelAction(name, CompletionMode.ALL_NO_FAIL, actions);
    }

    @Override
    public Action reset() {
        super.reset();
        for (int i = 0; i < this.actions.size(); i++) {
            this.actions.get(i).reset();
            lastStates.set(i, ActionState.NONE);
        }
        return this;
    }

    public ParallelAction with(Action action) {
        if (!ensureMutable("with")) return this;
        this.actions.add(action);
        this.lastStates.add(ActionState.NONE);
        // Propagate required slots from the new action
        if (action != null) {
            this.requiredSlots.addAll(action.requiredSlots());
        }
        return this;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Parallel[").append(mode).append("]: ");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(actions.get(i).name).append("=").append(lastStates.get(i));
        }
        return sb.toString();
    }

    public int getCompletedCount() {
        int c = 0;
        for (Action a : actions) if (a.getState() == ActionState.COMPLETE) c++;
        return c;
    }
}