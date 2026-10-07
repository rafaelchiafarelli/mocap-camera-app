## 1. ZeroMQ control channel

- **Depends on:** bootstrap/2; `mocap-contracts` camera-protocol camera-messages/3, and **`v0.3.0`** (`ControlRequest.reply_endpoint`, fixes/000018 there)
- **Contract:**
  - In: `ControlRequest` from the recorder
  - Requires: the generated Java/JeroMQ endpoints only (Harpia USAGE §7.6): `newReceiver` binds the app's control port, `newSender` connects to the request's `reply_endpoint` (where the recorder's `ControlReply` receiver listens; nothing declared it before v0.3.0), `newPublisher` binds the stats port. `receive()` blocks, so it runs on its own thread with a receive timeout. The HTTP `/control`, `/info`, `/stats` of the eval app are removed once this works.
  - Ports and serial are app settings (Rafael, 2026-10-06): `video_port`, `sync_port`, `control_port`, `stats_port` (defaults 8080–8083, the values the recorder's `config.yaml` declares per camera), and the device `serial` (the one adb reports, `ro.serialno`), declared at install by `./dev launch`. A request for another serial is answered with a problem and not handled.
  - Delivers: `ControlRequest` → apply → `ControlReply`; `DeviceInfo` on request; `CameraStats` every second
- **Pre-work:** none beyond the dependency
- **Out of scope:** camera controls (tasks 2, 3): each one is answered `UNSUPPORTED` until then; this task applies the stream settings (camera id, size, fps, bitrate, I-frame interval)
- **Tests:** Python ↔ Java loopback against the generated Python endpoint

### Device check (2026-10-06)

Multilaser M7, over Wi-Fi adb with `adb forward` (control, stats) and `adb reverse` (reply):
`tests/loopback/recorder.py` (the generated Python endpoints) against the installed app passed every check:
the reply to its request from this serial, `stream_applied` (pipeline restarted at the requested 20 Mbps),
one result per setting, `DeviceInfo` valid under the contract rules, a request for another serial not
handled, `CameraStats` from this serial. The stream kept its SEIs (182 frames in 6.6 s, 2 seq gaps at the
restart), 0 dropped frames, thermal 0.
