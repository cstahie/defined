package com.teamundefined.defined.runner;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import com.teamundefined.defined.Slot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link ActionRunner} slot-arbitration engine: multi-slot groups,
 * monitors, conflict cancellation, pending coalescing, and cleanup.
 *
 * <p>Note how a team declares its own subsystem slots simply by implementing
 * {@link Slot} — the library ships no hard-coded subsystem list.
 */
class ActionRunnerTest {

    /** Example team slot declaration — exactly what a real robot would write. */
    enum Sub implements Slot { DRIVE, TURRET, INTAKE }

    @Test
    void singleSlotActionRunsToCompletion() {
        ActionRunner runner = new ActionRunner();
        AtomicInteger counter = new AtomicInteger();
        Action a = new Action("drive", t -> counter.incrementAndGet()).requires(Sub.DRIVE);

        runner.startGroup(a);
        assertEquals(ActionState.NONE, a.getState());

        runner.update();
        assertEquals(ActionState.COMPLETE, a.getState());
        assertEquals(1, counter.get());
    }

    @Test
    void multiSlotActionRunsOnceNotPerSlot() {
        ActionRunner runner = new ActionRunner();
        AtomicInteger counter = new AtomicInteger();
        Action a = new Action("group", t -> counter.incrementAndGet())
                .requires(Sub.DRIVE, Sub.TURRET, Sub.INTAKE);

        runner.startGroup(a);
        runner.update();
        assertEquals(ActionState.COMPLETE, a.getState());
        assertEquals(1, counter.get(), "shared action ticks once per update, not per slot");
    }

    @Test
    void monitorRunsEveryUpdateUntilDone() {
        ActionRunner runner = new ActionRunner();
        AtomicInteger ticks = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);
        Action monitor = new Action("mon", t -> ticks.incrementAndGet(), stop::get, -1);

        runner.addMonitor(monitor);
        runner.update();
        runner.update();
        runner.update();
        assertEquals(3, ticks.get());
        assertEquals(ActionState.RUNNING, monitor.getState());

        stop.set(true);
        runner.update();
        assertEquals(ActionState.COMPLETE, monitor.getState());
    }

    @Test
    void monitorWithSlotsIsRejected() {
        ActionRunner runner = new ActionRunner();
        Action bad = new Action("bad", t -> {}).requires(Sub.DRIVE);
        assertThrows(IllegalArgumentException.class, () -> runner.addMonitor(bad));
    }

    @Test
    void conflictingActionCancelsRunningOne() {
        ActionRunner runner = new ActionRunner();
        AtomicBoolean firstDone = new AtomicBoolean(false);
        AtomicBoolean firstCanceled = new AtomicBoolean(false);

        Action first = new Action("first", t -> {}, () -> firstDone.get(), -1)
                .requires(Sub.DRIVE)
                .withOnCancel(t -> firstCanceled.set(true));
        Action second = new Action("second", t -> {}).requires(Sub.DRIVE);

        runner.startGroup(first);
        runner.update();
        assertEquals(ActionState.RUNNING, first.getState());

        runner.startGroup(second);
        runner.update();
        assertTrue(firstCanceled.get());
        assertEquals(ActionState.CANCELED, first.getState());

        runner.update();
        assertEquals(ActionState.COMPLETE, second.getState(), "pending action starts once slot frees");
    }

    @Test
    void pendingActionWaitsThenActivates() {
        ActionRunner runner = new ActionRunner();
        AtomicBoolean blockerDone = new AtomicBoolean(false);
        AtomicInteger pendingRuns = new AtomicInteger();

        Action blocker = new Action("block", t -> {}, () -> blockerDone.get(), -1).requires(Sub.DRIVE);
        Action pending = new Action("pending", t -> pendingRuns.incrementAndGet()).requires(Sub.DRIVE);

        runner.startGroup(blocker);
        runner.update();
        runner.startGroup(pending);
        runner.update();
        assertEquals(0, pendingRuns.get(), "pending action does not run while blocked");

        blockerDone.set(true);
        runner.update();
        assertEquals(ActionState.COMPLETE, pending.getState());
        assertEquals(1, pendingRuns.get());
    }

    @Test
    void onlyLastPendingActionWinsCoalescing() {
        ActionRunner runner = new ActionRunner();
        AtomicBoolean blockerDone = new AtomicBoolean(false);
        AtomicInteger a1 = new AtomicInteger(), a2 = new AtomicInteger(), a3 = new AtomicInteger();

        Action blocker = new Action("block", t -> {}, () -> blockerDone.get(), -1).requires(Sub.DRIVE);
        runner.startGroup(blocker);
        runner.update();

        runner.startGroup(new Action("p1", t -> a1.incrementAndGet()).requires(Sub.DRIVE));
        runner.startGroup(new Action("p2", t -> a2.incrementAndGet()).requires(Sub.DRIVE));
        runner.startGroup(new Action("p3", t -> a3.incrementAndGet()).requires(Sub.DRIVE));

        blockerDone.set(true);
        runner.update();
        runner.update();

        assertEquals(0, a1.get());
        assertEquals(0, a2.get());
        assertEquals(1, a3.get(), "newest pending replaces older pending on the same slot");
    }

    @Test
    void independentSlotsRunConcurrently() {
        ActionRunner runner = new ActionRunner();
        AtomicInteger drive = new AtomicInteger(), turret = new AtomicInteger();

        runner.startGroup(new Action("d", t -> drive.incrementAndGet()).requires(Sub.DRIVE));
        runner.startGroup(new Action("t", t -> turret.incrementAndGet()).requires(Sub.TURRET));
        runner.update();

        assertEquals(1, drive.get());
        assertEquals(1, turret.get());
    }

    @Test
    void cancelSlotStopsOnlyThatSlot() {
        ActionRunner runner = new ActionRunner();
        AtomicBoolean driveCanceled = new AtomicBoolean(false);

        Action drive = new Action("d", t -> {}, () -> false, -1)
                .requires(Sub.DRIVE).withOnCancel(t -> driveCanceled.set(true));
        Action turret = new Action("t", t -> {}, () -> false, -1).requires(Sub.TURRET);

        runner.startGroup(drive);
        runner.startGroup(turret);
        runner.update();

        runner.cancelSlot(Sub.DRIVE, "manual");
        assertTrue(driveCanceled.get());
        runner.update();
        assertEquals(ActionState.CANCELED, drive.getState());
        assertEquals(ActionState.RUNNING, turret.getState());
    }

    @Test
    void slotQueryHelpersReportOccupancy() {
        ActionRunner runner = new ActionRunner();
        Action drive = new Action("d", t -> {}, () -> false, -1).requires(Sub.DRIVE);
        runner.startGroup(drive);
        runner.update();

        assertTrue(runner.isSlotInUse(Sub.DRIVE));
        assertFalse(runner.isSlotInUse(Sub.TURRET));
        assertTrue(runner.isSlotOccupied(Sub.DRIVE));
        assertTrue(runner.areSlotsAvailable(Sub.TURRET, Sub.INTAKE));
        assertFalse(runner.areSlotsAvailable(Sub.DRIVE));
    }

    @Test
    void terminalActionFreesSlotForNewWork() {
        ActionRunner runner = new ActionRunner();
        AtomicInteger counter = new AtomicInteger();

        Action erroring = new Action("err", t -> {
            counter.incrementAndGet();
            if (counter.get() == 2) throw new RuntimeException("forced");
        }, () -> false, -1).requires(Sub.DRIVE);

        runner.startGroup(erroring);
        runner.update();
        runner.update();
        assertEquals(ActionState.ERROR, erroring.getState());

        runner.update(); // cleanup
        Action fresh = new Action("fresh", t -> counter.incrementAndGet()).requires(Sub.DRIVE);
        runner.startGroup(fresh);
        runner.update();
        assertEquals(ActionState.COMPLETE, fresh.getState(), "slot reusable after terminal state");
    }

    @Test
    void emergencyCancelClearsEverything() {
        ActionRunner runner = new ActionRunner();
        Action drive = new Action("d", t -> {}, () -> false, -1).requires(Sub.DRIVE);
        Action turret = new Action("t", t -> {}, () -> false, -1).requires(Sub.TURRET);
        runner.startGroup(drive);
        runner.startGroup(turret);
        runner.update();

        runner.emergencyCancelAllActions();
        assertEquals(ActionState.CANCELED, drive.getState());
        assertEquals(ActionState.CANCELED, turret.getState());
        assertFalse(runner.isSlotInUse(Sub.DRIVE));
        assertFalse(runner.isSlotInUse(Sub.TURRET));
    }

    @Test
    void nullInputsAreIgnored() {
        ActionRunner runner = new ActionRunner();
        assertDoesNotThrow(() -> {
            runner.startGroup(null);
            runner.addMonitor(null);
            runner.update();
        });
    }
}
