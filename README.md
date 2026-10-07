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

### Tablet setup: every tablet, once

Do all of this on **each** tablet before it is used as a camera (found on
the first Multilaser M7, 2026-10-05/06). `T=<serial|ip:port>` picks the
tablet when several are connected.

1. **USB debugging on** (Developer options), and the PC authorised.
2. **No screen lock.** With a PIN, pattern or password Android keeps the
   app's storage locked after every reboot, so the camera stays off until
   someone unlocks it. Clear it with
   `./dev adb shell locksettings clear --old <PIN>` (or Settings → Security →
   Screen lock → None).
3. **Install and declare it:** `scripts/install-app.sh [camera_id=<id>]`. This
   grants the permissions, "display over other apps" (start at boot) and the
   battery-optimisation exemption, and saves the device serial and the
   camera id (default 0; the ids are in `DeviceInfo`).
4. **Allwinner tablets (e.g. Multilaser M7):** allow the app in the vendor's
   process limiter (Awbms), which otherwise drops its boot start and services
   and can't be changed over adb:
   `./dev adb shell am start -n com.softwinner.awmanager/.AwManager`, set
   *Mocap Camera* to *Permitida*, check with
   `./dev adb shell dumpsys background whitelist`.
5. **Check:** `./dev adb reboot`; the stream must come back by itself on
   `http://<ip>:8080/h264.raw` within about two minutes.
6. **Record it** in `mocap-studio` (hardware inventory): model, Android
   version, serial, camera ids, and what `DeviceInfo` says it can control
   (the M7 has no manual exposure or focus).

Notes:
- Wireless debugging turns itself off on every reboot (Android behaviour);
  the app doesn't need it. Big installs over USB/IP can drop the link: switch
  to Wi-Fi adb with `./dev adb tcpip 5555` and `./dev connect <ip>` (lasts until
  the next reboot).

### Settings

`./dev launch key=value ...` saves settings and the device serial (from adb):
`camera_id` (nothing streams until it is set; `install-app.sh` sets 0), `width`,
`height`, `fps`, `bitrate`, `i_frame_interval_s`, and the ports `video_port`,
`sync_port`, `control_port`, `stats_port` (8080–8083, as the recorder's
`config.yaml` declares them). The recorder changes the stream settings with a
`ControlRequest`.

## Interfaces

- `http://<tablet>:<video_port>/h264.raw`, UDP `<sync_port>`: stream protocol v1.
- ZeroMQ (`camera.harpia`, Harpia's generated endpoints): `ControlRequest` in on
  `<control_port>` (PULL), `ControlReply` out to the request's
  `reply_endpoint` (PUSH), `CameraStats` every second on `<stats_port>` (PUB). Under WSL2 a USB tablet must
first be attached from Windows with `usbipd attach --wsl --busid <BUSID>`.

## Status

In progress: the bootstrap and streaming epics are done. The app streams
stream protocol v1 from a foreground service that starts at boot, from the
camera declared by id, and the recorder controls it over ZeroMQ (control/1).
Next: every Camera2 control (control/2, /3).
Plan: [`initiatives/baseline/`](initiatives/baseline/baseline.md). Architecture:
`mocap-studio/HANDOFF.md`.
