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

### Device check (2026-10-06)

Multilaser M7 (LIMITED, no `MANUAL_SENSOR`), one `ControlRequest` over the control channel, reply checked
under the contract rules, values read back from the 5th capture result made with them:

| setting | result |
|---|---|
| `aeTargetFpsRange` [30, 30] | APPLIED, read back [30, 30] |
| `aeExposureCompensation` 10 | CLAMPED to the range [−4, 4]; read back **0** |
| `aeLock` ON, `awbLock` ON | **FAILED**: read back OFF |
| `sensor.exposureTime` 8 ms | UNSUPPORTED (no `MANUAL_SENSOR`) |
| `aeMode` OFF | UNSUPPORTED (offers `ON` only) |
| `sensor.timestamp` | READ_ONLY |
| `com.vendor.nope` | UNSUPPORTED |

So the M7's HAL lists exposure compensation and the AE/AWB locks but doesn't take them (or doesn't report
them): on this tablet nothing about exposure can be locked or set. The applied controls were saved and put
back on the request after an app restart; the stream kept its SEIs (194 frames in 6.5 s, no gaps).
