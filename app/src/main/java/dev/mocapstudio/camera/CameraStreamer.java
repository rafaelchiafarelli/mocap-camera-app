package dev.mocapstudio.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;

/**
 * mjpeg: Camera2 → YUV ImageReader → software JPEG per frame → StreamServer.
 * h264:  Camera2 → MediaCodec input Surface (hardware) → StreamServer.
 */
final class CameraStreamer {
    private static final String TAG = "MocapCamera";

    static final class Config {
        int width = 1600;
        int height = 1200;
        int fps = 30;
        int quality = 80;
        String facing = "back";
        String mode = "h264";
        int bitrate = 10_000_000;

        boolean h264() { return "h264".equals(mode); }
    }

    private final Context context;
    private final Config cfg;
    private final StreamServer server;
    private final Stats stats;

    private HandlerThread cameraThread, encoderThread;
    private Handler cameraHandler, encoderHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private H264Encoder encoder;
    private Surface target;

    private byte[] nv21, uBuf, vBuf;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);

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
        encoderThread = new HandlerThread("encoder");
        encoderThread.start();
        encoderHandler = new Handler(encoderThread.getLooper());

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String cameraId = pickCamera(manager);
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size size = cfg.h264()
                ? pickSize(map.getOutputSizes(MediaCodec.class))
                : pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
        Range<Integer> fpsRange = pickFpsRange(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
        Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Integer tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (tsSource != null && tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            SensorClock.useBoottime();
        }

        String encoding;
        if (cfg.h264()) {
            encoder = new H264Encoder(size.getWidth(), size.getHeight(), fpsRange.getUpper(), cfg.bitrate, server, stats);
            try {
                target = encoder.start();
            } catch (IOException e) {
                throw new IllegalStateException("H.264 encoder failed: " + e.getMessage(), e);
            }
            server.setKeyFrameRequester(encoder::requestKeyFrame);
            encoding = String.format(Locale.US, "h264 %s %d kbps", encoder.name(), cfg.bitrate / 1000);
        } else {
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, encoderHandler);
            target = reader.getSurface();
            encoding = "mjpeg q" + cfg.quality;
        }

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
        if (encoderThread != null) encoderThread.quitSafely();
        if (reader != null) reader.close();
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

    private void onImage(ImageReader r) {
        Image image = r.acquireLatestImage();
        if (image == null) return;
        long t0 = System.nanoTime();
        int w = image.getWidth(), h = image.getHeight();
        long ts;
        try {
            toNv21(image);
            ts = image.getTimestamp();
        } finally {
            image.close();
        }
        jpegOut.reset();
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), cfg.quality, jpegOut);
        byte[] jpeg = jpegOut.toByteArray();
        stats.encodeNanosTotal.addAndGet(System.nanoTime() - t0);
        stats.jpegBytesTotal.addAndGet(jpeg.length);
        stats.encodedFrames.incrementAndGet();
        server.publish(jpeg, ts, true);
    }

    /** YUV_420_888 (any strides) → NV21 into the reused nv21 buffer. */
    private void toNv21(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        int size = w * h;
        if (nv21 == null || nv21.length != size * 3 / 2) nv21 = new byte[size * 3 / 2];

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int pos = 0;
        if (yPix == 1 && yRow == w) {
            y.get(nv21, 0, size);
            pos = size;
        } else {
            for (int row = 0; row < h; row++) {
                int base = row * yRow;
                for (int col = 0; col < w; col++) nv21[pos++] = y.get(base + col * yPix);
            }
        }

        ByteBuffer u = planes[1].getBuffer(), v = planes[2].getBuffer();
        int uvRow = planes[1].getRowStride(), uvPix = planes[1].getPixelStride();
        if (uBuf == null || uBuf.length < u.remaining()) uBuf = new byte[u.remaining()];
        if (vBuf == null || vBuf.length < v.remaining()) vBuf = new byte[v.remaining()];
        int uLen = u.remaining(), vLen = v.remaining();
        u.get(uBuf, 0, uLen);
        v.get(vBuf, 0, vLen);
        for (int row = 0; row < h / 2; row++) {
            int base = row * uvRow;
            for (int col = 0; col < w / 2; col++) {
                int i = base + col * uvPix;
                nv21[pos++] = vBuf[i];
                nv21[pos++] = uBuf[i];
            }
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
