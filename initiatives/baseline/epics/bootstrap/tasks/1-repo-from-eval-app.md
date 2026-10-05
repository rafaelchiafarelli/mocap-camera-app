## 1. Repo from the eval app

- **Depends on:** —
- **Contract:**
  - In: `mocap-studio/camera-stream-eval/app` (about 1,500 lines of Java) and its Docker build
  - Requires: package renamed to `dev.mocapstudio.camera`; Docker build (JDK 17, Android SDK 34, Gradle) with a `./dev build` / `./dev test` entry point; base image pinned by digest (`mocap-studio/DEPENDENCIES.md` rule B)
  - Delivers: the app builds into an APK from a clean clone, behaving like the eval app; a JVM unit-test suite runs (first tests: the pure classes `TimestampSei`, `SensorClock`)
- **Pre-work:** none
- **Out of scope:** behaviour changes (later tasks); the evaluation scripts, which stay in `camera-stream-eval`
- **Tests:** `./dev test` green; the APK builds
