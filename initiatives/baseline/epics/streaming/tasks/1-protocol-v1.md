## 1. Stream protocol v1

- **Depends on:** bootstrap/1; `mocap-contracts` camera-protocol stream-protocol/1, /2
- **Contract:**
  - In: the spec `docs/stream-protocol-v1.md` and its fixtures
  - Requires: the app emits exactly v1 (video transport, SEI payload, clock sync reply) and nothing the spec doesn't define
  - Delivers: `TimestampSei`, `TimeSyncServer` and the stream server conform to v1. Anything v1 drops (e.g. the multipart `/stream`, if the spec drops it) is removed.
- **Pre-work:** none
- **Out of scope:** control (control epic)
- **Tests:** JVM: the SEI bytes for known values equal the fixture's; the sync reply bytes equal the fixture's
