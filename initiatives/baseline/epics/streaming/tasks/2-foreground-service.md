## 2. Foreground service

- **Depends on:** 1
- **Contract:**
  - In: the camera + encoder + servers pipeline, today run from `MainActivity`
  - Requires: the pipeline runs in a foreground `Service` (`foregroundServiceType="camera"`, `FOREGROUND_SERVICE_CAMERA` on Android 14+); it resumes after a reboot or a crash with the last settings
  - Delivers: streaming that keeps going with the screen off and under OEM battery savers
- **Pre-work:** list the Android versions of the tablets in hand (`mocap-studio` hardware inventory)
- **Out of scope:** —
- **Tests:** JVM: the settings store survives a restart. On a device (manual, recorded in the task): 20 min with the screen off and no lost stream.
