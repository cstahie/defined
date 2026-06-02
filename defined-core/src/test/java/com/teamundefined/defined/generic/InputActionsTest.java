package com.teamundefined.defined.generic;

import com.teamundefined.defined.Action;
import com.teamundefined.defined.Action.ActionState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional examples + tests for input-handling actions:
 * EdgeTrigger, Toggle, DoubleTap, Latch, Debounce.
 *
 * <p>A mutable {@link AtomicBoolean} stands in for a gamepad button; the test
 * flips it between {@code update()} calls to simulate presses.
 */
class InputActionsTest {

    @Test
    void edgeTriggerFiresInnerOnRisingEdge() {
        AtomicBoolean button = new AtomicBoolean(false);
        AtomicInteger fired = new AtomicInteger();
        EdgeTriggerAction trig = EdgeTriggerAction.rising("shoot", button::get,
                Action.oneShot("fire", n -> fired.incrementAndGet()));

        trig.update(0);                 // first sample — never triggers
        assertEquals(0, fired.get());
        button.set(true);
        trig.update(10);                // false -> true: rising edge
        assertEquals(1, fired.get());
        assertEquals(ActionState.COMPLETE, trig.getState());
    }

    @Test
    void edgeTriggerIgnoresLevelHold() {
        AtomicBoolean button = new AtomicBoolean(true); // already held at start
        AtomicInteger fired = new AtomicInteger();
        EdgeTriggerAction trig = EdgeTriggerAction.rising("hold", button::get,
                Action.oneShot("fire", n -> fired.incrementAndGet()));

        trig.update(0); // initializes last=true, no trigger
        trig.update(10);
        assertEquals(0, fired.get(), "holding the button is not a rising edge");
    }

    @Test
    void toggleFlipsBetweenOnAndOff() {
        AtomicBoolean button = new AtomicBoolean(false);
        AtomicBoolean motorOn = new AtomicBoolean(false);
        ToggleAction toggle = ToggleAction.onPress("intake", button::get,
                Action.oneShot("on", n -> motorOn.set(true)),
                Action.oneShot("off", n -> motorOn.set(false)));

        toggle.update(0);               // init
        assertFalse(toggle.isOn());

        button.set(true);
        toggle.update(10);              // press 1 -> ON
        assertTrue(toggle.isOn());
        assertTrue(motorOn.get());

        button.set(false);
        toggle.update(20);             // release (no edge)
        button.set(true);
        toggle.update(30);             // press 2 -> OFF
        assertFalse(toggle.isOn());
        assertFalse(motorOn.get());
    }

    @Test
    void doubleTapTriggersOnlyOnSecondTapInWindow() {
        AtomicBoolean button = new AtomicBoolean(false);
        AtomicInteger fired = new AtomicInteger();
        DoubleTapAction dt = DoubleTapAction.ms("endgame", button::get,
                Action.oneShot("park", n -> fired.incrementAndGet()), 300);

        dt.update(0);                   // init
        // first tap
        button.set(true);  dt.update(10);
        button.set(false); dt.update(20);
        assertEquals(0, fired.get(), "one tap is not enough");
        assertTrue(dt.isArmed());
        // second tap within 300ms window
        button.set(true);  dt.update(100);
        assertEquals(1, fired.get(), "double tap within window fires");
    }

    @Test
    void doubleTapDoesNotFireWhenSecondTapIsLate() {
        AtomicBoolean button = new AtomicBoolean(false);
        AtomicInteger fired = new AtomicInteger();
        DoubleTapAction dt = DoubleTapAction.ms("endgame", button::get,
                Action.oneShot("park", n -> fired.incrementAndGet()), 100);

        dt.update(0);
        button.set(true);  dt.update(10);   // first tap (armed at t=10)
        button.set(false); dt.update(20);
        button.set(true);  dt.update(500);  // way past the 100ms window
        assertEquals(0, fired.get(), "late second tap re-arms instead of firing");
    }

    @Test
    void latchCompletesWhenConditionBecomesTrue() {
        AtomicBoolean sensor = new AtomicBoolean(false);
        LatchAction latch = LatchAction.once("ball_seen", sensor::get);

        latch.update(0);
        assertEquals(ActionState.RUNNING, latch.getState());
        sensor.set(true);
        latch.update(10);
        assertEquals(ActionState.COMPLETE, latch.getState());
        assertTrue(latch.isLatched());
    }
}
