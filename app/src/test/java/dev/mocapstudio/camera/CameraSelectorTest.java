package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class CameraSelectorTest {
    private static final List<CameraSelector.Camera> CAMERAS = Arrays.asList(
            new CameraSelector.Camera("0", true, Arrays.asList("2", "3")),   // logical back: wide + ultra-wide
            new CameraSelector.Camera("1", false, Collections.<String>emptyList()),
            new CameraSelector.Camera("4", false, Collections.<String>emptyList()));

    private static String error(String id) {
        try {
            CameraSelector.select(CAMERAS, id);
        } catch (CameraSelector.SelectionException e) {
            return e.getMessage();
        }
        fail("expected an error for camera id " + id);
        return null;
    }

    @Test
    public void picksById() throws Exception {
        assertEquals("1", CameraSelector.select(CAMERAS, "1").id);
        assertEquals("4", CameraSelector.select(CAMERAS, "4").id);
    }

    @Test
    public void unknownIdIsAnErrorNeverAFallback() {
        String e = error("7");
        assertTrue(e, e.contains("camera id 7 not found"));
        assertTrue(e, e.contains("0, 1, 4"));
    }

    @Test
    public void noIdIsAnError() {
        assertTrue(error(null).startsWith("no camera id set"));
        assertTrue(error("").startsWith("no camera id set"));
    }

    @Test
    public void logicalMultiCameraIsRefusedNamingItsLenses() {
        String e = error("0");
        assertTrue(e, e.contains("logical multi-camera"));
        assertTrue(e, e.contains("2, 3"));
    }

    @Test
    public void noCamerasAtAll() {
        try {
            CameraSelector.select(Collections.<CameraSelector.Camera>emptyList(), "0");
            fail();
        } catch (CameraSelector.SelectionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cameras: none"));
        }
    }
}
