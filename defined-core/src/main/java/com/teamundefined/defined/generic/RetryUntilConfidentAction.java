package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * RetryUntilConfidentAction
 *
 * A structured "retry loop" for noisy signals (vision, sensor fusion, etc).
 *
 * It runs an attempt action repeatedly until:
 *  - confidenceCondition becomes true  -> COMPLETE
 *  - attempts reach maxAttempts        -> ERROR (or TIMEOUT if you prefer; here ERROR)
 *  - inner attempt ERROR/TIMEOUT       -> (optional) either treat as a failed attempt and retry,
 *                                        or fail-fast (configurable)
 *
 * Typical FTC use:
 *   RetryUntilConfidentAction.vision(
 *       "tag_lock",
 *       now -> limelight.runPipeline(),              // attempt step
 *       () -> limelight.getConfidence() > 0.8,       // success condition
 *       5                                           // max attempts
 *   ).withAttemptTimeout(150)                        // per-attempt timeout
 *    .withDelayBetweenAttempts(50)                   // short settle
 *    .failFast(false);                               // don't die on a single bad read
 *
 * Notes:
 * - This does NOT sleep.
 * - Each "attempt" is itself an Action. You can pass a real Action (recommended),
 *   or use the helper vision(name, step, confidence, maxAttempts) which creates one.
 */
public class RetryUntilConfidentAction extends Action {

    private final Action attemptAction;
    private final BooleanSupplier confidenceCondition;

    private int maxAttempts;
    private int attemptsUsed = 0;

    private long delayBetweenAttemptsMs = 0;
    private long nextAttemptAllowedAtMs = -1;

    private boolean failFast = false;          // if true, inner ERROR/TIMEOUT ends us immediately
    private long perAttemptTimeoutMs = -1;     // if >=0, wraps each attempt with TimeoutAction

    private Action currentAttempt;             // either attemptAction or TimeoutAction(attemptAction)

    private RetryUntilConfidentAction(String name,
                                      Action attemptAction,
                                      BooleanSupplier confidenceCondition,
                                      int maxAttempts) {
        super(name, now -> {});
        this.attemptAction = attemptAction;
        this.confidenceCondition = confidenceCondition;
        this.maxAttempts = Math.max(1, maxAttempts);

        // Propagate required slots from attempt action
        if (this.attemptAction != null) {
            this.requiredSlots.addAll(this.attemptAction.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;

        this.currentAttempt = buildAttemptWrapper();
    }

    /**
     * Convenience factory for "vision-like" use cases.
     * You provide a step (runs each tick during an attempt) and a confidence condition.
     *
     * Example:
     *   RetryUntilConfidentAction.vision("tag_lock",
     *       now -> limelight.update(),
     *       () -> limelight.getConfidence() > 0.8,
     *       5
     *   );
     */
    public static RetryUntilConfidentAction vision(String name,
                                                   LongConsumer attemptStep,
                                                   BooleanSupplier confidenceCondition,
                                                   int maxAttempts) {
        Action attempt = Action.oneShot(name + "_attempt", attemptStep)
                // oneShot means: step runs once and completes immediately.
                // For vision, you usually want the attempt to be a tiny "pulse".
                // If you want multi-tick attempts, pass a real Action instead.
                ;
        return new RetryUntilConfidentAction(name, attempt, confidenceCondition, maxAttempts);
    }

    /** Factory when you already have an attempt Action. */
    public static RetryUntilConfidentAction of(String name,
                                               Action attemptAction,
                                               BooleanSupplier confidenceCondition,
                                               int maxAttempts) {
        return new RetryUntilConfidentAction(name, attemptAction, confidenceCondition, maxAttempts);
    }

    /** Optional: delay between attempts (ms). */
    public RetryUntilConfidentAction withDelayBetweenAttempts(long delayMs) {
        if (!ensureMutable("withDelayBetweenAttempts")) return this;
        this.delayBetweenAttemptsMs = Math.max(0, delayMs);
        return this;
    }

    /** Optional: attempt timeout (ms). Each attempt will be wrapped with TimeoutAction. */
    public RetryUntilConfidentAction withAttemptTimeout(long timeoutMs) {
        if (!ensureMutable("withAttemptTimeout")) return this;
        this.perAttemptTimeoutMs = timeoutMs < 0 ? -1 : timeoutMs;
        // rebuild wrapper for next runs
        this.currentAttempt = buildAttemptWrapper();
        return this;
    }

    /** Optional: fail fast on attempt ERROR/TIMEOUT instead of counting it as a failed attempt. */
    public RetryUntilConfidentAction failFast(boolean enabled) {
        if (!ensureMutable("failFast")) return this;
        this.failFast = enabled;
        return this;
    }

    /** For telemetry. */
    public int getAttemptsUsed() {
        return attemptsUsed;
    }

    @Override
    public Action reset() {
        super.reset();
        attemptsUsed = 0;
        nextAttemptAllowedAtMs = -1;

        if (attemptAction != null) attemptAction.reset();
        currentAttempt = buildAttemptWrapper();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (currentAttempt != null && !currentAttempt.inTerminalState()) {
            currentAttempt.cancel("Canceled because RetryUntilConfidentAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (attemptAction == null) {
            endActionWithError("RetryUntilConfidentAction attemptAction is null Action=[" + name + "]");
            return;
        }
        if (confidenceCondition == null) {
            endActionWithError("RetryUntilConfidentAction confidenceCondition is null Action=[" + name + "]");
            return;
        }

        // If already confident, finish immediately (useful if confidence comes from another action running)
        if (isConfident()) {
            endAction(ActionState.COMPLETE);
            return;
        }

        // Gate by delay between attempts
        if (nextAttemptAllowedAtMs >= 0 && nowMillis < nextAttemptAllowedAtMs) {
            return;
        }

        // Ensure currentAttempt exists and is reset when starting a new attempt
        if (currentAttempt == null) {
            currentAttempt = buildAttemptWrapper();
        }

        // Start / tick current attempt
        ActionState s = currentAttempt.update(nowMillis);

        // Check if confidence got achieved during/after the attempt tick
        if (isConfident()) {
            endAction(ActionState.COMPLETE);
            return;
        }

        // If attempt still running, keep ticking
        if (!currentAttempt.inTerminalState()) return;

        // Attempt ended without confidence: decide what to do
        if (s == ActionState.ERROR || s == ActionState.TIMEOUT) {
            if (failFast) {
                if (s == ActionState.ERROR) {
                    endActionWithError("Attempt failed: " + currentAttempt.getErrorMessage());
                } else {
                    endActionWithTimeout("Attempt timed out");
                }
                return;
            }
            // Otherwise: count it as a failed attempt and continue
        } else if (s == ActionState.CANCELED) {
            // If attempt got canceled externally, treat as cancel of whole thing
            endActionWithCancel("Attempt canceled");
            return;
        }

        attemptsUsed++;

        if (attemptsUsed >= maxAttempts) {
            endActionWithError("Confidence not reached after " + attemptsUsed + "/" + maxAttempts + " attempts Action=[" + name + "]");
            return;
        }

        // Prepare next attempt
        attemptAction.reset();
        currentAttempt = buildAttemptWrapper();
        nextAttemptAllowedAtMs = delayBetweenAttemptsMs > 0 ? (nowMillis + delayBetweenAttemptsMs) : -1;
    }

    private boolean isConfident() {
        try {
            return confidenceCondition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("confidenceCondition threw: " + e.toString());
            return false;
        }
    }

    private Action buildAttemptWrapper() {
        if (attemptAction == null) return null;

        // If perAttemptTimeout is set, wrap the attempt action
        if (perAttemptTimeoutMs >= 0) {
            // Reset the attempt action before wrapping so each attempt is fresh
            attemptAction.reset();
            return new TimeoutAction(name + "_attempt_timeout", attemptAction, perAttemptTimeoutMs);
        }

        return attemptAction;
    }
}

/*
Example

Action tagLock =
    RetryUntilConfidentAction.of(
        "tag_lock",
        detectTagAction,
        () -> limelight.getConfidence() > 0.80,
        5
    )
    .withAttemptTimeout(200)
    .withDelayBetweenAttempts(50)
    .failFast(false);


A couple of FTC “gotchas” (so this actually works in practice)
	•	If your detectTag action is a one-shot pulse, it’s perfect for this retry loop.
	•	If your vision needs multi-tick settling (camera exposure / pipeline warmup), make detectTag a real multi-tick action (with isComplete) and set withAttemptTimeout(ms).
 */