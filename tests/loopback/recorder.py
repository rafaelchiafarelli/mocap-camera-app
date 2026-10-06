"""The recorder side of the control loopback test (control/1).

Plays mocap-capture against the app's ControlChannel with nothing but the generated Python endpoints of
mocap-contracts (mocap_contracts.transport): binds its ControlReply receiver, sends ControlRequests to the
app's control port, subscribes to its CameraStats. Every message the app sends must pass the contract rules
(to_json/from_json). Run by ControlChannelLoopbackTest; exits 0 when every check passed.

usage: recorder.py <control-port> <stats-port> <reply-port> <serial>
"""

import sys

import zmq

import mocap_contracts as mc
from mocap_contracts import transport

TIMEOUT_MS = 20000


def check(cond, what):
    if not cond:
        raise SystemExit(f"FAIL: {what}")
    print(f"ok: {what}", flush=True)


def valid(cls, msg):
    """The message passes the contract rules both ways."""
    return mc.from_json(cls, mc.to_json(msg))


def main():
    control_port, stats_port, reply_port, serial = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    ctx = zmq.Context()
    replies = transport.new_receiver(mc.ControlReply, ctx, f"tcp://127.0.0.1:{reply_port}")
    replies.socket.rcvtimeo = TIMEOUT_MS
    stats = transport.new_subscriber(mc.CameraStats, ctx, f"tcp://127.0.0.1:{stats_port}")
    stats.socket.rcvtimeo = TIMEOUT_MS
    requests = transport.new_sender(mc.ControlRequest, ctx, f"tcp://127.0.0.1:{control_port}")
    reply_endpoint = f"tcp://127.0.0.1:{reply_port}"

    # 1. stream settings + a camera control + device info
    request = mc.ControlRequest(
        request_id="loop-1", serial=serial, reply_endpoint=reply_endpoint,
        stream=mc.StreamSettings(camera_id="0", width=1600, height=1200, fps=30.0, bitrate_kbps=20000,
                                 i_frame_interval_s=1.0),
        settings=[mc.ControlSetting(key="android.sensor.exposureTime", value=mc.ControlValue(int_value=8_000_000))],
        want_device_info=mc.Flag.Value("FLAG_ON"),
    )
    check(requests.send(valid(mc.ControlRequest, request)), "request 1 sent")
    reply = replies.recv()
    check(reply is not None, "reply 1 received")
    reply = valid(mc.ControlReply, reply)
    check((reply.request_id, reply.serial) == ("loop-1", serial), "reply 1 answers request 1 from this serial")
    check(reply.stream_applied.camera_id == "0" and reply.stream_applied.width == 1600, "stream_applied reported")
    check([r.key for r in reply.results] == ["android.sensor.exposureTime"], "one result per requested setting")
    check(reply.results[0].status != mc.ControlStatus.Value("CONTROL_STATUS_UNSET"), "result has a status")
    check(reply.HasField("device_info") and reply.device_info.serial == serial, "device_info sent on request")

    # 2. a request for another device is answered with a problem, not handled
    other = mc.ControlRequest(request_id="loop-2", serial="not-" + serial, reply_endpoint=reply_endpoint,
                              want_device_info=mc.Flag.Value("FLAG_OFF"))
    check(requests.send(other), "request 2 sent")
    reply = replies.recv()
    check(reply is not None and reply.request_id == "loop-2", "reply 2 received")
    check(not reply.results and not reply.HasField("stream_applied") and reply.problems, "other serial not handled")

    # 3. stats, every second
    got = stats.recv()
    check(got is not None, "CameraStats received")
    got = valid(mc.CameraStats, got)
    check(got.serial == serial, "CameraStats from this serial")

    ctx.destroy(linger=0)
    print("LOOPBACK OK", flush=True)


if __name__ == "__main__":
    main()
