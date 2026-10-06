package dev.mocapstudio.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.harpia.generated.ControlReply;
import com.harpia.generated.ControlRequest;
import com.harpia.generated.ControlResult;
import com.harpia.generated.ControlSetting;
import com.harpia.generated.ControlStatus;
import com.harpia.generated.Flag;
import com.harpia.generated.StreamSettings;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the camera → encoder → servers pipeline as a foreground service of type camera, so it keeps streaming
 * with the screen off. Holds a partial wake lock and a high-performance Wi-Fi lock for the same reason. The
 * recorder controls it over ControlChannel (ZeroMQ): stream settings, device info, and CameraStats every second.
 *
 * Android only lets a foreground service use the camera if it was started while the app was in the
 * foreground, so every start goes through MainActivity: at boot (BootReceiver), and when the system recreates
 * this service after a crash (a null intent), it opens MainActivity, which starts it again from the foreground.
 */
public class StreamingService extends Service {
    private static final String TAG = "MocapCamera";
    /** How long a ControlRequest with stream settings waits for the restarted pipeline's first frame. */
    private static final long RESTART_WAIT_MS = 8000;
    static final String ACTION_START = "dev.mocapstudio.camera.START";
    static final String ACTION_STOP = "dev.mocapstudio.camera.STOP";
    private static final String CHANNEL = "streaming";
    private static final int NOTIFICATION_ID = 1;

    private static volatile StreamingService current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SettingsStore settings;
    private volatile Stats stats;
    private StreamServer server;
    private CameraStreamer streamer;
    private TimeSyncServer timeSync;
    private ControlChannel channel;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    static SettingsStore settings(Context context) {
        return new SettingsStore(new File(context.getFilesDir(), "settings.properties"));
    }

    /** Status text for MainActivity, or null when not streaming. */
    static String status() {
        StreamingService s = current;
        Stats st = s == null ? null : s.stats;
        return st == null ? null : st.toText();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = settings(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            // Recreated by the system after a crash: restart from the foreground, through MainActivity.
            Log.i(TAG, "service restarted by the system, reopening the app");
            MainActivity.launch(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (current != this) {
            current = this;
            acquireLocks();
            CameraStreamer.Config cfg = settings.load();
            try {
                timeSync = new TimeSyncServer(cfg.syncPort);
                timeSync.start();
            } catch (IOException e) {
                Log.e(TAG, "time sync failed", e);
            }
            startPipeline();
            try {
                channel = new ControlChannel(cfg.serial, cfg.controlPort, cfg.statsPort, this::handle,
                        (m, t) -> Log.w(TAG, m, t));
                channel.start();
            } catch (RuntimeException e) {
                Log.e(TAG, "control channel failed", e);
                stats.error = "control channel: " + e;
            }
            main.post(tick);
        }
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, StreamingService.class).setAction(ACTION_STOP), immutable);
        Notification n = b.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Mocap Camera streaming")
                .setContentText("video port " + settings.load().videoPort)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @SuppressWarnings("deprecation") // WIFI_MODE_FULL_HIGH_PERF: the only option below API 29
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MocapCamera:streaming");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int mode = Build.VERSION.SDK_INT >= 29
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "MocapCamera:streaming");
        wifiLock.acquire();
    }

    private void startPipeline() {
        CameraStreamer.Config cfg = settings.load();
        stats = new Stats(this);
        server = new StreamServer(cfg.videoPort, stats);
        try {
            server.start();
            streamer = new CameraStreamer(this, cfg, server, stats);
            streamer.start();
        } catch (Exception ex) {
            Log.e(TAG, "start failed", ex);
            stats.error = ex instanceof CameraSelector.SelectionException ? ex.getMessage() : ex.toString();
        }
        if (cfg.serial == null) stats.note = "no serial set: the recorder can't address this device (./dev install)";
    }

    private void stopPipeline() {
        if (streamer != null) streamer.stop();
        if (server != null) server.stop();
        streamer = null;
        server = null;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Stats st = stats;
            if (st != null) {
                st.tick();
                Log.i(TAG, "stats " + st.toJson());
                ControlChannel ch = channel;
                if (ch != null) ch.publish(st.cameraStats());
            }
            main.postDelayed(this, 1000);
        }
    };

    /** A ControlRequest for this device, on the control channel's thread. */
    private ControlReply.Builder handle(ControlRequest r) {
        ControlReply.Builder out = ControlReply.newBuilder();
        if (r.hasStream()) applyStream(r.getStream(), out);
        for (ControlSetting s : r.getSettingsList()) {
            out.addResults(ControlResult.newBuilder().setKey(s.getKey()).setRequested(s.getValue())
                    .setStatus(ControlStatus.CONTROL_STATUS_UNSUPPORTED)
                    .setNote("camera controls are not implemented yet (mocap-camera-app control/2, /3)"));
        }
        if (r.getWantDeviceInfo() == Flag.FLAG_ON) {
            out.setDeviceInfo(DeviceInfo.proto(this, settings.load().serial, streamer));
        }
        return out;
    }

    /**
     * Saves the requested stream settings, restarts the pipeline with them and reports what it actually runs
     * (stream_applied), or the problem. fps is a whole number on Camera2: a fractional one is rounded, and said.
     */
    private void applyStream(StreamSettings want, ControlReply.Builder out) {
        CameraStreamer.Config c = settings.load();
        int fps = Math.round(want.getFps());
        boolean ok = SettingsStore.set(c, "camera_id", want.getCameraId())
                & SettingsStore.set(c, "width", Integer.toString(want.getWidth()))
                & SettingsStore.set(c, "height", Integer.toString(want.getHeight()))
                & SettingsStore.set(c, "fps", Integer.toString(fps))
                & SettingsStore.set(c, "bitrate", Integer.toString(want.getBitrateKbps() * 1000))
                & SettingsStore.set(c, "i_frame_interval_s", Float.toString(want.getIFrameIntervalS()));
        if (!ok) {
            out.addProblems("stream settings rejected (empty camera_id, or a non-positive value): stream unchanged");
            return;
        }
        if (fps != want.getFps()) out.addProblems("fps " + want.getFps() + " requested, " + fps + " used (Camera2 fps are whole)");
        try {
            settings.save(c);
        } catch (IOException e) {
            out.addProblems("stream settings not saved: " + e.getMessage());
            return;
        }
        CountDownLatch restarted = new CountDownLatch(1);
        main.post(() -> { stopPipeline(); startPipeline(); restarted.countDown(); });
        try {
            if (!restarted.await(RESTART_WAIT_MS, TimeUnit.MILLISECONDS)) {
                out.addProblems("pipeline restart timed out");
                return;
            }
            long deadline = System.currentTimeMillis() + RESTART_WAIT_MS;
            Stats st = stats;
            while (st.encodedFrames.get() == 0 && st.error.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            CameraStreamer s = streamer;
            StreamSettings applied = s == null ? null : s.applied();
            if (!st.error.isEmpty()) out.addProblems(st.error);
            else if (st.encodedFrames.get() == 0) out.addProblems("no frame within " + RESTART_WAIT_MS + " ms of the restart");
            if (applied != null && st.error.isEmpty()) out.setStreamApplied(applied);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.addProblems("interrupted");
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        if (current == this) {
            if (channel != null) channel.stop();
            stopPipeline();
            if (timeSync != null) timeSync.stop();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            current = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
