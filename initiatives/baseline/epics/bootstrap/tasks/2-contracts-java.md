## 2. Generated contracts wired in

- **Depends on:** 1; `mocap-contracts` camera-protocol camera-messages/2 (Java generation)
- **Contract:**
  - In: `mocap-contracts` at a tag, as a submodule (`third_party/mocap-contracts`)
  - Requires: its `gen/java/` Gradle project included in the build; no hand-written copies of any message
  - Delivers: the app compiles against the generated `camera.harpia` messages
- **Pre-work:** none
- **Out of scope:** using them over ZeroMQ (control/1)
- **Tests:** a unit test builds a `ControlReply` and reads it back
