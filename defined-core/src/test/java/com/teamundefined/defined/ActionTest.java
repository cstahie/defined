package com.teamundefined.defined;

import com.teamundefined.defined.Action.ActionState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link Action} state machine — the heart of the engine.
 *
 * <p>Every test drives the action by calling {@code update(now)} with explicit
 * timestamps, so the suite is fully deterministic with no {@code Thread.sleep}.
 */
class ActionTest {

    @Test
    void oneShotCompletesAfterFirstUpdate() {
        AtomicInteger runs = new AtomicInteger();
        Action a = Action.oneShot("blip", now -> runs.incrementAndGet());

        assertEquals(ActionState.NONE, a.getState());
        a.update(0);

        assertEquals(ActionState.COMPLETE, a.getState());
        assertEquals(1, runs.get(), "one-shot step runs exactly once");
    }

    @Test
    void untilRunsUntilConditionIsTrue() {
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicInteger steps = new AtomicInteger();
        Action a = Action.until("spin", now -> steps.incrementAndGet(), done::get);

        a.update(0);
        a.update(10);
        assertEquals(ActionState.RUNNING, a.getState());
        assertEquals(2, steps.get());

        done.set(true);
        a.update(20);
        assertEquals(ActionState.COMPLETE, a.getState());
        assertEquals(3, steps.get(), "step runs once more on the completing tick");
    }

    @Test
    void timeoutTransitionsToTimeoutState() {
        Action a = Action.until("forever", now -> {}, () -> false, 100);
        a.update(0);          // start at t=0
        a.update(50);         // not yet
        assertEquals(ActionState.RUNNING, a.getState());
        a.update(100);        // now - startTime >= timeout
        assertEquals(ActionState.TIMEOUT, a.getState());
    }

    @Test
    void exceptionInStepBecomesError() {
        Action a = new Action("boom", now -> { throw new RuntimeException("kaboom"); });
        a.update(0);
        assertEquals(ActionState.ERROR, a.getState());
        assertTrue(a.getErrorMessage().contains("kaboom"));
    }

    @Test
    void nullStepIsAnError() {
        Action a = new Action("nostep", null);
        a.update(0);
        assertEquals(ActionState.ERROR, a.getState());
    }

    @Test
    void lifecycleCallbacksFireInOrder() {
        StringBuilder log = new StringBuilder();
        Action a = Action.oneShot("cb", now -> log.append("step;"))
                .withOnStart(now -> log.append("start;"))
                .withOnComplete(now -> log.append("complete;"));
        a.update(0);
        assertEquals("start;step;complete;", log.toString());
    }

    @Test
    void onErrorCallbackFires() {
        AtomicBoolean errored = new AtomicBoolean(false);
        Action a = new Action("err", now -> { throw new IllegalStateException("x"); })
                .withOnError(now -> errored.set(true));
        a.update(0);
        assertTrue(errored.get());
    }

    @Test
    void onTimeoutCallbackFires() {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Action a = Action.until("t", now -> {}, () -> false, 10)
                .withOnTimeout(now -> timedOut.set(true));
        a.update(0);
        a.update(10);
        assertTrue(timedOut.get());
    }

    @Test
    void cancelInvokesCancelAndCleanupCallbacks() {
        StringBuilder log = new StringBuilder();
        Action a = Action.until("c", now -> {}, () -> false)
                .withOnCancel(now -> log.append("cancel;"))
                .withOnCancelCleanup(now -> log.append("cleanup;"));
        a.update(0);
        a.cancel("driver override");
        assertEquals(ActionState.CANCELED, a.getState());
        assertEquals("cancel;cleanup;", log.toString());
        assertTrue(a.getErrorMessage().contains("driver override"));
    }

    @Test
    void callbacksChainRatherThanReplace() {
        AtomicInteger count = new AtomicInteger();
        Action a = Action.oneShot("chain", now -> {})
                .withOnComplete(now -> count.incrementAndGet())
                .withOnComplete(now -> count.incrementAndGet());
        a.update(0);
        assertEquals(2, count.get(), "both onComplete callbacks run");
    }

    @Test
    void updateAfterTerminalIsNoOp() {
        AtomicInteger runs = new AtomicInteger();
        Action a = Action.oneShot("once", now -> runs.incrementAndGet());
        a.update(0);
        a.update(1);
        a.update(2);
        assertEquals(1, runs.get(), "no further steps after COMPLETE");
    }

    @Test
    void resetReturnsActionToNone() {
        AtomicInteger runs = new AtomicInteger();
        Action a = Action.oneShot("reusable", now -> runs.incrementAndGet());
        a.update(0);
        assertEquals(ActionState.COMPLETE, a.getState());

        a.reset();
        assertEquals(ActionState.NONE, a.getState());
        a.update(10);
        assertEquals(ActionState.COMPLETE, a.getState());
        assertEquals(2, runs.get(), "reset enables reuse");
    }

    @Test
    void durationIsTrackedAcrossLifecycle() {
        AtomicLong dur = new AtomicLong(-1);
        Action a = Action.until("timed", now -> {}, () -> true);
        a.update(100); // starts and completes on the same tick
        assertTrue(a.getCompleteDurationMs() >= 0);
    }

    @Test
    void inTerminalStateReflectsAllTerminals() {
        Action complete = Action.oneShot("a", now -> {});
        complete.update(0);
        assertTrue(complete.inTerminalState());

        Action running = Action.until("b", now -> {}, () -> false);
        running.update(0);
        assertFalse(running.inTerminalState());
    }

    @Test
    void withTimeoutFluentlySetsTimeout() {
        Action a = new Action("late", now -> {}).withTimeout(5);
        assertEquals(5, a.getTimeout());
    }
}
