package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Non-blocking "wait until" action.
 *
 * - Does NOT sleep.
 * - Each update() tick it optionally runs onTick(nowMillis), then checks condition.
 * - Completes when condition returns true.
 * - Optional timeout (default: none). If timeout expires before condition is true, ends TIMEOUT.
 *
 * Example:
 *   Action waitTop = WaitUntilAction.until("arm_at_top", () -> arm.isAtTop())
 *       .withTimeout(1200)
 *       .withOnTick(now -> arm.holdPosition()); // optional
 */
public class WaitUntilAction extends Action {

    private final BooleanSupplier condition;
    private LongConsumer onTick = null;

    private long startedAtMs = -1;

    public WaitUntilAction(String name, BooleanSupplier condition) {
        super(name, now -> {});
        this.condition = condition;

        this.step = this::runStep;
        // Let base Action decide completion via isComplete; we just define the condition.
        this.isComplete = this::isDone;
    }

    /** Factory: wait until condition becomes true (no timeout by default). */
    public static WaitUntilAction until(String name, BooleanSupplier condition) {
        return new WaitUntilAction(name, condition);
    }

    /** Optional: run a callback each update while waiting (e.g., keep motors/servos holding). */
    public WaitUntilAction withOnTick(LongConsumer onTick) {
        if (!ensureMutable("withOnTick")) return this;
        this.onTick = onTick;
        return this;
    }

    /** Optional: set timeout in ms. If reached, action ends TIMEOUT. */
    @Override
    public WaitUntilAction withTimeout(long timeoutMs) {
        super.withTimeout(timeoutMs);
        return this;
    }

    @Override
    public Action reset() {
        super.reset();
        startedAtMs = -1;
        return this;
    }

    private void runStep(long nowMillis) {
        if (startedAtMs < 0) startedAtMs = nowMillis;

        if (condition == null) {
            endActionWithError("WaitUntilAction condition is null Action=[" + name + "]");
            return;
        }

        if (onTick != null) {
            try {
                onTick.accept(nowMillis);
            } catch (Exception e) {
                endActionWithError("WaitUntilAction onTick failed: " + e.toString());
                return;
            }
        }

        // NOTE: we don't check condition here to avoid double-evaluating if isComplete() is called
        // in the same tick by the base Action.update(). isComplete() handles it.
    }

    private boolean isDone() {
        if (inTerminalState()) return true;

        // Timeout is handled by base Action.update() using startTime; but we also gate on startedAtMs
        // to avoid "complete" before first tick.
        if (startedAtMs < 0) return false;

        try {
            return condition.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("WaitUntilAction condition threw: " + e.toString());
            return true; // we've ended ourselves (ERROR); base update will see terminal and stop
        }
    }
}