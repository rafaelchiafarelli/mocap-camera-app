## 2. Generated contracts wired in

- **Depends on:** 1; `mocap-contracts` **v0.2.0** (Java generation, done)
- **Contract:**
  - In: `mocap-contracts` at tag `v0.2.0`, as a submodule (`third_party/mocap-contracts`)
  - Requires: its `gen/java/` Gradle project included in the build, consuming only the subset Harpia documents for Android (message classes, JSON, the JeroMQ client; never its DB/REST/SOAP/server parts); `minSdk 24`; no hand-written copies of any message
  - Delivers: the app compiles against the generated `camera.harpia` messages
- **Pre-work:** none
- **Out of scope:** using them over ZeroMQ (control/1)
- **Tests:** a unit test builds a `ControlReply` and reads it back
