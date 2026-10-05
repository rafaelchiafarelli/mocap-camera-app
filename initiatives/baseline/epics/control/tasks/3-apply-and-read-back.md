## 3. Apply any control, report what was applied

- **Depends on:** 2
- **Contract:**
  - In: the `ControlSetting`s of a `ControlRequest`
  - Requires:
    - each setting is validated against its capability and put on the repeating `CaptureRequest`
    - dependent modes are handled explicitly: a manual exposure needs `CONTROL_AE_MODE_OFF`, a manual focus needs `CONTROL_AF_MODE_OFF`. The app never flips a mode on its own: it reports a mode conflict unless the request sets the mode too.
    - the value reported is the one **read back from the `CaptureResult`**
  - Delivers: a `ControlResult` per setting: APPLIED, CLAMPED (with the read-back value), UNSUPPORTED, READ_ONLY or FAILED (e.g. mode conflict). This is what `mocap-capture` studio-setup cameras/3 consumes. Settings persist across app restarts (streaming/2).
- **Pre-work:** none
- **Out of scope:** choosing values (studio setup)
- **Tests:** JVM with a fake camera: read-back values reported; an out-of-range value is clamped or refused per the capability; manual exposure without AE off is a reported conflict; on a `LIMITED` device without `MANUAL_SENSOR`, exposure time is UNSUPPORTED
