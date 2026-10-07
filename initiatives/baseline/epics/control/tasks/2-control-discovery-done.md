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

### Device check (2026-10-06)

Multilaser M7 (LIMITED, `BACKWARD_COMPATIBLE` only, no `MANUAL_SENSOR`), `DeviceInfo` requested over the
control channel and checked under the contract rules: camera 0 lists **40** controls, one per available
request key, each with its current value from the latest capture result. What the device offers: AE mode
`ON` only (no manual exposure), AF `OFF` only (fixed focus), ISO 400–400, exposure compensation −4…4 in
½ EV steps, AWB `OFF`/`AUTO`, fps ranges `[6, 30]` and `[30, 30]`. Its HAL reports values passed on as
reported, never corrected: exposure time range 250 ms … 7.5·10¹⁵ ns and a current exposure of
4,755,750,000,000 ns (wrong units in the driver), noise reduction options `OFF, OFF`, test pattern mode 5
(not an API name: listed by number).
