## 2. Every Camera2 control, discovered

- **Depends on:** 1; streaming/3
- **Contract:**
  - In: the camera chosen by id
  - Requires:
    - **Every** key in `CameraCharacteristics.getAvailableCaptureRequestKeys()` is exposed (Rafael, 2026-10-05): AE/AF/AWB modes and locks, exposure time, ISO, exposure compensation (luminance), focus distance, white balance gains/mode, noise reduction, edge, tonemap, flash, scene/effect modes, and so on
    - each control's type, range and options come from the matching characteristic (`SENSOR_INFO_EXPOSURE_TIME_RANGE`, `CONTROL_AE_COMPENSATION_RANGE`/`_STEP`, `CONTROL_AF_AVAILABLE_MODES`, `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, …)
    - a key whose range the device doesn't report is still listed, marked range-unknown, never guessed
  - Delivers: `DeviceInfo` with a `ControlCapability` for every settable key, each with its current value from the latest capture result
- **Pre-work:** `device.json` from `camera-stream-eval` for each tablet in hand (hardware level, `MANUAL_SENSOR` capability)
- **Out of scope:** applying values (task 3)
- **Tests:** JVM with fake characteristics: every available key is listed; ranges and menus come out right; a key without a reported range is listed as range-unknown
