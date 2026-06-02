package com.teamundefined.defined.generic;


import com.teamundefined.defined.Action;

/**
 * DebounceAction
 *
 * Prevents rapid re-triggering by enforcing a cooldown window AFTER the inner action ends.
 *
 * IMPORTANT with your framework:
 * - Actions are typically "fire, run to terminal, then reset to fire again".
 * - Therefore the debounce timestamp MUST survive reset(), otherwise debounce does nothing.
 *
 * Modes:
 *  - WAIT: during cooldown, the wrapper stays active (RUNNING) and waits it out (no inner ticking).
 *  - SKIP: during cooldown, the wrapper immediately COMPLETEs without running the inner action.
 *
 * Example:
 *   Action safeToggle = DebounceAction.ms("intake_toggle_db", intakeAction, 300)
 *       .withMode(DebounceAction.Mode.SKIP);
 */
public class DebounceAction extends Action {

    public enum Mode {
        WAIT, // wait out cooldown (non-blocking)
        SKIP  // ignore this trigger during cooldown (complete immediately)
    }

    private final Action inner;
    private final long cooldownMs;

    private Mode mode = Mode.WAIT;

    // Timestamp of the last time the INNER action ended (terminal) - must survive reset()
    private long lastTerminalAtMs = -1;

    private DebounceAction(String name, Action inner, long cooldownMs) {
        super(name, now -> {});
        this.inner = inner;
        this.cooldownMs = Math.max(0, cooldownMs);

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: wraps an action with a cooldown in milliseconds. */
    public static DebounceAction ms(String name, Action inner, long cooldownMs) {
        return new DebounceAction(name, inner, cooldownMs);
    }

    /** Select debounce behavior. */
    public DebounceAction withMode(Mode mode) {
        if (!ensureMutable("withMode")) return this;
        this.mode = (mode != null) ? mode : Mode.WAIT;
        return this;
    }

    /** Clears stored debounce history (useful if you explicitly want to allow immediate retrigger). */
    public DebounceAction clearDebounce() {
        if (!ensureMutable("clearDebounce")) return this;
        this.lastTerminalAtMs = -1;
        return this;
    }

    /**
     * Reset should NOT clear lastTerminalAtMs, otherwise debounce is pointless.
     * We only reset the wrapper/inner state so it can run again if allowed.
     */
    @Override
    public Action reset() {
        super.reset();
        this.errorMessage = null;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because DebounceAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("DebounceAction inner is null Action=[" + name + "]");
            return;
        }

        // Are we currently within the cooldown window?
        boolean inCooldown = lastTerminalAtMs >= 0 && (nowMillis - lastTerminalAtMs) < cooldownMs;

        if (inCooldown) {
            long remaining = Math.max(0, cooldownMs - (nowMillis - lastTerminalAtMs));

            if (mode == Mode.SKIP) {
                // Ignore this trigger attempt completely.
                // We COMPLETE immediately (so callers can treat it as "did nothing successfully").
                this.errorMessage = "Debounced (skipped), remaining " + remaining + "ms Action=[" + name + "]";
                endAction(ActionState.COMPLETE);
                return;
            }

            // Mode.WAIT: wait out cooldown without ticking inner.
            this.errorMessage = "Debounce cooldown " + remaining + "ms Action=[" + name + "]";
            return;
        }

        // Cooldown is over: clear status message
        if (this.errorMessage != null && this.errorMessage.contains("Debounce")) {
            this.errorMessage = null;
        }

        // Tick inner normally
        ActionState s = inner.update(nowMillis);

        // If inner is still running, we're still running.
        if (!inner.inTerminalState()) return;
        
        // Record debounce timestamp (must survive reset)
        lastTerminalAtMs = nowMillis;

        // Mirror inner terminal result into this wrapper
        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (s == ActionState.ERROR) {
            endActionWithError("DebounceAction inner failed: " + inner.getErrorMessage());
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("DebounceAction inner timed out");
            return;
        }
        if (s == ActionState.CANCELED) {
            endActionWithCancel("DebounceAction inner canceled");
        }
    }
}