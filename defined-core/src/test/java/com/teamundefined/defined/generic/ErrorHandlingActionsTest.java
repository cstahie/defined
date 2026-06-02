package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional examples + tests for error-handling actions:
 * Try, Failsafe, FailFast, Ensure, Require, Finally, Guarded.
 */
class ErrorHandlingActionsTest {

    private static void run(Action a, int maxTicks) {
        long t = 0;
        for (int i = 0; i < maxTicks && !a.inTerminalState(); i++) {
            a.update(t);
            t += 10;
        }
    }

    private static Action failing(String name) {
        return new Action(name, n -> { throw new RuntimeException("simulated failure"); });
    }

    @Test
    void trySwallowsInnerFailureAndCompletes() {
        TryAction t = TryAction.tryOnce("attempt", failing("inner"));
        run(t, 10);
        assertEquals(ActionState.COMPLETE, t.getState(), "Try converts failure into a clean COMPLETE");
        assertFalse(t.lastAttemptSucceeded());
        assertEquals(TryAction.Outcome.FAILED_ERROR, t.getLastOutcome());
    }

    @Test
    void trySuccessReportsSucceeded() {
        TryAction t = TryAction.tryOnce("attempt", Action.oneShot("ok", n -> {}));
        run(t, 10);
        assertEquals(ActionState.COMPLETE, t.getState());
        assertTrue(t.lastAttemptSucceeded());
    }

    @Test
    void tryRunsOnFailFallback() {
        AtomicBoolean fallbackRan = new AtomicBoolean(false);
        TryAction t = TryAction.tryOnce("attempt", failing("inner"))
                .withOnFail(Action.oneShot("recover", n -> fallbackRan.set(true)));
        run(t, 10);
        assertTrue(fallbackRan.get(), "onFail action runs when the inner action fails");
    }

    @Test
    void failsafeRunsFallbackWhenPrimaryFails() {
        AtomicBoolean fallbackRan = new AtomicBoolean(false);
        FailsafeAction fs = FailsafeAction.tryCatch("aim",
                failing("limelight"),
                Action.oneShot("preset", n -> fallbackRan.set(true)));
        run(fs, 10);
        assertEquals(ActionState.COMPLETE, fs.getState());
        assertTrue(fs.isUsingFallback());
        assertTrue(fallbackRan.get());
    }

    @Test
    void failsafeRetriesUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        Action flaky = new Action("flaky", n -> {
            if (attempts.incrementAndGet() < 3) throw new RuntimeException("not yet");
        });
        FailsafeAction fs = FailsafeAction.withRetry("retry", flaky, 5);
        run(fs, 30);
        assertEquals(ActionState.COMPLETE, fs.getState());
        assertTrue(fs.getRetriesUsed() >= 1, "should have retried at least once");
    }

    @Test
    void failFastTripsToErrorWhenConditionTrue() {
        AtomicBoolean lostTarget = new AtomicBoolean(false);
        FailFastAction ff = FailFastAction.ifTrue("vision_guard", lostTarget::get);
        ff.update(0);
        assertEquals(ActionState.RUNNING, ff.getState());
        lostTarget.set(true);
        ff.update(10);
        assertEquals(ActionState.ERROR, ff.getState());
    }

    @Test
    void ensurePassesWhenPostconditionHolds() {
        EnsureAction ok = EnsureAction.after("load",
                Action.oneShot("intake", n -> {}),
                () -> true);
        run(ok, 10);
        assertEquals(ActionState.COMPLETE, ok.getState());
    }

    @Test
    void ensureFailsWhenPostconditionViolated() {
        EnsureAction bad = EnsureAction.after("load",
                Action.oneShot("intake", n -> {}),
                () -> false);
        run(bad, 10);
        assertEquals(ActionState.ERROR, bad.getState());
    }

    @Test
    void requireCompletesWhenTrueErrorsWhenFalse() {
        RequireAction pass = RequireAction.that("has_ball", () -> true);
        run(pass, 5);
        assertEquals(ActionState.COMPLETE, pass.getState());

        RequireAction fail = RequireAction.that("has_ball", () -> false);
        run(fail, 5);
        assertEquals(ActionState.ERROR, fail.getState());
    }

    @Test
    void finallyRunsCleanupEvenWhenPrimaryFails() {
        AtomicBoolean cleaned = new AtomicBoolean(false);
        FinallyAction fin = FinallyAction.wrap("shoot",
                failing("primary"),
                Action.oneShot("close_gates", n -> cleaned.set(true)));
        run(fin, 10);
        assertTrue(cleaned.get(), "cleanup always runs");
        assertEquals(ActionState.ERROR, fin.getState(), "final state mirrors the failed primary");
    }

    @Test
    void finallyMirrorsSuccessAfterCleanup() {
        AtomicBoolean cleaned = new AtomicBoolean(false);
        FinallyAction fin = FinallyAction.wrap("shoot",
                Action.oneShot("primary", n -> {}),
                Action.oneShot("close_gates", n -> cleaned.set(true)));
        run(fin, 10);
        assertTrue(cleaned.get());
        assertEquals(ActionState.COMPLETE, fin.getState());
    }

    @Test
    void guardedRunsInnerWhenGuardTrue() {
        AtomicBoolean ran = new AtomicBoolean(false);
        GuardedAction g = GuardedAction.ifTrue("if_balls", () -> true,
                Action.oneShot("shoot", n -> ran.set(true)));
        run(g, 10);
        assertEquals(ActionState.COMPLETE, g.getState());
        assertTrue(ran.get());
    }

    @Test
    void guardedSkipsInnerWhenGuardFalse() {
        AtomicBoolean ran = new AtomicBoolean(false);
        GuardedAction g = GuardedAction.ifTrue("if_balls", () -> false,
                Action.oneShot("shoot", n -> ran.set(true)));
        run(g, 10);
        assertEquals(ActionState.COMPLETE, g.getState(), "guard-false is a no-op success by default");
        assertFalse(ran.get());
    }

    @Test
    void guardedCanFailWhenGuardFalse() {
        GuardedAction g = GuardedAction.ifTrue("must", () -> false,
                Action.oneShot("shoot", n -> {}))
                .failIfGuardFalse("guard was false");
        run(g, 10);
        assertEquals(ActionState.ERROR, g.getState());
    }
}
