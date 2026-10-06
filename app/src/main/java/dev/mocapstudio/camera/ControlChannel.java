package dev.mocapstudio.camera;

import com.harpia.generated.CameraStats;
import com.harpia.generated.ControlReply;
import com.harpia.generated.ControlRequest;
import com.harpia.generated.zmq.CameraStats_zmq;
import com.harpia.generated.zmq.ControlReply_zmq;
import com.harpia.generated.zmq.ControlRequest_zmq;
import com.harpia.runtime.zmq.HarpiaZmq;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.zeromq.ZContext;

/**
 * The control channel to the recorder over Harpia ZeroMQ (mocap-contracts camera.harpia), with the generated
 * JeroMQ endpoints only (Harpia USAGE §7.6):
 * - ControlRequest: a receiver (PULL) bound on the control port; receive() blocks, so it runs on its own thread
 *   with a receive timeout.
 * - ControlReply: a sender (PUSH) connected to the request's reply_endpoint, where the recorder's receiver
 *   listens (one sender per endpoint, kept).
 * - CameraStats: a publisher (PUB) bound on the stats port; publish() is called from one other thread.
 *
 * A request for another serial is answered with a problem and not handled. Plain Java (no Android API), so the
 * loopback test runs it on the JVM.
 */
final class ControlChannel {
    interface Handler {
        /** Builds the reply to a request for this device; request_id and serial are filled in afterwards. */
        ControlReply.Builder handle(ControlRequest request);
    }

    interface Log {
        void warn(String message, Throwable t);
    }

    private static final Pattern TCP_ENDPOINT = Pattern.compile("^tcp://[^:/\\s*]+:\\d{1,5}$");
    private static final int RECEIVE_TIMEOUT_MS = 500;

    private final String serial;
    private final int controlPort, statsPort;
    private final Handler handler;
    private final Log log;
    private final Map<String, HarpiaZmq.Sender> replySenders = new HashMap<>();

    private ZContext ctx;
    private HarpiaZmq.Receiver requests;
    private HarpiaZmq.Sender stats;
    private Thread thread;
    private volatile boolean running;

    ControlChannel(String serial, int controlPort, int statsPort, Handler handler, Log log) {
        this.serial = serial == null ? "" : serial;
        this.controlPort = controlPort;
        this.statsPort = statsPort;
        this.handler = handler;
        this.log = log;
    }

    void start() {
        ctx = new ZContext();
        ctx.setLinger(0);
        requests = ControlRequest_zmq.newReceiver(ctx, "tcp://*:" + controlPort);
        requests.socket().setReceiveTimeOut(RECEIVE_TIMEOUT_MS);
        stats = CameraStats_zmq.newPublisher(ctx, "tcp://*:" + statsPort);
        running = true;
        thread = new Thread(this::loop, "control-channel");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(2 * RECEIVE_TIMEOUT_MS); } catch (InterruptedException ignored) { }
        }
        if (ctx != null) ctx.close();
    }

    /** Publishes one CameraStats. Call from a single thread; nothing is sent without a serial. */
    void publish(CameraStats.Builder s) {
        if (!running || serial.isEmpty()) return;
        stats.send(s.setSerial(serial).build());
    }

    private void loop() {
        while (running) {
            ControlRequest.Builder in = ControlRequest.newBuilder();
            boolean got;
            try {
                got = requests.receive(in);
            } catch (RuntimeException e) {
                if (running) log.warn("control receive failed", e);
                continue;
            }
            if (!got) continue;  // timeout, or a frame that didn't parse
            ControlRequest request = in.build();
            ControlReply reply = answer(request);
            String endpoint = request.getReplyEndpoint();
            if (!TCP_ENDPOINT.matcher(endpoint).matches()) {
                log.warn("request " + request.getRequestId() + ": no usable reply_endpoint '" + endpoint + "'", null);
                continue;
            }
            HarpiaZmq.Sender tx = replySenders.get(endpoint);
            if (tx == null) {
                tx = ControlReply_zmq.newSender(ctx, endpoint);
                replySenders.put(endpoint, tx);
            }
            if (!tx.send(reply)) log.warn("reply to " + endpoint + " not sent", null);
        }
    }

    /** The reply to one request; handles it only if it is for this device. */
    ControlReply answer(ControlRequest request) {
        ControlReply.Builder out;
        if (serial.isEmpty()) {
            out = ControlReply.newBuilder().addProblems("this app has no serial set (install with ./dev install)");
        } else if (!serial.equals(request.getSerial())) {
            out = ControlReply.newBuilder().addProblems(
                    "request for serial " + request.getSerial() + ", this device is " + serial + ": not handled");
        } else {
            try {
                out = handler.handle(request);
            } catch (RuntimeException e) {
                log.warn("handling request " + request.getRequestId() + " failed", e);
                out = ControlReply.newBuilder().addProblems("handling failed: " + e);
            }
        }
        return out.setRequestId(request.getRequestId()).setSerial(serial.isEmpty() ? "unknown" : serial).build();
    }
}
