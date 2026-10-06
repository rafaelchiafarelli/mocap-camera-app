package dev.mocapstudio.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * The last stream settings, in a properties file in the app's private storage. They outlive the process,
 * so a restart after a reboot or a crash resumes with them. Written to a temp file and renamed, so a crash
 * mid-write leaves the previous settings intact.
 */
final class SettingsStore {
    private final File file;

    SettingsStore(File file) {
        this.file = file;
    }

    /** The saved settings; defaults for anything missing or unreadable. */
    synchronized CameraStreamer.Config load() {
        CameraStreamer.Config cfg = new CameraStreamer.Config();
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return cfg;
        }
        for (String key : p.stringPropertyNames()) set(cfg, key, p.getProperty(key));
        return cfg;
    }

    synchronized void save(CameraStreamer.Config cfg) throws IOException {
        Properties p = new Properties();
        p.setProperty("width", Integer.toString(cfg.width));
        p.setProperty("height", Integer.toString(cfg.height));
        p.setProperty("fps", Integer.toString(cfg.fps));
        p.setProperty("bitrate", Integer.toString(cfg.bitrate));
        if (cfg.cameraId != null) p.setProperty("camera_id", cfg.cameraId);
        p.setProperty("i_frame_interval_s", Float.toString(cfg.iFrameIntervalS));
        p.setProperty("video_port", Integer.toString(cfg.videoPort));
        p.setProperty("sync_port", Integer.toString(cfg.syncPort));
        p.setProperty("control_port", Integer.toString(cfg.controlPort));
        p.setProperty("stats_port", Integer.toString(cfg.statsPort));
        if (cfg.serial != null) p.setProperty("serial", cfg.serial);
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "mocap-camera-app stream settings");
        }
        if (!tmp.renameTo(file)) throw new IOException("could not replace " + file);
    }

    /**
     * Sets one setting from its text form (intent extras). False if the key is unknown or the value invalid;
     * cfg is then unchanged. bitrate accepts kbps too (values below 100000). camera_id is a Camera2 id,
     * checked against the device's cameras only when the camera opens. mode is accepted only as h264, the one
     * mode of stream protocol v1, for camera-stream-eval's scripts.
     */
    static boolean set(CameraStreamer.Config cfg, String key, String value) {
        try {
            switch (key) {
                case "width": cfg.width = positive(value); return true;
                case "height": cfg.height = positive(value); return true;
                case "fps": cfg.fps = positive(value); return true;
                case "bitrate":
                    int b = positive(value);
                    cfg.bitrate = b < 100_000 ? b * 1000 : b;
                    return true;
                case "camera_id":
                    String id = value == null ? "" : value.trim();
                    if (id.isEmpty()) return false;
                    cfg.cameraId = id;
                    return true;
                case "i_frame_interval_s":
                    float f = Float.parseFloat(value.trim());
                    if (!(f > 0) || Float.isInfinite(f)) return false;
                    cfg.iFrameIntervalS = f;
                    return true;
                case "video_port": cfg.videoPort = port(value); return true;
                case "sync_port": cfg.syncPort = port(value); return true;
                case "control_port": cfg.controlPort = port(value); return true;
                case "stats_port": cfg.statsPort = port(value); return true;
                case "serial":
                    String serial = value == null ? "" : value.trim();
                    if (serial.isEmpty()) return false;
                    cfg.serial = serial;
                    return true;
                case "mode": return "h264".equals(value);
                default: return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int port(String value) {
        int v = positive(value);
        if (v > 65535) throw new NumberFormatException(value);
        return v;
    }

    private static int positive(String value) {
        int v = Integer.parseInt(value.trim());
        if (v <= 0) throw new NumberFormatException(value);
        return v;
    }
}
