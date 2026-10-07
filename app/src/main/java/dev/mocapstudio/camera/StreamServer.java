package dev.mocapstudio.camera;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The video transport of stream protocol v1 (mocap-contracts docs/stream-protocol-v1.md §2), and nothing else:
 *   /h264.raw     H.264 Annex-B, codec config first, then access units from a key frame, each one the
 *                 timestamp SEI + the frame's NALs. Watch live with: ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext -vf setpts=0 -f h264 http://host:8080/h264.raw
 * Control, device info and stats go over ZeroMQ (ControlChannel).
 */
final class StreamServer {
    private static final String TAG = "MocapCamera";
    private static final long SLOW_WRITE_NS = 200_000_000L;

    private final int port;
    private final Stats stats;
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final Object frameLock = new Object();

    private volatile Runnable keyFrameRequester = () -> { };
    private ServerSocket serverSocket;
    private volatile boolean running;

    private byte[] codecConfig;
    private byte[] frame;
    private long frameSeq;
    private boolean frameKey;

    StreamServer(int port, Stats stats) {
        this.port = port;
        this.stats = stats;
    }

    void setKeyFrameRequester(Runnable r) {
        keyFrameRequester = r;
    }

    void start() throws IOException {
        // After a /control restart the previous socket may not have released the port yet.
        for (int attempt = 0; ; attempt++) {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            try {
                serverSocket.bind(new InetSocketAddress(port));
                break;
            } catch (IOException e) {
                serverSocket.close();
                if (attempt >= 30) throw e;
                try { Thread.sleep(100); } catch (InterruptedException ie) { throw e; }
            }
        }
        running = true;
        Thread t = new Thread(this::acceptLoop, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (Socket s : sockets) {
            try { s.close(); } catch (IOException ignored) { }
        }
        synchronized (frameLock) { frameLock.notifyAll(); }
    }

    boolean hasCodecConfig() {
        synchronized (frameLock) { return codecConfig != null; }
    }

    void setCodecConfig(byte[] config) {
        synchronized (frameLock) {
            codecConfig = config;
            frameLock.notifyAll();
        }
    }

    /** Called by the encoder for every access unit (timestamp SEI + the frame's NALs). */
    void publish(byte[] accessUnit, boolean key) {
        synchronized (frameLock) {
            frame = accessUnit;
            frameSeq++;
            frameKey = key;
            frameLock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                sockets.add(s);
                Thread t = new Thread(() -> handle(s), "http-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* skip headers */ }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            OutputStream out = s.getOutputStream();
            if ("/h264.raw".equals(path)) stream(out);
            else text(out, "404 Not Found", "only /h264.raw (stream protocol v1)\n");
        } catch (IOException | InterruptedException ignored) {
            // client went away
        } finally {
            sockets.remove(s);
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void stream(OutputStream out) throws IOException, InterruptedException {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: video/h264\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        stats.clients.incrementAndGet();
        try {
            byte[] cfg;
            synchronized (frameLock) {
                long deadline = System.currentTimeMillis() + 3000;
                while (running && codecConfig == null && System.currentTimeMillis() < deadline) frameLock.wait(500);
                cfg = codecConfig;
            }
            if (cfg == null) return;
            out.write(cfg);
            out.flush();
            boolean needKey = true;
            keyFrameRequester.run();

            long lastSeq = 0;
            while (running) {
                byte[] data;
                long seq;
                boolean key;
                synchronized (frameLock) {
                    while (running && frameSeq == lastSeq) frameLock.wait(1000);
                    if (!running) return;
                    data = frame; seq = frameSeq; key = frameKey;
                }
                if (lastSeq != 0 && seq > lastSeq + 1) {
                    stats.clientSkippedFrames.addAndGet(seq - lastSeq - 1);
                    if (!needKey) {  // a gap breaks the H.264 reference chain
                        needKey = true;
                        keyFrameRequester.run();
                    }
                }
                lastSeq = seq;
                if (needKey && !key) continue;
                needKey = false;

                long t0 = System.nanoTime();
                out.write(data);
                out.flush();
                long blocked = System.nanoTime() - t0;
                if (blocked > SLOW_WRITE_NS) {
                    stats.slowWrites.incrementAndGet();
                    Log.w(TAG, "slow write " + blocked / 1_000_000 + " ms at frame " + seq);
                }
                stats.recordWrite(blocked);
                stats.sentFrames.incrementAndGet();
            }
        } finally {
            stats.clients.decrementAndGet();
        }
    }

    private static void text(OutputStream out, String status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.0 " + status + "\r\nContent-Type: text/plain\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
