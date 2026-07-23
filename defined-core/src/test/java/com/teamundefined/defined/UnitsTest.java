package com.teamundefined.defined;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitsTest {

    private static String fmt(double v, int maxDecimals) {
        StringBuilder sb = new StringBuilder();
        Units.appendDouble(sb, v, maxDecimals);
        return sb.toString();
    }

    @Test
    @DisplayName("degToTicks and ticksToDeg round-trip")
    void ticksRoundTrip() {
        double ticks = Units.degToTicks(90, 537.7, 2.0);
        assertEquals(90.0, Units.ticksToDeg(ticks, 537.7, 2.0), 1e-9);
    }

    @Test
    @DisplayName("degToTicks scales with revolution count and gear ratio")
    void degToTicksScaling() {
        assertEquals(537.7, Units.degToTicks(360, 537.7, 1.0), 1e-9);
        assertEquals(1075.4, Units.degToTicks(360, 537.7, 2.0), 1e-9);
        assertEquals(0.0, Units.degToTicks(0, 537.7, 1.0), 1e-9);
    }

    @Test
    @DisplayName("appendDouble trims trailing zeros")
    void appendTrimsZeros() {
        assertEquals("1", fmt(1.0, 3));
        assertEquals("1.5", fmt(1.5, 3));
        assertEquals("1.25", fmt(1.25, 3));
    }

    @Test
    @DisplayName("appendDouble pads leading zeros inside the fraction")
    void appendPadsFraction() {
        assertEquals("1.05", fmt(1.05, 2));
        assertEquals("1.005", fmt(1.005, 3));
        assertEquals("1.05", fmt(1.05, 3));
    }

    @Test
    @DisplayName("appendDouble handles negatives and rounds half-up on magnitude")
    void appendNegativesAndRounding() {
        assertEquals("-1.5", fmt(-1.5, 1));
        assertEquals("-1.3", fmt(-1.25, 1));
        assertEquals("1.3", fmt(1.25, 1));
        assertEquals("0", fmt(0.0, 2));
    }

    @Test
    @DisplayName("appendDouble clamps maxDecimals to 1..3")
    void appendClampsPrecision() {
        assertEquals(fmt(1.23456, 1), fmt(1.23456, 0));   // 0 clamps to 1
        assertEquals(fmt(1.23456, 3), fmt(1.23456, 9));   // 9 clamps to 3
    }

    @Test
    @DisplayName("appendDouble appends to existing content without allocating a new builder")
    void appendsInPlace() {
        StringBuilder sb = new StringBuilder("vel=");
        Units.appendDouble(sb, 1234.567, 2);
        assertEquals("vel=1234.57", sb.toString());
    }

    @Test
    @DisplayName("normalizeAngle wraps into [-PI, PI]")
    void normalizeAngleWraps() {
        assertEquals(0.0, Units.normalizeAngle(2 * Math.PI), 1e-9);
        assertEquals(Math.PI / 2, Units.normalizeAngle(Math.PI / 2 + 4 * Math.PI), 1e-9);
        assertEquals(-Math.PI / 2, Units.normalizeAngle(-Math.PI / 2 - 2 * Math.PI), 1e-9);
    }
}
