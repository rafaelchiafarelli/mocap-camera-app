package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.harpia.generated.CameraStats;
import com.harpia.generated.ControlRequest;
import com.harpia.generated.ControlReply;
import com.harpia.generated.ControlResult;
import com.harpia.generated.ControlSetting;
import com.harpia.generated.ControlStatus;
import com.harpia.generated.DeviceInfo;
import com.harpia.generated.Flag;
import com.harpia.generated.StreamSettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Python ↔ Java loopback: the app's ControlChannel against the generated Python endpoints of mocap-contracts
 * (tests/loopback/recorder.py, run with the toolchain image's /opt/loopback Python and the submodule on
 * PYTHONPATH). The handler is a fake camera; the channel, the wire and the contract are real.
 */
public class ControlChannelLoopbackTest {
    private static final String SERIAL = "10023CF18200138";

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ControlReply.Builder fakeCamera(ControlRequest r) {
        ControlReply.Builder out = ControlReply.newBuilder();
        if (r.hasStream()) out.setStreamApplied(r.getStream());
        for (ControlSetting s : r.getSettingsList()) {
            out.addResults(ControlResult.newBuilder().setKey(s.getKey()).setRequested(s.getValue())
                    .setStatus(ControlStatus.CONTROL_STATUS_UNSUPPORTED).setNote("fake camera"));
        }
        if (r.getWantDeviceInfo() == Flag.FLAG_ON) {
            out.setDeviceInfo(DeviceInfo.newBuilder().setModel("fake").setSerial(SERIAL)
                    .setAndroidVersion("13").setAppVersion("test"));
        }
        return out;
    }

    @Test
    public void pythonRecorderAndJavaAppTalkOverZeroMq() throws Exception {
        File python = new File("/opt/loopback/bin/python");
        assertTrue("run through ./dev test (needs the toolchain image's /opt/loopback)", python.canExecute());
        File root = new File(System.getProperty("fixtures")).getParentFile();  // third_party/mocap-contracts
        File repo = root.getParentFile().getParentFile();

        int control = freePort(), stats = freePort(), reply = freePort();
        ControlChannel channel = new ControlChannel(SERIAL, control, stats,
                ControlChannelLoopbackTest::fakeCamera, (m, t) -> System.err.println(m + (t == null ? "" : " " + t)));
        channel.start();
        Thread publisher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                channel.publish(CameraStats.newBuilder().setSensorNs(System.nanoTime()).setCameraFps(30)
                        .setEncoderFps(30).setDroppedFrames(0).setCpuPercent(10).setBatteryTempC(30).setThermalStatus(0));
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        });
        publisher.start();
        try {
            ProcessBuilder pb = new ProcessBuilder(python.getPath(),
                    new File(repo, "tests/loopback/recorder.py").getPath(),
                    Integer.toString(control), Integer.toString(stats), Integer.toString(reply), SERIAL)
                    .redirectErrorStream(true);
            pb.environment().put("PYTHONPATH", root.getPath() + File.pathSeparator + new File(root, "gen/python"));
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) output.append(line).append('\n');
            }
            assertTrue("recorder.py timed out", p.waitFor(90, TimeUnit.SECONDS));
            System.out.print(output);
            assertEquals(output.toString(), 0, p.exitValue());
            assertTrue(output.toString(), output.toString().contains("LOOPBACK OK"));
        } finally {
            publisher.interrupt();
            channel.stop();
        }
    }

    @Test
    public void requestsForAnotherSerialAreNotHandled() {
        ControlChannel channel = new ControlChannel(SERIAL, 0, 0, r -> {
            throw new AssertionError("handler must not run");
        }, (m, t) -> { });
        ControlReply reply = channel.answer(ControlRequest.newBuilder().setRequestId("x").setSerial("other")
                .setReplyEndpoint("tcp://127.0.0.1:1").build());
        assertEquals("x", reply.getRequestId());
        assertEquals(SERIAL, reply.getSerial());
        assertEquals(1, reply.getProblemsCount());
        assertTrue(reply.getProblems(0).contains("other"));
    }

    @Test
    public void streamSettingsAreUntouchedByTheChannel() {
        StreamSettings s = StreamSettings.newBuilder().setCameraId("0").setWidth(640).setHeight(480).setFps(30)
                .setBitrateKbps(4000).setIFrameIntervalS(1).build();
        ControlChannel channel = new ControlChannel(SERIAL, 0, 0, ControlChannelLoopbackTest::fakeCamera, (m, t) -> { });
        ControlReply reply = channel.answer(ControlRequest.newBuilder().setRequestId("y").setSerial(SERIAL)
                .setReplyEndpoint("tcp://127.0.0.1:1").setStream(s).build());
        assertEquals(s, reply.getStreamApplied());
        assertEquals(0, reply.getProblemsCount());
    }
}
