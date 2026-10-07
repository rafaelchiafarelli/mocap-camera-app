# baseline — mocap-camera-app

**Goal:** A production STREAM camera app. It streams protocol v1 to the recorder, keeps streaming through screen-off, battery savers and reboots, exposes **every** Camera2 control the device offers to the recorder, applies any of them on command, and reports what it actually applied.

**Scope:**
- Java, Camera2, MediaCodec hardware H.264, **`minSdk 24`**: Harpia's verified Android configuration for its Java output; the M7 tablet runs SDK 33 (`mocap-contracts` camera-messages/2, decision for Rafael's review)
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

## Gate result (2026-10-06): met, with the M7's limits accepted

On the Multilaser M7 (the only STREAM tablet; Rafael keeps it unless it proves really bad, 2026-10-06):

- **Quick run** (`camera-stream-eval/run-eval.sh --app camera --quick usb`, fixes/000026 there; report in
  `camera-stream-eval/results/M7_WIFI-200138-20261006-231457/`): verdict **WARN** (10 pass, 2 warn, 0 fail).
  1600×1200 at 10 and 20 Mbps and a 2 min soak at 20 Mbps: 29.9 fps, 0 frames lost, capture→PC median
  42–49 ms; clock sync best RTT ≈ 2.2 ms, drift ≈ −22 ppm.
- **Controls locked through the ZeroMQ channel:** `aeTargetFpsRange` [30, 30] APPLIED and read back. AE lock
  and AWB lock are the two WARNs: the M7's HAL doesn't take them (read back OFF), and it has no manual
  exposure or focus at all (control/2, /3). So on the M7 "locked controls" means the fps range only; its
  exposure follows the scene. Accepted by Rafael for now; a tablet that takes them would pass clean.
- **Protocol v1:** the sample stream read with the `mocap-contracts` reference reader: 298/298 frames timed,
  no gaps, no problems.
- **20 min with the screen off:** streaming/2 (0 dropped on the device, 7/35,870 lost on the network,
  never ended).
