package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

/**
 * Enforces a hard timeout around another Action.
 *
 * - Delegates update() to inner.
 * - If inner doesn't finish in timeoutMs, this action ends TIMEOUT and cancels inner.
 * - If this action is canceled, it cancels inner too.
 */
public class TimeoutAction extends Action {

    private final Action inner;
    private final long timeoutMs;

    public TimeoutAction(String name, Action innerAction, long timeoutMs) {
        super(name, now -> {});
        this.inner = innerAction;
        this.timeoutMs = Math.max(0, timeoutMs);

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    @Override
    public Action reset() {
        super.reset();
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        // Cancel inner first (best effort)
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because wrapper canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("TimeoutAction inner action is null Action=[" + name + "]");
            return;
        }

        // Tick inner
        ActionState childState = inner.update(nowMillis);

        if (childState == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }

        if (childState == ActionState.ERROR) {
            endActionWithError("TimeoutAction inner failed: " + inner.getErrorMessage());
            return;
        }

        if (childState == ActionState.TIMEOUT) {
            // inner timed out before wrapper; propagate (and keep it consistent)
            endActionWithTimeout("TimeoutAction inner timed out: " + inner.name);
            return;
        }

        if (childState == ActionState.CANCELED) {
            // if inner got canceled externally, reflect that
            endActionWithCancel("TimeoutAction inner canceled: " + inner.name);
            return;
        }

        // Enforce wrapper timeout
        if (startTime >= 0 && nowMillis - startTime >= timeoutMs) {
            // cancel inner first so hardware doesn't keep running
            if (!inner.inTerminalState()) {
                inner.cancel("Timeout by wrapper: " + name + " after " + timeoutMs + "ms");
            }
            endActionWithTimeout("TimeoutAction exceeded " + timeoutMs + "ms wrapping [" + inner.name + "]");
        }
    }
}