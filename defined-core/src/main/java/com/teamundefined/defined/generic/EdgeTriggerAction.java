package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * EdgeTriggerAction
 *
 * Triggers an inner action ONLY on an edge transition of a boolean signal.
 * This solves the classic FTC problem: "button held triggers 10 times".
 *
 * - Rising edge: false -> true
 * - Falling edge: true -> false
 *
 * Behavior:
 * - While not triggered: waits for edge (RUNNING)
 * - On edge: starts inner action (or one-shot) exactly once
 * - Completes when inner action completes (or immediately if inner is null)
 *
 * Example:
 *   Action fireOnA =
 *       EdgeTriggerAction.rising("a_edge", () -> gamepad1.a, fireOnceAction);
 *
 * Common pattern:
 *   ParallelAction.allNoFail("teleop_actions",
 *       fireOnA,
 *       fireOnB,
 *       ...
 *   );
 */
public class EdgeTriggerAction extends Action {

    public enum Edge {
        RISING,
        FALLING
    }

    private final BooleanSupplier signal;
    private final Edge edge;
    private final Action inner;

    private boolean initialized = false;
    private boolean last = false;

    private boolean triggered = false;

    private EdgeTriggerAction(String name, BooleanSupplier signal, Edge edge, Action inner) {
        super(name, now -> {});
        this.signal = signal;
        this.edge = edge;
        this.inner = inner;

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: triggers on false -> true. */
    public static EdgeTriggerAction rising(String name, BooleanSupplier signal, Action inner) {
        return new EdgeTriggerAction(name, signal, Edge.RISING, inner);
    }

    /** Factory: triggers on true -> false. */
    public static EdgeTriggerAction falling(String name, BooleanSupplier signal, Action inner) {
        return new EdgeTriggerAction(name, signal, Edge.FALLING, inner);
    }

    @Override
    public Action reset() {
        super.reset();
        initialized = false;
        last = false;
        triggered = false;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because EdgeTriggerAction canceled: " + name);
        }
        return super.cancel(reason);
    }

    private void runStep(long nowMillis) {
        if (signal == null) {
            endActionWithError("EdgeTriggerAction signal is null Action=[" + name + "]");
            return;
        }

        boolean cur;
        try {
            cur = signal.getAsBoolean();
        } catch (Exception e) {
            endActionWithError("EdgeTriggerAction signal threw: " + e.toString());
            return;
        }

        if (!initialized) {
            initialized = true;
            last = cur;
            return; // do not trigger on first sample
        }

        boolean isEdge =
                (edge == Edge.RISING  && !last && cur) ||
                        (edge == Edge.FALLING &&  last && !cur);

        last = cur;

        // If not triggered yet, wait for the edge
        if (!triggered) {
            if (!isEdge) return;

            triggered = true;

            if (inner == null) {
                endAction(ActionState.COMPLETE);
                return;
            }

            // Ensure inner starts fresh on the edge
            inner.reset();
        }

        // After trigger: run inner until terminal and mirror result
        ActionState s = inner.update(nowMillis);

        if (!inner.inTerminalState()) return;

        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (s == ActionState.ERROR) {
            endActionWithError("EdgeTriggerAction inner failed: " + inner.getErrorMessage());
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("EdgeTriggerAction inner timed out");
            return;
        }
        if (s == ActionState.CANCELED) {
            endActionWithCancel("EdgeTriggerAction inner canceled");
        }
    }
}

/*
// Examples:

// 1. Fire-once mechanism (classic “button spam” fix)
 Problem: holding A fires intake 10 times
 Solution: rising edge

Action fireIntake =
    EdgeTriggerAction.rising(
        "fire_intake",
        () -> gamepad1.a,
        Action.oneShot("intake_on", now -> intake.spin())
    );
 ✔ fires once per press, no repeats
 ✔ safe to run every loop inside a ParallelAction



// 2. Toggle behavior (ON ↔ OFF) without state bugs
final boolean[] intakeOn = { false };
Action toggleIntake =
    EdgeTriggerAction.rising(
        "toggle_intake",
        () -> gamepad1.a,
        Action.oneShot("toggle", now -> {
            intakeOn[0] = !intakeOn[0];
            intake.setPower(intakeOn[0] ? 1.0 : 0.0);
        })
    );
 ✔ no debounce
 ✔ no “while held” madness
 ✔ no extra flags in TeleOp loop



// 3. Start automation from TeleOp safely
Action startAuto =
    EdgeTriggerAction.rising(
        "start_auto",
        () -> gamepad1.y,
        fullAutomation
    );

// Run it alongside manual drive:
Action teleop =
    ParallelAction.all("teleop",
        driveAction,
        startAuto
    );
  ✔ automation starts once
  ✔ driver can keep holding Y, nothing retriggers


// 4. Press-to-extend arm, hold does nothing
Action extendArmOnce =
    EdgeTriggerAction.rising(
        "extend_arm",
        () -> gamepad2.right_bumper,
        Action.oneShot("arm_extend", now -> arm.extend())
    );

 Perfect for:
	•	pistons
	•	latches
	•	hooks

// 5. Edge-triggered sequence (multi-step)
Action shootSequence =
    new SequentialAction("shoot_seq",
        spinUp,
        aim,
        fire
    );

Action shootOnPress =
    EdgeTriggerAction.rising(
        "shoot_on_press",
        () -> gamepad1.right_trigger > 0.5,
        shootSequence
    );

    ✔ full sequence starts once
    ✔ no partial repeats
    ✔ no race conditions


 */