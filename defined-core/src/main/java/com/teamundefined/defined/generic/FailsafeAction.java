package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Failsafe wrapper for an action:
 * - Runs a primary action.
 * - On failure (ERROR/TIMEOUT), retries primary up to maxRetries (optional) with optional delay.
 * - If still failing, switches to fallback action (optional).
 * - Propagates terminal child state into this action (COMPLETE / TIMEOUT / ERROR).
 *
 * Assumptions:
 * - Action instances are single-use objects owned by this FailsafeAction (reset() resets children).
 * - Never mutates this.state directly; always ends via endAction
 **/
public class FailsafeAction extends Action {

    private final Action primary;
    private Action fallback = null;

    private int maxRetries = 0;
    private int retriesUsed = 0;

    private long retryDelayMs = 0;     // delay between retry attempts
    private long retryReadyAtMs = -1;  // when we are allowed to retry next

    private boolean usingFallback = false;
    private Action current;

    private final List<String> errorLog = new ArrayList<>();

    public FailsafeAction(String name, Action primaryAction) {
        super(name, now -> {});
        if (primaryAction == null) {
            endActionWithError("FailsafeAction primaryAction is null Action=[" + this.name + "]");
            this.primary = Action.oneShot("noop_primary", t -> {});
            this.current = this.primary;
            return;
        }

        this.primary = primaryAction;
        this.current = primaryAction;

        // Propagate required slots from primary action
        if (this.primary != null) {
            this.requiredSlots.addAll(this.primary.requiredSlots());
        }

        this.step = this::runStep;

        // We end ourselves from within step; base can just ask "am I terminal?"
        this.isComplete = this::inTerminalState;
    }

    public static FailsafeAction tryCatch(String name, Action tryAction, Action catchAction) {
        return new FailsafeAction(name, tryAction).withFallback(catchAction);
    }

    public static FailsafeAction withRetry(String name, Action action, int retries) {
        return new FailsafeAction(name, action).withRetries(retries);
    }

    public static FailsafeAction retryOrFallback(String name, Action primary, int retries, Action fallback) {
        return new FailsafeAction(name, primary).withRetries(retries).withFallback(fallback);
    }

    public FailsafeAction withFallback(Action fallbackAction) {
        if (!ensureMutable("withFallback")) return this;
        this.fallback = fallbackAction;
        // Propagate required slots from fallback action
        if (fallbackAction != null) {
            this.requiredSlots.addAll(fallbackAction.requiredSlots());
        }
        return this;
    }

    public FailsafeAction withRetries(int maxRetries) {
        if (!ensureMutable("withRetries")) return this;
        this.maxRetries = Math.max(0, maxRetries);
        return this;
    }

    public FailsafeAction withRetryDelay(long delayMs) {
        if (!ensureMutable("withRetryDelay")) return this;
        this.retryDelayMs = Math.max(0, delayMs);
        return this;
    }

    public int getRetriesUsed() {
        return retriesUsed;
    }

    public boolean isUsingFallback() {
        return usingFallback;
    }

    public List<String> getErrorLog() {
        return Collections.unmodifiableList(errorLog);
    }

    @Override
    public Action reset() {
        super.reset();

        retriesUsed = 0;
        usingFallback = false;
        current = primary;

        retryReadyAtMs = -1;
        errorLog.clear();

        primary.reset();
        if (fallback != null) fallback.reset();

        return this;
    }

    private void runStep(long nowMillis) {
        // If we already ended (e.g., someone called endActionWithError inside), do nothing.
        if (inTerminalState()) return;

        // Retry delay gate
        if (retryReadyAtMs >= 0 && nowMillis < retryReadyAtMs) return;

        // Defensive: current might be null if user configured badly
        if (current == null) {
            endActionWithError("FailsafeAction current action is null Action=[" + name + "]");
            return;
        }

        ActionState childState = current.update(nowMillis);

        if (childState == ActionState.RUNNING || childState == ActionState.NONE) {
            return; // still working
        }

        if (childState == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }

        // Failure path: ERROR or TIMEOUT
        String childErr = current.getErrorMessage();
        if (childErr == null || childErr.isBlank()) {
            childErr = "Child ended with state=" + childState + " name=" + current.name;
        }
        errorLog.add(childErr);

        // If fallback already in use and it failed, we are done.
        if (usingFallback) {
            // Preserve the terminal type if it was TIMEOUT; otherwise ERROR.
            if (childState == ActionState.TIMEOUT) {
                endActionWithTimeout("Failsafe: fallback timed out. Primary: " + safe0() + " Fallback: " + childErr);
            } else {
                endActionWithError("Failsafe: fallback failed. Primary: " + safe0() + " Fallback: " + childErr);
            }
            return;
        }

        // We were running primary and it failed: decide retry / fallback / fail.
        if (retriesUsed < maxRetries) {
            retriesUsed++;
            primary.reset();
            current = primary;
            retryReadyAtMs = retryDelayMs > 0 ? (nowMillis + retryDelayMs) : -1;
            return;
        }

        if (fallback != null) {
            usingFallback = true;
            current = fallback;
            // optional: reset fallback before use (recommended)
            fallback.reset();
            return;
        }

        // No fallback, no retries left: end with the child terminal type (timeout vs error)
        if (childState == ActionState.TIMEOUT) {
            endActionWithTimeout("Failsafe: primary timed out after " + retriesUsed + " retries. Last: " + childErr);
        } else {
            endActionWithError("Failsafe: primary failed after " + retriesUsed + " retries. Last: " + childErr);
        }
    }

    private String safe0() {
        return errorLog.isEmpty() ? "(none)" : errorLog.get(0);
    }

    /**
     * Chain builder: A -> fallback(B) -> fallback(C) ...
     */
    public static class Chain {
        private final List<Action> actions = new ArrayList<>();
        private final String name;

        public Chain(String name) {
            this.name = name;
        }

        public Chain addAction(Action action) {
            actions.add(action);
            return this;
        }

        public Action build() {
            if (actions.isEmpty()) {
                throw new IllegalStateException("Chain must have at least one action");
            }
            if (actions.size() == 1) {
                return actions.get(0);
            }

            Action result = actions.get(actions.size() - 1);
            for (int i = actions.size() - 2; i >= 0; i--) {
                result = new FailsafeAction(name + "_step" + i, actions.get(i)).withFallback(result);
            }
            return result;
        }
    }
}