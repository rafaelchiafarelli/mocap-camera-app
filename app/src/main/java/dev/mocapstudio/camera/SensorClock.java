package dev.mocapstudio.camera;

import android.os.SystemClock;
import android.util.Log;

/**
 * The clock camera sensor timestamps are on. Camera2 says REALTIME (boottime) or UNKNOWN; with UNKNOWN it's
 * usually CLOCK_MONOTONIC, but we decide from the first frame: whichever clock it's closest to.
 * Everything that needs "now" in sensor-timestamp terms (latency, the time-sync server) goes through now().
 */
final class SensorClock {
    private static final String TAG = "MocapCamera";
    private static volatile boolean boottime;
    private static volatile boolean calibrated;

    private SensorClock() { }

    static void useBoottime() {
        boottime = true;
        calibrated = true;
    }

    /** Call with the first frame's SENSOR_TIMESTAMP when the timestamp source is UNKNOWN. */
    static void calibrate(long sensorNs) {
        if (calibrated) return;
        long mono = System.nanoTime(), boot = SystemClock.elapsedRealtimeNanos();
        boottime = Math.abs(boot - sensorNs) < Math.abs(mono - sensorNs);
        calibrated = true;
        Log.i(TAG, "sensor clock = " + name() + " (sensor-mono " + (sensorNs - mono) / 1_000_000
                + " ms, sensor-boot " + (sensorNs - boot) / 1_000_000 + " ms)");
    }

    static long now() {
        return boottime ? SystemClock.elapsedRealtimeNanos() : System.nanoTime();
    }

    /** Tablet wall clock (Unix ns) minus sensor clock: sensorNs + this = tablet's idea of Unix time. */
    static long unixOffsetNs() {
        return System.currentTimeMillis() * 1_000_000L - now();
    }

    /** Tests only: back to the state before the first frame. */
    static void reset() {
        boottime = false;
        calibrated = false;
    }

    static String name() {
        return !calibrated ? "uncalibrated" : boottime ? "boottime" : "monotonic";
    }
}
