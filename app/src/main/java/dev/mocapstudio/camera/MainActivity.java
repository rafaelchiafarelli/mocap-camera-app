package dev.mocapstudio.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Starts StreamingService from the foreground and shows its status. Shown over the lock screen, so it can do
 * that at boot. Settings are remembered (SettingsStore) and can be changed by intent extras, e.g.
 *   am start -n dev.mocapstudio.camera/.MainActivity --ei width 1600 --ei height 1200 --ei fps 30 \
 *       --ei bitrate 8000000 --es facing back
 * or over HTTP: GET /control?width=1600&height=1200&fps=30&bitrate=10000000 (restarts the pipeline).
 * Closing this screen does not stop streaming; the notification's Stop action does.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MocapCamera";
    private static final String[] INT_KEYS = {"width", "height", "fps", "bitrate"};
    private static final String[] STRING_KEYS = {"facing", "mode"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    static void launch(Context context) {
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    @SuppressWarnings("deprecation") // window flags: the only option below API 27
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.BLACK);
        status.setTextSize(16);
        status.setPadding(24, 24, 24, 24);
        setContentView(status);

        saveExtras(getIntent());
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        } else {
            startStreaming();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        saveExtras(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startStreaming();
        else status.setText("Camera permission denied.");
    }

    /** Intent extras override the saved settings. */
    private void saveExtras(Intent i) {
        SettingsStore store = StreamingService.settings(this);
        CameraStreamer.Config cfg = store.load();
        boolean changed = false;
        for (String key : INT_KEYS) {
            if (i.hasExtra(key)) changed |= SettingsStore.set(cfg, key, Integer.toString(i.getIntExtra(key, 0)));
        }
        for (String key : STRING_KEYS) {
            if (i.hasExtra(key)) changed |= SettingsStore.set(cfg, key, i.getStringExtra(key));
        }
        if (!changed) return;
        try {
            store.save(cfg);
        } catch (IOException e) {
            Log.e(TAG, "saving settings failed", e);
        }
    }

    private void startStreaming() {
        Intent service = new Intent(this, StreamingService.class).setAction(StreamingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        ui.post(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String s = StreamingService.status();
            status.setText("http://" + localIp() + ":" + StreamingService.PORT + "/\n\n"
                    + (s == null ? "starting..." : s));
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresh);
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
