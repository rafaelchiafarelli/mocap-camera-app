package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SettingsStoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file() {
        return new File(tmp.getRoot(), "settings.properties");
    }

    @Test
    public void defaultsWhenNothingSaved() {
        CameraStreamer.Config c = new SettingsStore(file()).load();
        CameraStreamer.Config d = new CameraStreamer.Config();
        assertEquals(d.width, c.width);
        assertEquals(d.height, c.height);
        assertEquals(d.fps, c.fps);
        assertEquals(d.bitrate, c.bitrate);
        assertEquals(null, c.cameraId);
    }

    @Test
    public void settingsSurviveARestart() throws Exception {
        CameraStreamer.Config c = new CameraStreamer.Config();
        c.width = 1280;
        c.height = 720;
        c.fps = 60;
        c.bitrate = 20_000_000;
        c.cameraId = "1";
        new SettingsStore(file()).save(c);

        // A new store on the same file, as after the process restarts.
        CameraStreamer.Config r = new SettingsStore(file()).load();
        assertEquals(1280, r.width);
        assertEquals(720, r.height);
        assertEquals(60, r.fps);
        assertEquals(20_000_000, r.bitrate);
        assertEquals("1", r.cameraId);
        assertFalse(new File(file().getPath() + ".tmp").exists());
    }

    @Test
    public void badSavedValuesFallBackToDefaults() throws Exception {
        try (FileOutputStream out = new FileOutputStream(file())) {
            out.write("width=abc\nfps=-5\nheight=1080\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        CameraStreamer.Config r = new SettingsStore(file()).load();
        assertEquals(new CameraStreamer.Config().width, r.width);
        assertEquals(new CameraStreamer.Config().fps, r.fps);
        assertEquals(1080, r.height);
    }

    @Test
    public void setValidatesAndConverts() {
        CameraStreamer.Config c = new CameraStreamer.Config();
        assertTrue(SettingsStore.set(c, "bitrate", "20000"));
        assertEquals(20_000_000, c.bitrate);       // kbps accepted
        assertTrue(SettingsStore.set(c, "bitrate", "8000000"));
        assertEquals(8_000_000, c.bitrate);
        assertTrue(SettingsStore.set(c, "mode", "h264"));
        assertFalse(SettingsStore.set(c, "mode", "mjpeg"));
        assertFalse(SettingsStore.set(c, "camera_id", " "));
        assertFalse(SettingsStore.set(c, "facing", "back"));
        assertTrue(SettingsStore.set(c, "camera_id", "2"));
        assertEquals("2", c.cameraId);
        assertFalse(SettingsStore.set(c, "fps", "0"));
        assertFalse(SettingsStore.set(c, "quality", "80"));
    }
}
