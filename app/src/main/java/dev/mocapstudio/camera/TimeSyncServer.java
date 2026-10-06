package dev.mocapstudio.camera;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

/**
 * UDP time server for NTP-style offset estimation against the sensor clock.
 * Request: any datagram (first 8 bytes echoed back as a token).
 * Reply (24 bytes, big-endian): token, sensor-clock ns at receive, tablet Unix ns at receive.
 */
final class TimeSyncServer {
    private static final String TAG = "MocapCamera";

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

    private void loop() {
        byte[] in = new byte[64];
        byte[] out = new byte[24];
        DatagramPacket req = new DatagramPacket(in, in.length);
        while (running) {
            try {
                req.setLength(in.length);
                socket.receive(req);
                long sensorNs = SensorClock.now();
                long unixNs = sensorNs + SensorClock.unixOffsetNs();
                ByteBuffer b = ByteBuffer.wrap(out);
                b.put(in, 0, 8);
                b.putLong(sensorNs);
                b.putLong(unixNs);
                socket.send(new DatagramPacket(out, out.length, req.getSocketAddress()));
            } catch (IOException e) {
                if (running) Log.w(TAG, "time sync", e);
            }
        }
    }
}
