package dev.mocapstudio.camera;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import com.harpia.generated.CameraStats;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Counters shared by the camera, encoder and server, turned into per-second rates by tick(). */
final class Stats {
    final AtomicLong cameraFrames = new AtomicLong();
    final AtomicLong encodedFrames = new AtomicLong();
    final AtomicLong sentFrames = new AtomicLong();
    final AtomicLong clientSkippedFrames = new AtomicLong();
    final AtomicLong encodeNanosTotal = new AtomicLong();
    final AtomicLong jpegBytesTotal = new AtomicLong();
    final AtomicInteger clients = new AtomicInteger();
    final AtomicLong slowWrites = new AtomicLong();
    private volatile long maxWriteNs;

    volatile String config = "";
    volatile String error = "";
    volatile String note = "";

    private final Context context;
    private final long startMs = SystemClock.elapsedRealtime();
    private final int cores = Runtime.getRuntime().availableProcessors();

    private long lastTickMs = startMs;
    private long lastCpuMs = Process.getElapsedCpuTime();
    private long lastCamera, lastEncoded, lastSent, lastEncodeNanos, lastBytes;

    private volatile double cameraFps, encodedFps, sentFps, encodeMs, jpegKb, mbps, cpuPercent;
    private volatile float batteryTempC = Float.NaN;
    private volatile int batteryLevel = -1, thermalStatus = -1;
    private volatile boolean charging;

    Stats(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Called once per second. */
    synchronized void tick() {
        long now = SystemClock.elapsedRealtime();
        double dt = Math.max(1, now - lastTickMs) / 1000.0;
        long cam = cameraFrames.get(), enc = encodedFrames.get(), sent = sentFrames.get();
        long encNs = encodeNanosTotal.get(), bytes = jpegBytesTotal.get();
        long cpuMs = Process.getElapsedCpuTime();

        cameraFps = (cam - lastCamera) / dt;
        encodedFps = (enc - lastEncoded) / dt;
        sentFps = (sent - lastSent) / dt;
        long encDelta = enc - lastEncoded;
        encodeMs = encDelta > 0 ? (encNs - lastEncodeNanos) / 1e6 / encDelta : 0;
        jpegKb = encDelta > 0 ? (bytes - lastBytes) / 1024.0 / encDelta : 0;
        mbps = (bytes - lastBytes) * 8 / 1e6 / dt;
        cpuPercent = (cpuMs - lastCpuMs) / (dt * 1000.0) / cores * 100.0;

        lastTickMs = now; lastCpuMs = cpuMs;
        lastCamera = cam; lastEncoded = enc; lastSent = sent;
        lastEncodeNanos = encNs; lastBytes = bytes;

        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            batteryTempC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            batteryLevel = level < 0 ? -1 : level * 100 / scale;
            charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            thermalStatus = pm.getCurrentThermalStatus();
        }
    }

    /** Longest time a single frame write to a client blocked. */
    synchronized void recordWrite(long ns) {
        if (ns > maxWriteNs) maxWriteNs = ns;
    }

    /** Frames the camera delivered that were never encoded (encoder too slow). */
    long droppedFrames() {
        return Math.max(0, cameraFrames.get() - encodedFrames.get());
    }

    /**
     * The per-second CameraStats for the recorder (serial is filled in by ControlChannel). Thermal status is
     * PowerManager's (API 29+); below that Android has none and 0 (THERMAL_STATUS_NONE) is sent.
     */
    CameraStats.Builder cameraStats() {
        return CameraStats.newBuilder()
                .setSensorNs(SensorClock.now())
                .setCameraFps((float) cameraFps)
                .setEncoderFps((float) encodedFps)
                .setDroppedFrames(droppedFrames())
                .setCpuPercent((float) Math.max(0, cpuPercent))
                .setBatteryTempC(batteryTempC)
                .setThermalStatus(Math.max(0, thermalStatus));
    }

    String toJson() {
        return String.format(Locale.US,
                "{\"uptime_s\":%.1f,\"config\":\"%s\",\"error\":\"%s\",\"note\":\"%s\","
                        + "\"camera_fps\":%.2f,\"encoded_fps\":%.2f,\"sent_fps\":%.2f,"
                        + "\"camera_frames\":%d,\"encoded_frames\":%d,\"dropped_frames\":%d,"
                        + "\"client_skipped_frames\":%d,\"clients\":%d,\"slow_writes\":%d,\"max_write_ms\":%.1f,"
                        + "\"encode_ms\":%.2f,\"jpeg_kb\":%.1f,\"mbps\":%.2f,"
                        + "\"app_cpu_percent\":%.1f,\"cores\":%d,"
                        + "\"battery_temp_c\":%.1f,\"battery_level\":%d,\"charging\":%b,"
                        + "\"thermal_status\":%d,\"sensor_clock\":\"%s\",\"android_sdk\":%d,\"device\":\"%s\"}",
                (SystemClock.elapsedRealtime() - startMs) / 1000.0, escape(config), escape(error), escape(note),
                cameraFps, encodedFps, sentFps,
                cameraFrames.get(), encodedFrames.get(), droppedFrames(),
                clientSkippedFrames.get(), clients.get(), slowWrites.get(), maxWriteNs / 1e6,
                encodeMs, jpegKb, mbps,
                cpuPercent, cores,
                batteryTempC, batteryLevel, charging,
                thermalStatus, SensorClock.name(), Build.VERSION.SDK_INT, escape(Build.MANUFACTURER + " " + Build.MODEL));
    }

    String toText() {
        return String.format(Locale.US,
                "%s\ncamera %.1f fps | encoded %.1f fps | sent %.1f fps\n"
                        + "dropped %d | encode %.1f ms | %.0f KB/frame | %.1f Mbps | clients %d | slow writes %d\n"
                        + "app CPU %.0f%% of %d cores | battery %.1f°C %d%%%s | thermal %d%s",
                config, cameraFps, encodedFps, sentFps,
                droppedFrames(), encodeMs, jpegKb, mbps, clients.get(), slowWrites.get(),
                cpuPercent, cores, batteryTempC, batteryLevel, charging ? " (charging)" : "",
                thermalStatus, error.isEmpty() ? "" : "\nERROR: " + error);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
