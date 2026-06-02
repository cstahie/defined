package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;

import java.util.function.BooleanSupplier;

/**
 * DoubleTapAction
 *
 * Triggers an inner action ONLY when a button is double-tapped within a window.
 *
 * Use cases (FTC-real):
 * - panic stop
 * - endgame deploy (hard to trigger accidentally)
 * - reset heading / re-localize
 *
 * Example:
 *   Action panic =
 *       DoubleTapAction.ms("panic_stop", () -> gamepad1.back, emergencyStop, 350);
 *
 * Behavior:
 * - First rising edge arms the action and starts a timer window.
 * - Second rising edge within window triggers inner action (runs to completion).
 * - If window expires, it disarms and waits for a new first tap.
 */
public class DoubleTapAction extends Action {

    private final BooleanSupplier button;
    private final Action inner;
    private final long windowMs;

    private final EdgeTriggerAction edge;

    private boolean armed = false;
    private long firstTapAtMs = -1;

    private DoubleTapAction(String name, BooleanSupplier button, Action inner, long windowMs) {
        super(name, now -> {});
        this.button = button;
        this.inner = inner;
        this.windowMs = Math.max(1, windowMs);

        // Propagate required slots from inner action
        if (inner != null) {
            this.requiredSlots.addAll(inner.requiredSlots());
        }

        this.edge = EdgeTriggerAction.rising(name + "_edge", button,
                Action.oneShot(name + "_tap", this::onTap));

        this.step = this::runStep;
        this.isComplete = this::inTerminalState;
    }

    /** Factory: set window in ms. */
    public static DoubleTapAction ms(String name, BooleanSupplier button, Action inner, long windowMs) {
        return new DoubleTapAction(name, button, inner, windowMs);
    }

    /** Factory: reasonable default 300ms. */
    public static DoubleTapAction ms(String name, BooleanSupplier button, Action inner) {
        return new DoubleTapAction(name, button, inner, 300);
    }

    @Override
    public Action reset() {
        super.reset();
        edge.reset();
        armed = false;
        firstTapAtMs = -1;
        if (inner != null) inner.reset();
        return this;
    }

    @Override
    public ActionState cancel(String reason) {
        if (inner != null && !inner.inTerminalState()) {
            inner.cancel("Canceled because DoubleTapAction canceled: " + name);
        }
        armed = false;
        firstTapAtMs = -1;
        return super.cancel(reason);
    }

    public boolean isArmed() {
        return armed;
    }

    public long getWindowMs() {
        return windowMs;
    }

    private void runStep(long nowMillis) {
        // If inner is running, just tick it until terminal and mirror result.
        if (inner != null && !inner.inTerminalState() && armed == false && firstTapAtMs == -2) {
            mirrorInner(nowMillis);
            return;
        }

        // Tick edge detector
        edge.update(nowMillis);

        // An EdgeTriggerAction completes after it fires once. Re-arm it (unless we are
        // currently mirroring the triggered inner, firstTapAtMs == -2) so the *second*
        // tap of the double-tap can be detected. Without this the detector latches after
        // the first tap and the gesture can never complete.
        if (firstTapAtMs != -2 && edge.inTerminalState()) {
            edge.reset();
        }

        // Expire armed window
        if (armed && firstTapAtMs >= 0 && (nowMillis - firstTapAtMs) > windowMs) {
            armed = false;
            firstTapAtMs = -1;
        }
    }

    private void onTap(long nowMillis) {
        if (inner == null) {
            endActionWithError("DoubleTapAction inner is null Action=[" + name + "]");
            return;
        }

        // First tap -> arm
        if (!armed) {
            armed = true;
            firstTapAtMs = nowMillis;
            return;
        }

        // Second tap within window -> trigger
        if ((nowMillis - firstTapAtMs) <= windowMs) {
            armed = false;

            // Sentinel to indicate "inner running" without needing another flag
            // (keeps this action continuous until inner finishes)
            firstTapAtMs = -2;

            inner.reset();
            inner.update(nowMillis);

            // If it finished instantly, mirror right away.
            if (inner.inTerminalState()) {
                mirrorInner(nowMillis);
            }
        } else {
            // Too late: treat this tap as the new first tap (more forgiving UX)
            firstTapAtMs = nowMillis;
            armed = true;
        }
    }

    private void mirrorInner(long nowMillis) {
        ActionState s = inner.update(nowMillis);
        if (!inner.inTerminalState()) return;

        // Clear "inner running" sentinel
        firstTapAtMs = -1;

        if (s == ActionState.COMPLETE) {
            endAction(ActionState.COMPLETE);
            return;
        }
        if (s == ActionState.ERROR) {
            endActionWithError("DoubleTapAction inner failed: " + inner.getErrorMessage());
            return;
        }
        if (s == ActionState.TIMEOUT) {
            endActionWithTimeout("DoubleTapAction inner timed out");
            return;
        }
        if (s == ActionState.CANCELED) {
            endActionWithCancel("DoubleTapAction inner canceled");
        }
    }
}

/*
Examples:
1. Panic stop (double-tap BACK within 350ms):
Action panicStop =
    DoubleTapAction.ms("panic", () -> gamepad1.back,
        Action.oneShot("stop_all", now -> robot.stopAll()),
        350
    );

2. Endgame deploy with low accidental risk:
Action deploy =
    DoubleTapAction.ms("deploy", () -> gamepad2.y, deployEndgame, 300);

 */