# baseline — epics (execution order)

1. **bootstrap** — Repo from the eval app, Docker build, JVM tests; generated contracts wired in (2 tasks)
2. **streaming** — Protocol v1, foreground service, camera selection and orientation (3 tasks)
3. **control** — ZeroMQ control channel; every Camera2 control discovered, applied and read back (3 tasks)

An epic only merges up into `epics` once all its tasks are `-done` and the suite is green.
