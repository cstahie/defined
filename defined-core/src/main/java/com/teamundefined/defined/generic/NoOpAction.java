package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

/**
 * NoOpAction
 *
 * A no-operation action that completes immediately.
 * Useful as:
 * - placeholder in If / Switch / Parallel / Race groups
 * - default branch
 * - safe "do nothing" instead of null
 *
 * Always transitions to COMPLETE on first update.
 */
public final class NoOpAction extends Action {

    public static final NoOpAction INSTANCE = new NoOpAction();

    public NoOpAction() {
        super("noop", now -> {});
        this.isComplete = () -> true;
    }

    public static NoOpAction dummy() {
        return new NoOpAction();
    }

    @Override
    public ActionState update(long nowMillis) {
        if (!inTerminalState()) {
            endAction(ActionState.COMPLETE);
        }
        return state;
    }

    @Override
    public Action reset() {
        // No state to reset; remain reusable
        state = ActionState.NONE;
        startTime = UNSET;
        endTime = UNSET;
        errorMessage = null;
        return this;
    }
}