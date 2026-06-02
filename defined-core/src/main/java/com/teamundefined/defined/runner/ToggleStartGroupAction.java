package com.teamundefined.defined.runner;

import com.teamundefined.defined.Log;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class ToggleStartGroupAction extends Action {
    private final BooleanSupplier button;
    private final ActionRunner runner;
    private final Supplier<Action> onAction;
    private final Supplier<Action> offAction;

    private boolean isOn = false;
    private boolean initialized = false;
    private boolean last = false;
    private long lastToggleTime = 0;
    private static final long DEBOUNCE_MS = 100; // Minimum time between toggles

    private static Action lastExecutedAction = null;

    /**
     * Reset the toggle state - call this when the OpMode starts
     */
    @Override
    public Action reset() {
        super.reset();
        isOn = false;
        initialized = false;
        last = false;
        lastToggleTime = 0;
        lastExecutedAction = null;
        return this;
    }

    public ToggleStartGroupAction(String name,
                                  BooleanSupplier button,
                                  ActionRunner runner,
                                  Supplier<Action> onAction,
                                  Supplier<Action> offAction) {
        super(name, now -> {});
        this.button = button;
        this.runner = runner;
        this.onAction = onAction;
        this.offAction = offAction;

        this.step = this::runStep;
        this.isComplete = () -> false;   // monitor
        // IMPORTANT: do NOT require slots here
    }

    private void runStep(long now) {
        boolean cur = button.getAsBoolean();

        if (!initialized) {
            initialized = true;
            last = cur;
            lastToggleTime = now;
            return;
        }

        boolean rising = !last && cur;
        last = cur;

        if (!rising) return;

        // Debounce: ignore button presses that are too close together
        long timeSinceLastToggle = now - lastToggleTime;
        if (timeSinceLastToggle < DEBOUNCE_MS) {
            Log.d("ToggleAction", name + ": Ignoring button press - debounce (only " + timeSinceLastToggle + "ms since last toggle)");
            return;
        }

        if(lastExecutedAction != null)
            Log.i("ToggleAction", isOn + " " + lastExecutedAction.name + " " + lastExecutedAction.getState());

        lastToggleTime = now;
        isOn = lastExecutedAction != null && lastExecutedAction.getState() == ActionState.CANCELED || !isOn;
        Action actionToStart = isOn ? onAction.get() : offAction.get();

        if (isOn) {
            actionToStart.withOnCancelCleanup(nowX -> {
                // Log for debugging
                Log.i("ToggleAction", name + ": cancel detected, isOn=" + isOn + ", resetting before starting " + actionToStart.name);
                isOn = false;
                lastExecutedAction = null;
            });
        }

        // Log for debugging
        Log.i("ToggleAction", name + ": Button press detected, isOn=" + isOn + ", starting: " + actionToStart.name);

        lastExecutedAction = actionToStart;
        runner.startGroup(actionToStart);
    }
}