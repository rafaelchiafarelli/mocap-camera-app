package dev.mocapstudio.camera;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

/**
 * The clock sync of stream protocol v1 (mocap-contracts docs/stream-protocol-v1.md §4): UDP probes answered
 * at once on the sensor clock. Request: at least 8 bytes, the first 8 an opaque token. Reply (24 bytes,
 * big-endian): token, sensor-clock ns at receive, device Unix ns at receive (informational).
 */
final class TimeSyncServer {
    private static final String TAG = "MocapCamera";

    static final int REPLY_BYTES = 24;

    private final int port;
    private DatagramSocket socket;
    private volatile boolean running;

    TimeSyncServer(int port) {
        this.port = port;
    }

    void start() throws IOException {
        socket = new DatagramSocket(port);
        running = true;
        Thread t = new Thread(this::loop, "time-sync");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    void stop() {
        running = false;
        if (socket != null) socket.close();
    }

    /** Fills the 24-byte reply into out; false (no reply) for a request shorter than the 8-byte token. */
    static boolean reply(byte[] request, int length, long sensorNs, long unixNs, byte[] out) {
        if (length < 8) return false;
        ByteBuffer.wrap(out).put(request, 0, 8).putLong(sensorNs).putLong(unixNs);
        return true;
    }

    private void loop() {
        byte[] in = new byte[64];
        byte[] out = new byte[REPLY_BYTES];
        DatagramPacket req = new DatagramPacket(in, in.length);
        while (running) {
            try {
                req.setLength(in.length);
                socket.receive(req);
                long sensorNs = SensorClock.now();
                if (!reply(in, req.getLength(), sensorNs, sensorNs + SensorClock.unixOffsetNs(), out)) continue;
                socket.send(new DatagramPacket(out, out.length, req.getSocketAddress()));
            } catch (IOException e) {
                if (running) Log.w(TAG, "time sync", e);
            }
        }
    }
}
