## 2. Camera2 manual controls

- **Depends on:** 1; streaming/3
- **Contract:**
  - In: the `CameraControls` part of a `ControlRequest` (exposure time, ISO, focus distance, white balance, AE/AF lock)
  - Requires: `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY`, `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`, AWB lock or manual gains, where the hardware level allows. The values **read back from the capture results** are what's reported.
  - Delivers: `ControlReply` with the controls actually applied, and every unsupported control listed by name, never silently ignored. This is what `mocap-capture` studio-setup cameras/3 consumes.
- **Pre-work:** check the Camera2 hardware level and `MANUAL_SENSOR` capability of each tablet in hand (`camera-stream-eval` `device.json`)
- **Out of scope:** choosing the values (studio setup)
- **Tests:** JVM with a fake capture result: the reply reports read-back values; on a `LIMITED` device without `MANUAL_SENSOR`, exposure is listed as unsupported
