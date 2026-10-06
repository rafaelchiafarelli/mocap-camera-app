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

import java.util.Locale;

/**
 * /info: what this device can do, as JSON, from the Android APIs (no vendor-specific dumpsys parsing).
 * Cameras (facing, sizes for YUV and for a MediaCodec surface, fps ranges, hardware level, timestamp source)
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
                kv(sb, "facing", facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back"
                        : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external").append(',');
                kv(sb, "hardware_level", levelName(level)).append(',');
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

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        return sb.append('"').append(k).append("\":\"").append(escape(v == null ? "" : v)).append('"');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
