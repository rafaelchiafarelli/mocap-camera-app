## 3. Camera selection and orientation

- **Depends on:** 1
- **Contract:**
  - In: the device's Camera2 cameras
  - Requires: the camera is chosen by id (no more "first camera with this facing"); logical multi-cameras handled or refused explicitly; no pixel rotation
  - Delivers: `DeviceInfo` lists every camera with its id, facing, focal lengths, sensor orientation and hardware level, so the recorder knows how the image is oriented
- **Pre-work:** none
- **Out of scope:** —
- **Tests:** JVM with a fake camera list: picks by id; an unknown id is an error, never a fallback
