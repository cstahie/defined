package com.teamundefined.defined;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTest {

    @AfterEach
    void restoreDefaults() {
        Log.useAutoDetect();
        Log.verbosity = 1;
    }

    @Test
    @DisplayName("off Android, auto-detect stays silent rather than throwing")
    void autoDetectSilentOffAndroid() {
        Log.useAutoDetect();
        // No android.util.Log on the test JVM: this must be a no-op, not an exception.
        Log.i("TAG", "hello");
        Log.e("TAG", () -> "lazy");
    }

    @Test
    @DisplayName("an explicit sink receives messages and wins over auto-detect")
    void explicitSinkReceives() {
        List<String> got = new ArrayList<>();
        Log.sink = (level, tag, msg) -> got.add(level + "|" + tag + "|" + msg);

        Log.i("A", "one");
        Log.w("B", "two");

        assertEquals(2, got.size());
        assertEquals(Log.INFO + "|A|one", got.get(0));
        assertEquals(Log.WARN + "|B|two", got.get(1));
    }

    @Test
    @DisplayName("disable() silences everything")
    void disableSilences() {
        List<String> got = new ArrayList<>();
        Log.sink = (level, tag, msg) -> got.add(msg);
        Log.i("A", "before");
        Log.disable();
        Log.i("A", "after");
        assertEquals(1, got.size());
        assertEquals("before", got.get(0));
    }

    @Test
    @DisplayName("verbosity gates the leveled call and never evaluates the supplier")
    void verbosityGatesSupplier() {
        List<String> got = new ArrayList<>();
        Log.sink = (level, tag, msg) -> got.add(msg);
        AtomicInteger evaluations = new AtomicInteger();

        Log.verbosity = 1;
        Log.i("HOT", 20, () -> { evaluations.incrementAndGet(); return "noisy"; });
        assertTrue(got.isEmpty(), "level 20 must be dropped at verbosity 1");
        assertEquals(0, evaluations.get(), "supplier must not run when gated out");

        Log.verbosity = 30;
        Log.i("HOT", 20, () -> { evaluations.incrementAndGet(); return "noisy"; });
        assertEquals(1, got.size());
        assertEquals(1, evaluations.get());
    }

    @Test
    @DisplayName("with no sink installed, suppliers are never evaluated")
    void noSinkSkipsSupplier() {
        Log.disable();
        Log.sink = null;             // force the auto-detect path, which resolves to nothing here
        AtomicInteger evaluations = new AtomicInteger();
        Log.i("TAG", () -> { evaluations.incrementAndGet(); return "x"; });
        assertEquals(0, evaluations.get());
    }
}
