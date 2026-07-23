package com.teamundefined.defined;

/**
 * Small allocation-free numeric helpers for the robot hot loop.
 *
 * <p>Nothing here allocates or uses {@code String.format}/{@code java.text} — on a Control
 * Hub those show up in a ~100 Hz loop as GC pressure. {@link #appendDouble} in particular
 * exists so telemetry strings can be assembled into a reused {@link StringBuilder}.
 */
public final class Units {

    private Units() {}

    /**
     * Converts an angle in degrees to encoder ticks.
     *
     * @param angle             angle in degrees
     * @param ticksPerRevolution encoder ticks per motor revolution
     * @param gearRatio         output revolutions per motor revolution
     */
    public static double degToTicks(double angle, double ticksPerRevolution, double gearRatio) {
        return angle * ticksPerRevolution / 360.0 * gearRatio;
    }

    /** Inverse of {@link #degToTicks}: encoder ticks to degrees. */
    public static double ticksToDeg(double position, double ticksPerRevolution, double gearRatio) {
        return position * 360.0 / (ticksPerRevolution * gearRatio);
    }

    /** Normalizes an angle in radians to {@code [-PI, PI]}. */
    public static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * Appends {@code value} to {@code sb} with at most {@code maxDecimals} decimal places
     * (clamped to 1–3), trimming trailing zeros — without allocating.
     *
     * <p>Rounds half-up on the magnitude, so {@code -1.25} at 1 decimal gives {@code -1.3}.
     *
     * <pre>{@code
     * StringBuilder sb = new StringBuilder(64);   // reused across loops
     * sb.setLength(0);
     * sb.append("vel=");
     * Units.appendDouble(sb, velocity, 2);        // "vel=1234.57"
     * }</pre>
     */
    public static void appendDouble(StringBuilder sb, double value, int maxDecimals) {
        // Clamp allowed range (defensive, cheap)
        if (maxDecimals < 1) maxDecimals = 1;
        else if (maxDecimals > 3) maxDecimals = 3;

        if (value < 0) {
            sb.append('-');
            value = -value;
        }

        int scale;
        if (maxDecimals == 1) scale = 10;
        else if (maxDecimals == 2) scale = 100;
        else scale = 1000;

        int scaled = (int) (value * scale + 0.5);

        int intPart = scaled / scale;
        int fracPart = scaled % scale;

        sb.append(intPart);

        if (fracPart == 0) {
            return;
        }

        // Trim trailing zeros (but only within maxDecimals)
        int decimals = maxDecimals;
        while (decimals > 1 && fracPart % 10 == 0) {
            fracPart /= 10;
            decimals--;
        }

        sb.append('.');

        // Leading zeros inside the fractional part
        if (decimals == 3) {
            if (fracPart < 10) sb.append("00");
            else if (fracPart < 100) sb.append('0');
        } else if (decimals == 2) {
            if (fracPart < 10) sb.append('0');
        }

        sb.append(fracPart);
    }
}
