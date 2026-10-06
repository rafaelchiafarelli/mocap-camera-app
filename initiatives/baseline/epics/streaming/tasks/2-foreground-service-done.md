## 2. Foreground service

- **Depends on:** 1
- **Contract:**
  - In: the camera + encoder + servers pipeline, today run from `MainActivity`
  - Requires: the pipeline runs in a foreground `Service` (`foregroundServiceType="camera"`, `FOREGROUND_SERVICE_CAMERA` on Android 14+); it resumes after a reboot or a crash with the last settings
  - Delivers: streaming that keeps going with the screen off and under OEM battery savers
- **Pre-work:** list the Android versions of the tablets in hand (`mocap-studio` hardware inventory)
- **Out of scope:** —
- **Tests:** JVM: the settings store survives a restart. On a device (manual, recorded in the task): 20 min with the screen off and no lost stream.

### Device check (2026-10-05)

Multilaser M7 (Android 13, SDK 33), camera 0, 1600×1200 @ 30 fps, 20 Mbps,
screen off (`mWakefulness=Dozing`) the whole time, measured from Windows with
`camera-stream-eval/scripts/measure.py --seconds 1200` over `/h264.raw`:

- the stream ran the full 1199.9 s, never ended early
- 35,870 frames, 29.90 fps capture rate; 7 lost (0.02 %, seq jumps on the
  network side: 0 dropped on the device, 0 slow sends), longest gap 334 ms
- 35,870 timestamp SEIs, 0 missing or mismatched
- capture→PC latency median 43.6 ms, p95 57.4 ms; drift −21.5 ppm
- thermal status 0, 28.6–29.6 °C

Start at boot also checked: after `adb reboot` the app streamed by itself,
once Mocap Camera was allowed in the Allwinner process limiter (README,
*Tablet setup*).
