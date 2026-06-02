package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional examples + tests for advanced control actions:
 * RateLimit, Deadline, Watchdog, CancelOn, Hold, ManualOverride, RaceGroup,
 * RetryUntilConfident, Throttled, Metric, Debounce.
 */
class AdvancedActionsTest {

    private static void run(Action a, long stepMs, int maxTicks) {
        long t = 0;
        for (int i = 0; i < maxTicks && !a.inTerminalState(); i++) {
            a.update(t);
            t += stepMs;
        }
    }

    @Test
    void rateLimitCompletesWhenInnerCompletes() {
        RateLimitAction rl = RateLimitAction.ms("shoot", Action.oneShot("fire", n -> {}), 100);
        run(rl, 10, 30);
        assertEquals(ActionState.COMPLETE, rl.getState());
    }

    @Test
    void rateLimitHzConvertsToInterval() {
        RateLimitAction rl = RateLimitAction.hz("sensor", Action.oneShot("read", n -> {}), 10.0);
        assertEquals(100, rl.getIntervalMs(), "10 Hz == one run per 100 ms");
    }

    @Test
    void deadlineCompletesWhenDeadlineElapses() {
        AtomicInteger heldTicks = new AtomicInteger();
        DeadlineAction d = DeadlineAction.with("transfer",
                WaitAction.ms("window", 50),
                HoldAction.hold("feed", n -> heldTicks.incrementAndGet()));
        run(d, 10, 30);
        assertEquals(ActionState.COMPLETE, d.getState());
        assertTrue(heldTicks.get() > 0, "the held side ran while the deadline counted down");
    }

    @Test
    void watchdogRunsEmergencyWhenTripped() {
        AtomicBoolean stuck = new AtomicBoolean(false);
        AtomicBoolean recovered = new AtomicBoolean(false);
        WatchdogAction w = WatchdogAction.monitor("transfer_wd",
                HoldAction.hold("transfer", n -> {}),
                stuck::get,
                Action.oneShot("unstick", n -> recovered.set(true)));

        w.update(0);
        assertEquals(ActionState.RUNNING, w.getState());
        stuck.set(true);
        run(w, 10, 10);
        assertTrue(recovered.get(), "emergency action runs when watchdog trips");
        assertTrue(w.isTripped());
    }

    @Test
    void cancelOnCancelsInnerWhenConditionTrue() {
        AtomicBoolean full = new AtomicBoolean(false);
        CancelOnAction c = CancelOnAction.cancelIf("intake",
                HoldAction.hold("run_intake", n -> {}),
                full::get);
        c.update(0);
        assertEquals(ActionState.RUNNING, c.getState());
        full.set(true);
        c.update(10);
        assertEquals(ActionState.CANCELED, c.getState());
    }

    @Test
    void holdRunsUntilStopCondition() {
        AtomicBoolean locked = new AtomicBoolean(false);
        AtomicInteger ticks = new AtomicInteger();
        HoldAction h = HoldAction.hold("turret_hold", n -> ticks.incrementAndGet())
                .until(locked::get);
        h.update(0);
        h.update(10);
        assertEquals(ActionState.RUNNING, h.getState());
        locked.set(true);
        h.update(20);
        assertEquals(ActionState.COMPLETE, h.getState());
        assertTrue(ticks.get() >= 2);
    }

    @Test
    void manualOverridePausesInnerWhileOverridden() {
        AtomicBoolean override = new AtomicBoolean(true);
        AtomicInteger autoTicks = new AtomicInteger();
        ManualOverrideAction mo = ManualOverrideAction.when("aim", override::get,
                Action.until("auto_aim", n -> autoTicks.incrementAndGet(), () -> autoTicks.get() >= 2));

        mo.update(0);
        mo.update(10);
        assertEquals(0, autoTicks.get(), "inner does not run while driver overrides");
        override.set(false);
        run(mo, 10, 10);
        assertEquals(ActionState.COMPLETE, mo.getState());
        assertTrue(autoTicks.get() >= 2, "inner resumes when override releases");
    }

    @Test
    void raceGroupCompletesOnFirstFinisher() {
        Action fast = Action.oneShot("fast", n -> {});
        Action slow = Action.until("slow", n -> {}, () -> false);
        RaceGroupAction race = RaceGroupAction.race("detect", fast, slow);
        race.update(0);
        assertEquals(ActionState.COMPLETE, race.getState());
    }

    @Test
    void retryUntilConfidentSucceedsAfterEnoughAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        RetryUntilConfidentAction r = RetryUntilConfidentAction.of("vision_lock",
                Action.oneShot("pulse", n -> attempts.incrementAndGet()),
                () -> attempts.get() >= 3,
                10);
        run(r, 10, 40);
        assertEquals(ActionState.COMPLETE, r.getState());
        assertTrue(attempts.get() >= 3);
    }

    @Test
    void retryUntilConfidentFailsAfterMaxAttempts() {
        RetryUntilConfidentAction r = RetryUntilConfidentAction.of("never",
                Action.oneShot("pulse", n -> {}),
                () -> false,
                3);
        run(r, 10, 40);
        assertEquals(ActionState.ERROR, r.getState());
        assertTrue(r.getAttemptsUsed() <= 3);
    }

    @Test
    void throttledMonitorRunsAtIntervalCadence() {
        AtomicInteger checks = new AtomicInteger();
        Action mon = ThrottledAction.throttledMonitor("expensive", 100, checks::incrementAndGet);

        // The first interval boundary is at t = intervalMs; ticks before that are skipped.
        for (long t = 0; t <= 300; t += 50) mon.update(t);
        // Boundaries hit: t=100, t=200, t=300 -> 3 checks.
        assertEquals(3, checks.get(), "runnable runs at most once per interval");
        assertEquals(ActionState.RUNNING, mon.getState(), "a monitor keeps running");
    }

    @Test
    void metricMeasuresAndCompletes() {
        AtomicBoolean logged = new AtomicBoolean(false);
        MetricAction.LogSink sink = msg -> logged.set(true);
        MetricAction m = MetricAction.measure("shoot_metrics",
                Action.oneShot("shoot", n -> {}),
                sink);
        run(m, 10, 10);
        assertEquals(ActionState.COMPLETE, m.getState());
        assertTrue(m.getTicks() >= 1);
        assertTrue(logged.get(), "metric summary is emitted to the sink");
        assertNotNull(m.summary());
    }

    @Test
    void debounceRunsInnerThenCompletes() {
        AtomicInteger runs = new AtomicInteger();
        DebounceAction d = DebounceAction.ms("adjust",
                Action.oneShot("bump", n -> runs.incrementAndGet()), 100);
        run(d, 10, 10);
        assertEquals(ActionState.COMPLETE, d.getState());
        assertEquals(1, runs.get());
    }
}
