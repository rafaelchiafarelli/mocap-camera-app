package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/** In JVM tests SystemClock.elapsedRealtimeNanos() is a stub returning 0; System.nanoTime() is real. */
public class SensorClockTest {

    @Before
    public void reset() {
        SensorClock.reset();
    }

    @Test
    public void uncalibratedUntilFirstFrame() {
        assertEquals("uncalibrated", SensorClock.name());
    }

    @Test
    public void boottimeWhenCameraSaysRealtime() {
        SensorClock.useBoottime();
        assertEquals("boottime", SensorClock.name());
    }

    @Test
    public void calibratePicksTheCloserClock() {
        SensorClock.calibrate(System.nanoTime());
        assertEquals("monotonic", SensorClock.name());
    }

    @Test
    public void calibratePicksBoottimeWhenCloser() {
        SensorClock.calibrate(1_000L);  // near the stubbed boottime of 0
        assertEquals("boottime", SensorClock.name());
    }

    @Test
    public void calibrationIsDecidedOnce() {
        SensorClock.useBoottime();
        SensorClock.calibrate(System.nanoTime());
        assertEquals("boottime", SensorClock.name());
    }

    @Test
    public void nowFollowsTheChosenClock() {
        SensorClock.calibrate(System.nanoTime());
        long before = System.nanoTime();
        long now = SensorClock.now();
        assertEquals(true, now >= before && now <= System.nanoTime());
    }
}
