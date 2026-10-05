# baseline — mocap-camera-app

**Goal:** A production STREAM camera app. It streams protocol v1 to the recorder, keeps streaming through screen-off, battery savers and reboots, locks exposure/ISO/focus/white balance on command, and reports what it actually applied.

**Scope:**
- Java, Camera2, MediaCodec hardware H.264, `minSdk` as in the eval app (21) unless the generated Java contracts need more (`mocap-contracts` camera-protocol camera-messages/2)
- built in Docker (JDK 17, Android SDK 34, Gradle), like `camera-stream-eval`
- control, info and stats over Harpia ZeroMQ, using the generated Java messages from `mocap-contracts`

**Out of scope:**
- the recorder side (`mocap-capture` devices/5)
- recording on the device (STREAM only)
- choosing which device is which role (declared in the recorder's `config.yaml`)
- a director view on the tablet
- HEVC

**Initiative gate:** with locked controls applied through the ZeroMQ channel, a tablet passes `camera-stream-eval`'s quick run, its stream matches protocol v1 (checked with the reference reader from `mocap-contracts`), and the stream survives 20 min with the screen off.

General context, cross-repository order and open questions:
`mocap-studio/HANDOFF.md`.
