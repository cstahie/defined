package com.teamundefined.defined.ftc;

import com.teamundefined.defined.SectionProfiler;

import org.firstinspires.ftc.robotcore.external.Telemetry;

/**
 * Adapts FTC {@link Telemetry} to {@link SectionProfiler.Report}, so a profiler built in the
 * dependency-free core can write straight to the Driver Station:
 *
 * <pre>{@code
 * profiler.displayTelemetry(TelemetryReport.of(telemetry));
 * }</pre>
 */
public final class TelemetryReport {

    private TelemetryReport() {}

    /** Wraps {@code telemetry} as a profiler report target. */
    public static SectionProfiler.Report of(Telemetry telemetry) {
        return new SectionProfiler.Report() {
            @Override
            public void line(String text) {
                telemetry.addLine(text);
            }

            @Override
            public void data(String key, String value) {
                telemetry.addData(key, value);
            }
        };
    }
}
