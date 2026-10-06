package dev.mocapstudio.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Range;
import android.util.Size;

import com.harpia.generated.CameraFacing;
import com.harpia.generated.CameraInfo;
import com.harpia.generated.FpsRange;
import com.harpia.generated.FrameSize;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * What this device can do, from the Android APIs (no vendor-specific dumpsys parsing): json() for the log and
 * proto() for the recorder.
 * Every camera by id (facing, focal lengths in mm, sensor orientation in degrees, hardware level, logical
 * multi-camera and its physical lens ids, timestamp source, sizes for YUV and for a MediaCodec surface, fps
 * ranges), so the recorder knows which id to declare and how the image is oriented (pixels are never rotated),
 * and every H.264 encoder (hardware or software, supported size and bitrate ranges).
 */
final class DeviceInfo {
    private DeviceInfo() { }

    static String json(Context context) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        kv(sb, "manufacturer", Build.MANUFACTURER).append(',');
        kv(sb, "model", Build.MODEL).append(',');
        kv(sb, "android", Build.VERSION.RELEASE).append(',');
        sb.append("\"sdk\":").append(Build.VERSION.SDK_INT).append(',');
        kv(sb, "hardware", Build.HARDWARE).append(',');
        kv(sb, "soc", Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.BOARD).append(',');
        sb.append("\"cores\":").append(Runtime.getRuntime().availableProcessors()).append(',');
        kv(sb, "sensor_clock", SensorClock.name()).append(',');
        sb.append("\"cameras\":[").append(cameras(context)).append("],");
        sb.append("\"h264_encoders\":[").append(encoders()).append(']');
        sb.append('}');
        return sb.toString();
    }

    private static String cameras(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            CameraManager m = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : m.getCameraIdList()) {
                CameraCharacteristics c = m.getCameraCharacteristics(id);
                if (sb.length() > 0) sb.append(',');
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                Integer ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                sb.append('{');
                kv(sb, "id", id).append(',');
                kv(sb, "facing", CameraStreamer.facingName(facing)).append(',');
                sb.append("\"focal_lengths_mm\":[");
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focal != null) {
                    for (int i = 0; i < focal.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(String.format(Locale.US, "%.3f", focal[i]));
                    }
                }
                sb.append("],");
                Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sb.append("\"sensor_orientation_deg\":").append(orientation == null ? "null" : orientation).append(',');
                kv(sb, "hardware_level", levelName(level)).append(',');
                CameraSelector.Camera sel = CameraStreamer.describe(c, id);
                sb.append("\"logical\":").append(sel.logical).append(",\"physical_ids\":[");
                for (int i = 0; i < sel.physicalIds.size(); i++) {
                    if (i > 0) sb.append(',');
                    kv0(sb, sel.physicalIds.get(i));
                }
                sb.append("],");
                kv(sb, "timestamp_source", ts != null && ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                        ? "realtime" : "unknown").append(',');
                sb.append("\"fps_ranges\":[");
                Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null) {
                    for (int i = 0; i < ranges.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append("\"").append(ranges[i].getLower()).append('-').append(ranges[i].getUpper()).append('"');
                    }
                }
                sb.append("],\"encoder_sizes\":[");
                if (map != null) sizes(sb, map, map.getOutputSizes(MediaCodec.class));
                sb.append("],\"yuv_sizes\":[");
                if (map != null) sizes(sb, map, map.getOutputSizes(ImageFormat.YUV_420_888));
                sb.append("]}");
            }
        } catch (Exception e) {
            sb.append("{\"error\":\"").append(escape(e.toString())).append("\"}");
        }
        return sb.toString();
    }

    /** "WxH@maxfps" using the minimum frame duration for MediaCodec/YUV outputs. */
    private static void sizes(StringBuilder sb, StreamConfigurationMap map, Size[] sizes) {
        if (sizes == null) return;
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) sb.append(',');
            long ns = 0;
            try { ns = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, sizes[i]); } catch (Exception ignored) { }
            sb.append('"').append(sizes[i].getWidth()).append('x').append(sizes[i].getHeight());
            if (ns > 0) sb.append(String.format(Locale.US, "@%.0f", 1e9 / ns));
            sb.append('"');
        }
    }

    /**
     * The same, as the generated camera.harpia DeviceInfo sent in a ControlReply, with every settable control of
     * every camera (ControlCatalog); the streaming camera's controls also carry their current and default values.
     */
    static com.harpia.generated.DeviceInfo.Builder proto(Context context, String serial, CameraStreamer streaming) {
        com.harpia.generated.DeviceInfo.Builder d = com.harpia.generated.DeviceInfo.newBuilder()
                .setModel(Build.MANUFACTURER + " " + Build.MODEL)
                .setSerial(serial)
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setAppVersion(appVersion(context));
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) d.addH264Encoders(info.getName());
            }
        }
        try {
            CameraManager m = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : m.getCameraIdList()) {
                boolean open = streaming != null && id.equals(streaming.cameraId());
                d.addCameras(cameraInfo(m.getCameraCharacteristics(id), id,
                        open ? streaming.currentValues() : Collections.<String, Object>emptyMap(),
                        open ? streaming.defaultValues() : Collections.<String, Object>emptyMap()));
            }
        } catch (Exception e) {
            android.util.Log.w("MocapCamera", "camera list failed", e);
        }
        return d;
    }

    private static CameraInfo cameraInfo(CameraCharacteristics c, String id, Map<String, Object> current,
                                         Map<String, Object> defaults) {
        CameraInfo.Builder b = CameraInfo.newBuilder().setCameraId(id);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        b.setFacing(facing == null ? CameraFacing.CAMERA_FACING_UNSET
                : facing == CameraCharacteristics.LENS_FACING_BACK ? CameraFacing.CAMERA_FACING_BACK
                : facing == CameraCharacteristics.LENS_FACING_FRONT ? CameraFacing.CAMERA_FACING_FRONT
                : CameraFacing.CAMERA_FACING_EXTERNAL);
        b.setHardwareLevel(levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)).toUpperCase(Locale.US));
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (orientation != null) b.setSensorOrientationDeg(orientation);
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focal != null) for (float f : focal) b.addFocalLengthsMm(f);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(MediaCodec.class);
        if (sizes != null) {
            for (Size s : sizes) b.addSizes(FrameSize.newBuilder().setWidth(s.getWidth()).setHeight(s.getHeight()));
        }
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                b.addFpsRanges(FpsRange.newBuilder().setMinFps(r.getLower()).setMaxFps(r.getUpper()));
            }
        }
        b.addAllControls(ControlCatalog.capabilities(Camera2Values.requestKeys(c), Camera2Values.characteristics(c),
                current, defaults));
        return b.build();
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String encoders() {
        StringBuilder sb = new StringBuilder();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AVC)) continue;
                MediaCodecInfo.VideoCapabilities v = info.getCapabilitiesForType(type).getVideoCapabilities();
                if (sb.length() > 0) sb.append(',');
                sb.append('{');
                kv(sb, "name", info.getName()).append(',');
                kv(sb, "hardware", Build.VERSION.SDK_INT >= 29 ? String.valueOf(info.isHardwareAccelerated())
                        : String.valueOf(!info.getName().startsWith("OMX.google") && !info.getName().startsWith("c2.android"))).append(',');
                kv(sb, "max_size", v.getSupportedWidths().getUpper() + "x" + v.getSupportedHeights().getUpper()).append(',');
                kv(sb, "bitrate_kbps", v.getBitrateRange().getLower() / 1000 + "-" + v.getBitrateRange().getUpper() / 1000);
                sb.append('}');
            }
        }
        return sb.toString();
    }

    private static String levelName(Integer level) {
        if (level == null) return "?";
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "legacy";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "limited";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "full";
            case 3: return "level_3";
            case 4: return "external";
            default: return String.valueOf(level);
        }
    }

    private static StringBuilder kv0(StringBuilder sb, String v) {
        return sb.append('"').append(escape(v)).append('"');
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        return sb.append('"').append(k).append("\":\"").append(escape(v == null ? "" : v)).append('"');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
