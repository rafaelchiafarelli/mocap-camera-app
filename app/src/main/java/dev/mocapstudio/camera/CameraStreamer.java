package dev.mocapstudio.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;

/** Camera2 → MediaCodec input Surface (hardware H.264) → StreamServer. */
final class CameraStreamer {
    private static final String TAG = "MocapCamera";

    static final class Config {
        int width = 1600;
        int height = 1200;
        int fps = 30;
        String facing = "back";
        int bitrate = 10_000_000;
    }

    private final Context context;
    private final Config cfg;
    private final StreamServer server;
    private final Stats stats;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private H264Encoder encoder;
    private Surface target;

    CameraStreamer(Context context, Config cfg, StreamServer server, Stats stats) {
        this.context = context;
        this.cfg = cfg;
        this.server = server;
        this.stats = stats;
    }

    @SuppressLint("MissingPermission") // checked by MainActivity before start()
    void start() throws CameraAccessException {
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String cameraId = pickCamera(manager);
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = pickSize(map.getOutputSizes(MediaCodec.class));
        Range<Integer> fpsRange = pickFpsRange(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
        Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Integer tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (tsSource != null && tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            SensorClock.useBoottime();
        }

        encoder = new H264Encoder(size.getWidth(), size.getHeight(), fpsRange.getUpper(), cfg.bitrate, server, stats);
        try {
            target = encoder.start();
        } catch (IOException e) {
            throw new IllegalStateException("H.264 encoder failed: " + e.getMessage(), e);
        }
        server.setKeyFrameRequester(encoder::requestKeyFrame);
        String encoding = String.format(Locale.US, "h264 %s %d kbps", encoder.name(), cfg.bitrate / 1000);

        stats.config = String.format(Locale.US, "camera %s (%s) %dx%d fps %s %s hw-level %s",
                cameraId, facingName(chars.get(CameraCharacteristics.LENS_FACING)), size.getWidth(), size.getHeight(),
                fpsRange, encoding, hwLevel);
        Log.i(TAG, stats.config);

        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) {
                device = d;
                createSession(fpsRange);
            }
            @Override public void onDisconnected(CameraDevice d) {
                stats.error = "camera disconnected";
                d.close();
            }
            @Override public void onError(CameraDevice d, int error) {
                stats.error = "camera error " + error;
                d.close();
            }
        }, cameraHandler);
    }

    void stop() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        if (encoder != null) encoder.stop();
        if (cameraThread != null) cameraThread.quitSafely();
    }

    @SuppressWarnings("deprecation") // createCaptureSession(List, ...) is the only option below API 28
    private void createSession(Range<Integer> fpsRange) {
        try {
            device.createCaptureSession(Collections.singletonList(target),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(target);
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                s.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override public void onCaptureCompleted(CameraCaptureSession ss,
                                            CaptureRequest r, TotalCaptureResult result) {
                                        stats.cameraFrames.incrementAndGet();
                                        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                        if (ts != null) SensorClock.calibrate(ts);
                                    }
                                }, cameraHandler);
                            } catch (CameraAccessException | IllegalStateException e) {
                                stats.error = "repeating request failed: " + e.getMessage();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            stats.error = "capture session configuration failed";
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            stats.error = "createCaptureSession failed: " + e.getMessage();
        }
    }

    private String pickCamera(CameraManager manager) throws CameraAccessException {
        int wanted = "front".equals(cfg.facing)
                ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == wanted) return id;
        }
        if (ids.length == 0) throw new IllegalStateException("no cameras");
        stats.note = "no " + cfg.facing + " camera, using camera " + ids[0];
        return ids[0];
    }

    private static String facingName(Integer facing) {
        if (facing == null) return "?";
        if (facing == CameraCharacteristics.LENS_FACING_BACK) return "back";
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "front";
        return "external";
    }

    /** Exact match if available, otherwise the size with the closest pixel count. */
    private Size pickSize(Size[] sizes) {
        long target = (long) cfg.width * cfg.height;
        Size best = sizes[0];
        for (Size s : sizes) {
            if (s.getWidth() == cfg.width && s.getHeight() == cfg.height) return s;
            long d = Math.abs((long) s.getWidth() * s.getHeight() - target);
            long bd = Math.abs((long) best.getWidth() * best.getHeight() - target);
            if (d < bd) best = s;
        }
        return best;
    }

    /** Prefer a fixed [fps, fps] range; otherwise the narrowest range whose upper bound is closest to fps. */
    private Range<Integer> pickFpsRange(Range<Integer>[] ranges) {
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == cfg.fps && r.getUpper() == cfg.fps) return r;
            if (best == null) { best = r; continue; }
            int d = Math.abs(r.getUpper() - cfg.fps), bd = Math.abs(best.getUpper() - cfg.fps);
            int span = r.getUpper() - r.getLower(), bspan = best.getUpper() - best.getLower();
            if (d < bd || (d == bd && span < bspan)) best = r;
        }
        return best;
    }
}
