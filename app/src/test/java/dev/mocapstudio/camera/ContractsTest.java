package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;

import com.harpia.generated.ControlReply;
import com.harpia.generated.ControlResult;
import com.harpia.generated.ControlStatus;
import com.harpia.generated.ControlValue;
import com.harpia.runtime.json.HarpiaJson;
import org.junit.Test;

/** The app compiles and runs against the messages generated from mocap-contracts. */
public class ContractsTest {

    private static ControlReply reply() {
        return ControlReply.newBuilder()
                .setRequestId("req-1")
                .setSerial("M7-200138")
                .addResults(ControlResult.newBuilder()
                        .setKey("sensor.exposure_time")
                        .setRequested(ControlValue.newBuilder().setIntValue(8_000_000L))
                        .setApplied(ControlValue.newBuilder().setIntValue(7_998_000L))
                        .setStatus(ControlStatus.CONTROL_STATUS_CLAMPED))
                .addProblems("none")
                .build();
    }

    private static void assertReply(ControlReply r) {
        assertEquals("req-1", r.getRequestId());
        assertEquals("M7-200138", r.getSerial());
        assertEquals(1, r.getResultsCount());
        ControlResult result = r.getResults(0);
        assertEquals("sensor.exposure_time", result.getKey());
        assertEquals(8_000_000L, result.getRequested().getIntValue());
        assertEquals(7_998_000L, result.getApplied().getIntValue());
        assertEquals(ControlStatus.CONTROL_STATUS_CLAMPED, result.getStatus());
        assertEquals("none", r.getProblems(0));
    }

    @Test
    public void controlReplyRoundTripsThroughBytes() throws Exception {
        assertReply(ControlReply.parseFrom(reply().toByteArray()));
    }

    @Test
    public void controlReplyRoundTripsThroughJson() throws Exception {
        String json = HarpiaJson.toJson(reply());
        assertReply(HarpiaJson.fromJson(json, ControlReply.newBuilder()).build());
    }
}
