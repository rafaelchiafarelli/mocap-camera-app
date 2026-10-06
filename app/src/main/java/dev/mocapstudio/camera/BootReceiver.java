package dev.mocapstudio.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts streaming when the tablet boots, and after the app is updated. Opens MainActivity, which starts
 * StreamingService from the foreground (a camera service started straight from here could not use the camera).
 * Opening an activity from the background needs "display over other apps" on Android 10+ (./dev install
 * grants it).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("MocapCamera", "starting after " + intent.getAction());
        MainActivity.launch(context);
    }
}
