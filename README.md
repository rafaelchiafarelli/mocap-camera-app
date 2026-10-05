# mocap-camera-app

The Android app (Java) that turns a tablet or phone into a Mocap Studio
**STREAM** camera. It streams H.264 live to the recorder PC with each frame's
capture time embedded (stream protocol v1), answers clock syncs, and takes
its settings, including locked manual exposure/focus/white balance, from the
recorder over Harpia ZeroMQ.

It starts from the evaluation app in `mocap-studio/camera-stream-eval`, which
stays as the device-evaluation tool. Its contracts (stream protocol v1,
`camera.harpia`) live in `mocap-contracts`; this repo never defines a wire
format of its own.

Plan and progress: `initiatives/`.
# mocap-camera-app
