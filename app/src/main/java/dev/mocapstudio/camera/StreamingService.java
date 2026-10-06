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

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the camera → encoder → servers pipeline as a foreground service of type camera, so it keeps streaming
 * with the screen off. Holds a partial wake lock and a high-performance Wi-Fi lock for the same reason.
 *
 * Android only lets a foreground service use the camera if it was started while the app was in the
 * foreground, so every start goes through MainActivity: at boot (BootReceiver), and when the system recreates
 * this service after a crash (a null intent), it opens MainActivity, which starts it again from the foreground.
 */
public class StreamingService extends Service implements StreamServer.Controller {
    private static final String TAG = "MocapCamera";
    static final int PORT = 8080;
    static final int SYNC_PORT = 8081;
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
            try {
                timeSync = new TimeSyncServer(SYNC_PORT);
                timeSync.start();
            } catch (IOException e) {
                Log.e(TAG, "time sync failed", e);
            }
            startPipeline();
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
                .setContentText("port " + PORT)
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
        server = new StreamServer(PORT, stats);
        server.setController(this);
        try {
            server.start();
            streamer = new CameraStreamer(this, cfg, server, stats);
            streamer.start();
        } catch (Exception ex) {
            Log.e(TAG, "start failed", ex);
            stats.error = ex instanceof CameraSelector.SelectionException ? ex.getMessage() : ex.toString();
        }
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
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    public String info() {
        return DeviceInfo.json(this);
    }

    /** Called from an HTTP thread for /control. Saves the settings and restarts the pipeline shortly after replying. */
    @Override
    public String control(Map<String, String> params) {
        CameraStreamer.Config c = settings.load();
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, String> p : params.entrySet()) {
            if (!SettingsStore.set(c, p.getKey(), p.getValue())) bad.append(p.getKey()).append(' ');
        }
        boolean restart = bad.length() == 0 && !params.isEmpty();
        if (restart) {
            try {
                settings.save(c);
            } catch (IOException e) {
                Log.e(TAG, "saving settings failed", e);
                restart = false;
                bad.append("(not saved: ").append(e.getMessage()).append(')');
            }
        }
        if (restart) main.postDelayed(() -> { stopPipeline(); startPipeline(); }, 300);
        if (!restart) c = settings.load();
        return String.format(Locale.US,
                "{\"width\":%d,\"height\":%d,\"fps\":%d,\"mode\":\"h264\",\"bitrate\":%d,"
                        + "\"camera_id\":%s,\"restarting\":%b,\"rejected\":\"%s\"}",
                c.width, c.height, c.fps, c.bitrate, c.cameraId == null ? "null" : "\"" + c.cameraId + "\"",
                restart, bad.toString().trim());
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(tick);
        if (current == this) {
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
