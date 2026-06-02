package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

/**
 * TryAction
 *
 * Wraps an inner action and *swallows failures* so the parent automation can continue.
 *
 * Intended use:
 * - Put inside RepeatAction to keep attempting even if the inner errors/timeouts.
 * - Use lastAttemptSucceeded() (or lastOutcome) to decide whether to stop repeating.
 *
 * Default behavior:
 * - inner COMPLETE  -> TryAction COMPLETE (success=true)
 * - inner ERROR     -> TryAction COMPLETE (success=false, records error)
 * - inner TIMEOUT   -> TryAction COMPLETE (success=false, records message)
 * - inner CANCELED  -> TryAction CANCELED (NOT swallowed)
 *
 * Optional:
 * - withOnFail(Action): run a cleanup/backoff action when an attempt fails, then complete.
 * - withSwallowCancel(true): also swallow CANCELED (rare; usually a bad idea).
 *
 * Example:
 *   TryAction tryGrab = TryAction.tryOnce("try_grab", grabAction)
 *       .withOnFail(Action.oneShot("nudge", now -> intake.nudge()));
 *
 *   Action retry =
 *       RepeatAction.until("retry_grab",
 *           tryGrab,
 *           () -> tryGrab.lastAttemptSucceeded()
 *       );
 */
public class TryAction extends Action {

    public enum Outcome {
        NONE,
        SUCCESS,      // inner COMPLETE
        FAILED_ERROR, // inner ERROR
        FAILED_TIMEOUT, // inner TIMEOUT
        CANCELED      // inner CANCELED
    }

    private final Action inner;

    private Action onFail = null;
    private boolean swallowCancel = false;

    private Outcome lastOutcome = Outcome.NONE;
    private String lastFailureMessage = null;

    private int attemptCount = 0;

    // internal: if an attempt failed and we are running onFail
    private boolean runningOnFail = false;

    private TryAction(String name, Action inner) {
        super(name, now -> {});
        this.inner = inner;

        // Propagate required slots from inner action
        if (this.inner != null) {
            this.requiredSlots.addAll(this.inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory */
    public static TryAction tryOnce(String name, Action inner) {
        return new TryAction(name, inner);
    }

    /** Optional: action to run when an attempt fails (ERROR/TIMEOUT, and optionally cancel). */
    public TryAction withOnFail(Action onFail) {
        if (!ensureMutable("withOnFail")) return this;
        this.onFail = onFail;
        // Propagate required slots from onFail action
        if (onFail != null) {
            this.requiredSlots.addAll(onFail.requiredSlots());
        }
        return this;
    }

    /** Optional: swallow cancel too (default false). */
    public TryAction withSwallowCancel(boolean swallowCancel) {
        if (!ensureMutable("withSwallowCancel")) return this;
        this.swallowCancel = swallowCancel;
        return this;
    }

    /** Last attempt outcome (after completion). */
    public Outcome getLastOutcome() {
        return lastOutcome;
    }

    /** True if the last attempt ended with inner COMPLETE. */
    public boolean lastAttemptSucceeded() {
        return lastOutcome == Outcome.SUCCESS;
    }

    /** For telemetry/debug (what went wrong on last failure). */
    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    /** How many attempts (runs) have been executed. */
    public int getAttemptCount() {
        return attemptCount;
    }

    @Override
    public Action reset() {
        super.reset();
        attemptCount = 0;
        lastOutcome = Outcome.NONE;
        lastFailureMessage = null;
        runningOnFail = false;

        if (inner != null) inner.reset();
        if (onFail != null) onFail.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because TryAction canceled: " + name);
        }
        if (onFail != null && !onFail.inTerminalState()) {
            onFail.cancel("Canceled because TryAction canceled: " + name);
        }
        // TryAction cancel is real cancel
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("TryAction inner is null Action=[" + name + "]");
            return;
        }

        // If already running a fail-handler, continue it
        if (runningOnFail) {
            tickOnFail(nowMillis);
            return;
        }

        // Start attempt once per run
        if (state == ActionState.RUNNING && startTime == nowMillis) {
            // no-op
        }

        // Ensure inner is in a runnable state (TryAction is typically reset between attempts by RepeatAction)
        if (inner.getState() == ActionState.NONE) {
            attemptCount++;
        }

        ActionState s = inner.update(nowMillis);

        if (!inner.inTerminalState()) return;

        // Inner ended; map outcome
        if (s == ActionState.COMPLETE) {
            lastOutcome = Outcome.SUCCESS;
            lastFailureMessage = null;
            endAction(ActionState.COMPLETE);
            return;
        }

        if (s == ActionState.ERROR) {
            lastOutcome = Outcome.FAILED_ERROR;
            lastFailureMessage = inner.getErrorMessage();
            handleFailureOrComplete(nowMillis, "Inner error: " + safeMsg(lastFailureMessage));
            return;
        }

        if (s == ActionState.TIMEOUT) {
            lastOutcome = Outcome.FAILED_TIMEOUT;
            lastFailureMessage = "timeout";
            handleFailureOrComplete(nowMillis, "Inner timeout");
            return;
        }

        if (s == ActionState.CANCELED) {
            lastOutcome = Outcome.CANCELED;
            lastFailureMessage = "canceled";

            if (swallowCancel) {
                handleFailureOrComplete(nowMillis, "Inner canceled (swallowed)");
            } else {
                endActionWithCancel("Inner canceled");
            }
        }
    }

    private void handleFailureOrComplete(long nowMillis, String msg) {
        // If there is a fail handler, run it; otherwise just complete successfully (swallow failure)
        if (onFail != null) {
            runningOnFail = true;
            onFail.reset();
            onFail.update(nowMillis);
            if (onFail.inTerminalState()) {
                runningOnFail = false;
                // even if onFail fails, we still do NOT want to kill the automation here;
                // but we DO record it.
                if (onFail.getState() == ActionState.ERROR) {
                    lastFailureMessage = msg + " | onFail ERROR: " + onFail.getErrorMessage();
                } else if (onFail.getState() == ActionState.TIMEOUT) {
                    lastFailureMessage = msg + " | onFail TIMEOUT";
                }
                endAction(ActionState.COMPLETE);
            }
            return;
        }

        // Swallow and mark complete
        lastFailureMessage = msg;
        endAction(ActionState.COMPLETE);
    }

    private void tickOnFail(long nowMillis) {
        if (onFail == null) {
            runningOnFail = false;
            endAction(ActionState.COMPLETE);
            return;
        }

        ActionState s = onFail.update(nowMillis);
        if (!onFail.inTerminalState()) return;

        runningOnFail = false;

        if (s == ActionState.ERROR) {
            // swallow, but record
            lastFailureMessage = safeMsg(lastFailureMessage) + " | onFail ERROR: " + onFail.getErrorMessage();
        } else if (s == ActionState.TIMEOUT) {
            lastFailureMessage = safeMsg(lastFailureMessage) + " | onFail TIMEOUT";
        } else if (s == ActionState.CANCELED) {
            lastFailureMessage = safeMsg(lastFailureMessage) + " | onFail CANCELED";
        }

        endAction(ActionState.COMPLETE);
    }

    private static String safeMsg(String s) {
        return (s == null || s.isBlank()) ? "(none)" : s;
    }
}

/*
Examples:

1. Retry until success flag flips (best when inner success is what matters):
TryAction tryGrab = TryAction.tryOnce("try_grab", grabAction);

Action retry =
    RepeatAction.until("retry_grab",
        tryGrab,
        () -> tryGrab.lastAttemptSucceeded()
    );


2. Retry until sensor says “done” (best when inner COMPLETE isn’t trustworthy):
Action retry =
    RepeatAction.until("retry_grab",
        TryAction.tryOnce("try_grab", grabAction),
        () -> sensor.hasObject()
    );

 */