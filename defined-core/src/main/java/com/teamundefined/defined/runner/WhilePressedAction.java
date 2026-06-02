package com.teamundefined.defined.runner;

import com.teamundefined.defined.Log;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Action that runs one action while a button is pressed and another when released.
 * Unlike ToggleStartGroupAction, this is not a toggle - it continuously monitors
 * the button state and runs the appropriate action.
 *
 * This is designed to be used as a monitor action that stays active throughout the OpMode.
 */
public class WhilePressedAction extends Action {
    private final BooleanSupplier button;
    private final ActionRunner runner;
    private final Supplier<Action> whilePressedAction;
    private final Supplier<Action> whenReleasedAction;

    private boolean lastButtonState = false;
    private boolean initialized = false;
    private Action currentAction = null;
    private static final long DEBOUNCE_MS = 50; // Debounce to avoid rapid switching
    private long lastStateChangeTime = 0;

    /**
     * Creates a WhilePressedAction that monitors a button and runs actions based on its state.
     *
     * @param name Unique name for this action
     * @param button Supplier that returns true when button is pressed
     * @param runner ActionRunner to manage actions
     * @param whilePressedAction Action to run while button is pressed
     * @param whenReleasedAction Action to run when button is released
     */
    public WhilePressedAction(String name,
                              BooleanSupplier button,
                              ActionRunner runner,
                              Supplier<Action> whilePressedAction,
                              Supplier<Action> whenReleasedAction) {
        super(name, now -> {});
        this.button = button;
        this.runner = runner;
        this.whilePressedAction = whilePressedAction;
        this.whenReleasedAction = whenReleasedAction;

        this.step = this::runStep;
        this.isComplete = () -> false; // Monitor action - never completes
        // IMPORTANT: Do NOT require slots here - let the child actions handle that

        // Set up cleanup when cancelled
        this.withOnCancel(now -> cleanup());
    }

    @Override
    public Action reset() {
        super.reset();
        lastButtonState = false;
        initialized = false;
        currentAction = null;
        lastStateChangeTime = 0;
        return this;
    }

    private void runStep(long now) {
        boolean currentButtonState = button.getAsBoolean();

        // Initialize on first call
        if (!initialized) {
            initialized = true;
            lastButtonState = currentButtonState;
            lastStateChangeTime = now;

            // Start appropriate action based on initial state
            if (currentButtonState) {
                startWhilePressedAction();
            } else {
                startWhenReleasedAction();
            }
            return;
        }

        // Check for state change with debouncing
        if (currentButtonState != lastButtonState) {
            // Debounce - only change state if enough time has passed
            if (now - lastStateChangeTime < DEBOUNCE_MS) {
                return;
            }

            lastButtonState = currentButtonState;
            lastStateChangeTime = now;

            // Cancel current action if running
            if (currentAction != null) {
                currentAction.cancel("Button state changed");
                currentAction = null;
            }

            // Start new action based on button state
            if (currentButtonState) {
                Log.i("WhilePressedAction", name + " - Button pressed, starting whilePressed action");
                startWhilePressedAction();
            } else {
                Log.i("WhilePressedAction", name + " - Button released, starting whenReleased action");
                startWhenReleasedAction();
            }
        }
    }

    private void startWhilePressedAction() {
        if (whilePressedAction != null) {
            currentAction = whilePressedAction.get();
            if (currentAction != null) {
                runner.startGroup(currentAction);
            }
        }
    }

    private void startWhenReleasedAction() {
        if (whenReleasedAction != null) {
            currentAction = whenReleasedAction.get();
            if (currentAction != null) {
                runner.startGroup(currentAction);
            }
        }
    }

    private void cleanup() {
        // Clean up when this monitor is cancelled
        if (currentAction != null) {
            currentAction.cancel("Parent monitor cancelled");
            currentAction = null;
        }
    }
}