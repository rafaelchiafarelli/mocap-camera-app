package dev.mocapstudio.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Starts the camera stream and HTTP server. Settings are remembered (SharedPreferences), so tapping the
 * icon after a reboot resumes the last configuration. They can be changed by intent extras, e.g.
 *   am start -n dev.mocapstudio.camera/.MainActivity --ei width 1600 --ei height 1200 --ei fps 30 \
 *       --es mode h264 --ei bitrate 8000000 --ei quality 80 --es facing back
 * or over HTTP: GET /control?width=1600&height=1200&fps=30&mode=h264&bitrate=10000000 (restarts the pipeline).
 */
public class MainActivity extends Activity implements StreamServer.Controller {
    private static final String TAG = "MocapCamera";
    private static final int PORT = 8080;
    private static final int SYNC_PORT = 8081;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Stats stats;
    private StreamServer server;
    private CameraStreamer streamer;
    private TimeSyncServer timeSync;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.BLACK);
        status.setTextSize(16);
        status.setPadding(24, 24, 24, 24);
        setContentView(status);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            startStreaming();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startStreaming();
        else status.setText("Camera permission denied.");
    }

    private void startStreaming() {
        Intent i = getIntent();
        SharedPreferences.Editor e = prefs().edit();
        for (String key : INT_KEYS) {
            if (i.hasExtra(key)) e.putInt(key, i.getIntExtra(key, 0));
        }
        for (String key : STRING_KEYS) {
            if (i.hasExtra(key)) e.putString(key, i.getStringExtra(key));
        }
        e.apply();

        try {
            timeSync = new TimeSyncServer(SYNC_PORT);
            timeSync.start();
        } catch (Exception ex) {
            Log.e(TAG, "time sync failed", ex);
        }
        startPipeline();
        ui.post(refresh);
    }

    private static final String[] INT_KEYS = {"width", "height", "fps", "quality", "bitrate"};
    private static final String[] STRING_KEYS = {"mode", "facing"};

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    private CameraStreamer.Config loadConfig() {
        SharedPreferences p = prefs();
        CameraStreamer.Config cfg = new CameraStreamer.Config();
        cfg.width = p.getInt("width", cfg.width);
        cfg.height = p.getInt("height", cfg.height);
        cfg.fps = p.getInt("fps", cfg.fps);
        cfg.quality = p.getInt("quality", cfg.quality);
        cfg.bitrate = p.getInt("bitrate", cfg.bitrate);
        cfg.mode = p.getString("mode", cfg.mode);
        cfg.facing = p.getString("facing", cfg.facing);
        return cfg;
    }

    private void startPipeline() {
        CameraStreamer.Config cfg = loadConfig();
        stats = new Stats(this);
        server = new StreamServer(PORT, cfg.h264(), stats);
        server.setController(this);
        try {
            server.start();
            streamer = new CameraStreamer(this, cfg, server, stats);
            streamer.start();
        } catch (Exception ex) {
            Log.e(TAG, "start failed", ex);
            stats.error = ex.toString();
        }
    }

    private void stopPipeline() {
        if (streamer != null) streamer.stop();
        if (server != null) server.stop();
        streamer = null;
        server = null;
    }

    @Override
    public String info() {
        return DeviceInfo.json(this);
    }

    /** Called from an HTTP thread for /control. Saves the settings and restarts the pipeline shortly after replying. */
    @Override
    public String control(Map<String, String> params) {
        SharedPreferences.Editor e = prefs().edit();
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, String> p : params.entrySet()) {
            String key = p.getKey(), value = p.getValue();
            if (Arrays.asList(INT_KEYS).contains(key)) {
                try {
                    int v = Integer.parseInt(value);
                    if ("bitrate".equals(key) && v < 100_000) v *= 1000;  // accept kbps too
                    e.putInt(key, v);
                } catch (NumberFormatException ex) {
                    bad.append(key).append(' ');
                }
            } else if (Arrays.asList(STRING_KEYS).contains(key)) {
                e.putString(key, value);
            } else {
                bad.append(key).append(' ');
            }
        }
        e.commit();
        if (bad.length() == 0 && !params.isEmpty()) {
            ui.postDelayed(() -> { stopPipeline(); startPipeline(); }, 300);
        }
        CameraStreamer.Config c = loadConfig();
        return String.format(Locale.US,
                "{\"width\":%d,\"height\":%d,\"fps\":%d,\"mode\":\"%s\",\"bitrate\":%d,\"quality\":%d,"
                        + "\"facing\":\"%s\",\"restarting\":%b,\"rejected\":\"%s\"}",
                c.width, c.height, c.fps, c.mode, c.bitrate, c.quality, c.facing,
                bad.length() == 0 && !params.isEmpty(), bad.toString().trim());
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            stats.tick();
            String text = "http://" + localIp() + ":" + PORT + "/\n\n" + stats.toText();
            status.setText(text);
            Log.i(TAG, "stats " + stats.toJson());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresh);
        stopPipeline();
        if (timeSync != null) timeSync.stop();
        super.onDestroy();
    }

    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }
}
