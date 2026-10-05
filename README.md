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

## Status

Planned, no code yet. Built in Docker (JDK 17, Android SDK 34, Gradle).
Plan: [`initiatives/baseline/`](initiatives/baseline/baseline.md). Architecture:
`mocap-studio/HANDOFF.md`.
