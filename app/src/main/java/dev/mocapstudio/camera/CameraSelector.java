package dev.mocapstudio.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Picks the camera to stream by its Camera2 id, and only by it: the id is declared (settings, set by the
 * recorder or at install), never guessed. An unknown id, no id, or a logical multi-camera is an error, never a
 * fallback to another camera. A logical multi-camera is refused because the device may switch lenses inside
 * it, silently changing the intrinsics; its physical lens ids are named in the error.
 */
final class CameraSelector {

    /** What the selection needs to know about one camera. */
    static final class Camera {
        final String id;
        final boolean logical;
        final List<String> physicalIds;

        Camera(String id, boolean logical, List<String> physicalIds) {
            this.id = id;
            this.logical = logical;
            this.physicalIds = physicalIds;
        }
    }

    static final class SelectionException extends Exception {
        SelectionException(String message) {
            super(message);
        }
    }

    private CameraSelector() { }

    static Camera select(List<Camera> cameras, String id) throws SelectionException {
        List<String> ids = new ArrayList<>();
        for (Camera c : cameras) ids.add(c.id);
        if (id == null || id.isEmpty()) {
            throw new SelectionException("no camera id set (set camera_id; cameras: " + join(ids) + ")");
        }
        for (Camera c : cameras) {
            if (!c.id.equals(id)) continue;
            if (c.logical) {
                throw new SelectionException("camera " + id + " is a logical multi-camera (physical lenses "
                        + join(c.physicalIds) + "): not supported, pick a single camera");
            }
            return c;
        }
        throw new SelectionException("camera id " + id + " not found (cameras: " + join(ids) + ")");
    }

    private static String join(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "none";
        List<String> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        return String.join(", ", sorted);
    }
}
