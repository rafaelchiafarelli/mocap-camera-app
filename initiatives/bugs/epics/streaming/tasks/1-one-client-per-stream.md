## 1. The app seems to serve one client per stream

- **Symptom:** a live view open on a device gets in the way of a take.
- **Where:** not located; the stream is served from
  `app/src/main/java/dev/mocapstudio/camera/StreamingService.java`.
- **Reproduce:** seen on 2026-10-12 in real takes. Not confirmed by reading the code.
- **Impact:** a live view and a take can't share a device, which matters for the
  live director monitor. The workaround is to close any live view before a take.
- **Status:** Open
