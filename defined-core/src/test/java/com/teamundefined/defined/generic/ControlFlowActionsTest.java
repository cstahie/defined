package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional examples + tests for the control-flow action types:
 * Sequential, Parallel, If, Switch, Repeat, Wait, WaitUntil, Timeout, Continuous, NoOp.
 *
 * <p>Each test reads as a worked example of the action it covers.
 */
class ControlFlowActionsTest {

    /** Pump an action with explicit timestamps until terminal or a tick budget runs out. */
    private static void run(Action a, long startMs, long stepMs, int maxTicks) {
        long t = startMs;
        for (int i = 0; i < maxTicks && !a.inTerminalState(); i++) {
            a.update(t);
            t += stepMs;
        }
    }

    @Test
    void sequentialRunsChildrenInOrder() {
        StringBuilder order = new StringBuilder();
        SequentialAction seq = new SequentialAction("boot",
                Action.oneShot("a", n -> order.append("A")),
                Action.oneShot("b", n -> order.append("B")),
                Action.oneShot("c", n -> order.append("C")));

        run(seq, 0, 10, 20);
        assertEquals(ActionState.COMPLETE, seq.getState());
        assertEquals("ABC", order.toString());
    }

    @Test
    void sequentialStopsOnFirstFailure() {
        AtomicBoolean thirdRan = new AtomicBoolean(false);
        SequentialAction seq = new SequentialAction("guarded",
                Action.oneShot("ok", n -> {}),
                new Action("boom", n -> { throw new RuntimeException("x"); }),
                Action.oneShot("never", n -> thirdRan.set(true)));

        run(seq, 0, 10, 20);
        assertEquals(ActionState.ERROR, seq.getState());
        assertFalse(thirdRan.get(), "actions after a failure do not run");
    }

    @Test
    void parallelAllCompletesWhenAllDone() {
        AtomicInteger ticksB = new AtomicInteger();
        AtomicBoolean bDone = new AtomicBoolean(false);
        ParallelAction par = ParallelAction.all("spinup",
                Action.oneShot("a", n -> {}),
                Action.until("b", n -> ticksB.incrementAndGet(), bDone::get));

        par.update(0);
        assertEquals(ActionState.RUNNING, par.getState(), "still waiting on b");
        bDone.set(true);
        par.update(10);
        assertEquals(ActionState.COMPLETE, par.getState());
    }

    @Test
    void parallelAnyCompletesOnFirstAndCancelsLosers() {
        AtomicBoolean loserCanceled = new AtomicBoolean(false);
        Action winner = Action.oneShot("winner", n -> {});
        Action loser = Action.until("loser", n -> {}, () -> false)
                .withOnCancel(n -> loserCanceled.set(true));

        ParallelAction race = ParallelAction.any("race", winner, loser);
        race.update(0);
        assertEquals(ActionState.COMPLETE, race.getState());
        assertTrue(loserCanceled.get(), "ANY cancels the losing branch");
    }

    @Test
    void ifThenElseSelectsCorrectBranch() {
        AtomicBoolean thenRan = new AtomicBoolean(false);
        AtomicBoolean elseRan = new AtomicBoolean(false);
        IfAction branch = IfAction.ifThenElse("pick",
                () -> false,
                Action.oneShot("then", n -> thenRan.set(true)),
                Action.oneShot("else", n -> elseRan.set(true)));

        run(branch, 0, 10, 10);
        assertEquals(ActionState.COMPLETE, branch.getState());
        assertFalse(thenRan.get());
        assertTrue(elseRan.get());
    }

    @Test
    void switchSelectsFirstMatchingCase() {
        AtomicInteger selected = new AtomicInteger(-1);
        SwitchAction sw = new SwitchAction("strategy")
                .addCase("low", () -> false, Action.oneShot("a", n -> selected.set(0)))
                .addCase("hit", () -> true, Action.oneShot("b", n -> selected.set(1)))
                .withDefault(Action.oneShot("d", n -> selected.set(9)));

        run(sw, 0, 10, 10);
        assertEquals(ActionState.COMPLETE, sw.getState());
        assertEquals(1, selected.get());
    }

    @Test
    void valueSwitchDispatchesOnValue() {
        AtomicInteger result = new AtomicInteger(-1);
        SwitchAction.ValueSwitch<Integer> sw =
                new SwitchAction.ValueSwitch<Integer>("ballcount", () -> 2)
                        .addCase(1, Action.oneShot("one", n -> result.set(1)))
                        .addCase(2, Action.oneShot("two", n -> result.set(2)))
                        .withDefault(Action.oneShot("def", n -> result.set(0)));

        run(sw, 0, 10, 10);
        assertEquals(ActionState.COMPLETE, sw.getState());
        assertEquals(2, result.get());
    }

    @Test
    void repeatTimesRunsExactlyNTimes() {
        AtomicInteger runs = new AtomicInteger();
        RepeatAction rep = RepeatAction.times("triple",
                Action.oneShot("shot", n -> runs.incrementAndGet()), 3);

        run(rep, 0, 10, 50);
        assertEquals(ActionState.COMPLETE, rep.getState());
        assertEquals(3, runs.get());
    }

    @Test
    void repeatUntilStopsWhenConditionTrue() {
        AtomicInteger runs = new AtomicInteger();
        RepeatAction rep = RepeatAction.until("drain",
                Action.oneShot("shot", n -> runs.incrementAndGet()),
                () -> runs.get() >= 4);

        run(rep, 0, 10, 50);
        assertEquals(ActionState.COMPLETE, rep.getState());
        assertTrue(runs.get() >= 4);
    }

    @Test
    void waitActionCompletesAfterDuration() {
        WaitAction wait = WaitAction.ms("settle", 250);
        wait.update(0);
        wait.update(100);
        assertEquals(ActionState.RUNNING, wait.getState());
        wait.update(260);
        assertEquals(ActionState.COMPLETE, wait.getState());
    }

    @Test
    void waitUntilCompletesWhenConditionMet() {
        AtomicBoolean ready = new AtomicBoolean(false);
        WaitUntilAction wait = WaitUntilAction.until("flywheel", ready::get);
        wait.update(0);
        assertEquals(ActionState.RUNNING, wait.getState());
        ready.set(true);
        wait.update(10);
        assertEquals(ActionState.COMPLETE, wait.getState());
    }

    @Test
    void waitUntilTimesOut() {
        WaitUntilAction wait = WaitUntilAction.until("never", () -> false).withTimeout(100);
        wait.update(0);
        wait.update(150);
        assertEquals(ActionState.TIMEOUT, wait.getState());
    }

    @Test
    void timeoutActionWrapsInnerWithDeadline() {
        Action slow = Action.until("slow", n -> {}, () -> false);
        TimeoutAction timed = new TimeoutAction("bounded", slow, 100);
        timed.update(0);
        timed.update(150);
        assertEquals(ActionState.TIMEOUT, timed.getState());
    }

    @Test
    void continuousRunsUntilStopCondition() {
        AtomicInteger ticks = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);
        Continuous cont = Continuous.until("track", stop::get, n -> ticks.incrementAndGet());

        cont.update(0);
        cont.update(10);
        assertEquals(ActionState.RUNNING, cont.getState());
        stop.set(true);
        cont.update(20);
        assertEquals(ActionState.COMPLETE, cont.getState());
        assertTrue(ticks.get() >= 2);
    }

    @Test
    void noOpCompletesImmediately() {
        NoOpAction noop = NoOpAction.dummy();
        noop.update(0);
        assertEquals(ActionState.COMPLETE, noop.getState());
    }
}
