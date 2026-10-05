## 1. ZeroMQ control channel

- **Depends on:** bootstrap/2; `mocap-contracts` camera-protocol camera-messages/3 (**blocked** on Harpia's ZeroMQ docs)
- **Contract:**
  - In: `ControlRequest` from the recorder
  - Requires: the generated Java/JeroMQ endpoints only; the HTTP `/control`, `/info`, `/stats` of the eval app are removed once this works
  - Delivers: `ControlRequest` → apply → `ControlReply`; `DeviceInfo` on request; `CameraStats` every second
- **Pre-work:** none beyond the dependency
- **Out of scope:** manual exposure/focus (task 2); this task applies the stream settings (size, fps, bitrate, camera)
- **Tests:** Python ↔ Java loopback against the generated Python endpoint
