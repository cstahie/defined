package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

/**
 * RateLimitAction
 *
 * Limits how often an inner action is allowed to RUN.
 *
 * Modes:
 *  - SKIP:      trigger during cooldown => completes immediately (inner not run)
 *  - WAIT:      trigger during cooldown => stays RUNNING, runs inner when allowed
 *  - QUEUE_ONE: trigger during cooldown => remembers ONE pending trigger, runs once when allowed.
 *               Additional triggers during cooldown are ignored (collapsed).
 *
 * Important:
 * - Cooldown state (nextAllowedAtMs) survives reset(), otherwise rate limiting is pointless.
 * - reset() resets "per-run" flags and inner action.
 */
public class RateLimitAction extends Action {

    public enum Mode {
        SKIP,
        WAIT, // WARNING - THIS CAN SPAM and call all actions when time allows?!
        QUEUE_ONE
    }

    private final Action inner;
    private final long intervalMs;

    private Mode mode = Mode.SKIP;

    // Must persist across reset()
    private long nextAllowedAtMs = -1;

    // Per-run flags
    private boolean innerStartedThisRun = false;

    // QUEUE_ONE state (must persist across reset() too, otherwise spam-collapse breaks)
    private boolean queued = false;

    public static RateLimitAction ms(String name, Action inner, long intervalMs) {
        return new RateLimitAction(name, inner, intervalMs);
    }

    public static RateLimitAction hz(String name, Action inner, double hz) {
        if (hz <= 0) hz = 1.0;
        long interval = (long) Math.floor(1000.0 / hz);
        if (interval < 0) interval = 0;
        return new RateLimitAction(name, inner, interval);
    }

    private RateLimitAction(String name, Action inner, long intervalMs) {
        super(name, now -> {});
        this.inner = inner;
        this.intervalMs = Math.max(0, intervalMs);

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    public RateLimitAction withMode(Mode mode) {
        if (!ensureMutable("withMode")) return this;
        this.mode = (mode != null) ? mode : Mode.SKIP;
        return this;
    }

    /** Clears limiter history (allows immediate run) and clears queued trigger. */
    public RateLimitAction clearLimit() {
        if (!ensureMutable("clearLimit")) return this;
        this.nextAllowedAtMs = -1;
        this.queued = false;
        return this;
    }

    /** Reserve/consume a slot now (advances cooldown) and clears queued trigger. */
    public RateLimitAction consumeNow(long nowMillis) {
        if (!ensureMutable("consumeNow")) return this;
        this.nextAllowedAtMs = nowMillis + intervalMs;
        this.queued = false;
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        innerStartedThisRun = false;
        // DO NOT clear nextAllowedAtMs
        // DO NOT clear queued (QUEUE_ONE needs to survive resets if you use it as a reusable trigger wrapper)
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because RateLimitAction canceled: " + name);
        }
        // If canceled, drop any queued trigger.
        queued = false;
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (inner == null) {
            endActionWithError("RateLimitAction inner is null Action=[" + name + "]");
            return;
        }

        boolean allowed = (nextAllowedAtMs < 0) || (nowMillis >= nextAllowedAtMs);

        // Cooldown active
        if (!allowed) {
            long remaining = Math.max(0, nextAllowedAtMs - nowMillis);

            if (mode == Mode.SKIP) {
                // Drop this trigger attempt
                this.errorMessage = "RateLimited(SKIP) remaining " + remaining + "ms Action=[" + name + "]";
                endAction(ActionState.COMPLETE);
                return;
            }

            if (mode == Mode.WAIT) {
                // Wait it out (non-blocking)
                this.errorMessage = "RateLimited(WAIT) remaining " + remaining + "ms Action=[" + name + "]";
                return;
            }

            // QUEUE_ONE
            queued = true;
            this.errorMessage = "RateLimited(QUEUE_ONE) remaining " + remaining + "ms Action=[" + name + "]";
            return;
        }

        // Allowed now:
        // - WAIT should run inner immediately
        // - QUEUE_ONE should run ONLY if we have a queued trigger, OR if this call itself is the trigger
        //   (i.e., we were invoked while allowed).
        if (mode == Mode.QUEUE_ONE) {
            // If we became allowed but nothing is queued and this is a re-tick, we should *not* run.
            // However, since RateLimitAction.update() being called is itself "a trigger attempt",
            // we interpret "invoked while allowed" as a valid trigger. So:
            //
            // - If we reached here because cooldown ended after being queued, queued==true and we run.
            // - If we reached here and queued==false (no spam happened), we run once as normal.
            //
            // If you want "only run when previously queued", set queuedOnly(true) (not implemented).
        }

        // Clear rate-limit message
        if (this.errorMessage != null && this.errorMessage.startsWith("RateLimited")) {
            this.errorMessage = null;
        }

        // For QUEUE_ONE: if we were queued, consume it now (collapse spam into this single run)
        if (mode == Mode.QUEUE_ONE && queued) {
            queued = false;
        }

        // Start inner fresh once per run
        if (!innerStartedThisRun) {
            innerStartedThisRun = true;
            inner.reset();
        }

        ActionState s = inner.update(nowMillis);

        if (!inner.inTerminalState()) return;

        // Inner ended: advance cooldown
        nextAllowedAtMs = nowMillis + intervalMs;

        // Mirror result
        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (s == ActionState.ERROR) {
            endActionWithError("RateLimitAction inner failed: " + inner.getErrorMessage());
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("RateLimitAction inner timed out");
            return;
        }
        if (s == ActionState.CANCELED) {
            endActionWithCancel("RateLimitAction inner canceled");
        }
    }

    /** Telemetry helper: ms until next allowed (0 if allowed now). */
    public long getRemainingMs(long nowMillis) {
        if (nextAllowedAtMs < 0) return 0;
        return Math.max(0, nextAllowedAtMs - nowMillis);
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    /** QUEUE_ONE helper: whether we currently have a pending trigger queued. */
    public boolean isQueued() {
        return queued;
    }
}


/*
Examples:

Limit a “fire” to max 4/sec
Action fireLimited =
    RateLimitAction.hz("fire_rl", fireOnceAction, 4.0)
        .withMode(RateLimitAction.Mode.SKIP);

Limit a “scan” action but don’t lose it — wait until allowed

Action scanLimited =
    RateLimitAction.ms("scan_rl", scanAction, 200)
        .withMode(RateLimitAction.Mode.QUEUE_ONE);
 */