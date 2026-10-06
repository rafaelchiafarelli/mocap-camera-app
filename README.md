# mocap-camera-app

The Android app (Java) that turns a tablet or phone into a Mocap Studio
**STREAM** camera.

## Where it sits in the architecture

```
tablet (this app) ──H.264 + capture time in SEI (stream protocol v1, Wi-Fi)──▶ recorder PC (mocap-capture)
                  ◀──Harpia ZeroMQ: control requests / replies, stats──────────▶
```

- **Video:** streams hardware-encoded H.264 live to the recorder (raw Annex-B
  over HTTP). Every frame carries its capture time in an SEI, and a UDP clock
  sync lets the recorder map those times onto its own clock. The device
  never records internally.
- **Control:** exposes **every** Camera2 control the device offers
  (exposure, ISO, luminance compensation, focus and auto focus, white
  balance, …) to the recorder. It applies any of them on request and reports
  the value actually read back from the camera.
- **Status:** device info and per-second stats (fps, drops, temperature,
  thermal state) go to the recorder.
- Keeps streaming with the screen off and through battery savers and reboots.

Every wire format it speaks is defined in `mocap-contracts`: stream protocol
v1 as a spec with fixtures, `camera.harpia` generated for Java. This repo
never defines a format of its own.

It starts from the evaluation app in `mocap-studio/camera-stream-eval`, which
stays as the tool that decides whether a device is good enough.

## Build and install

Only Docker is needed on the host; the toolchain (JDK 17, Android SDK 34,
Gradle 8.7, adb) is a Docker image.

```bash
scripts/install-deps.sh    # check Docker, build the toolchain image
scripts/build.sh           # JVM unit tests, then the debug APK
scripts/install-app.sh     # install on the connected tablet and start it
```

The generated messages come from the `third_party/mocap-contracts` submodule
(pinned to a tag); `./dev contracts` initialises it if needed and builds its
jar. Its own nested Harpia submodule is not needed: the generated Java is
committed in `mocap-contracts`.

`./dev` has the individual steps (`./dev help`).

### Tablet setup (once per device)

The app starts by itself when the tablet boots. For that:

- Screen lock **None** or **Swipe** (with a PIN, Android holds the boot event
  until the tablet is unlocked).
- **Allwinner tablets (e.g. Multilaser M7):** the vendor's process limiter
  (Awbms) blocks boot broadcasts and services of apps it doesn't list, and
  adb can't change its list. Open it with
  `./dev adb shell am start -n com.softwinner.awmanager/.AwManager` and set
  *Mocap Camera* to allowed (*Permitida*). Check with
  `./dev adb shell dumpsys background whitelist`.
- Wireless debugging turns itself off on every reboot (Android behaviour); the
  app doesn't need it. Under WSL2 a USB tablet must
first be attached from Windows with `usbipd attach --wsl --busid <BUSID>`.

## Status

In progress: the bootstrap epic is done. The app builds, behaves like the eval
app, and compiles against the generated `camera.harpia` messages.
Plan: [`initiatives/baseline/`](initiatives/baseline/baseline.md). Architecture:
`mocap-studio/HANDOFF.md`.
