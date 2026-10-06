package dev.mocapstudio.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;

public class TimestampSeiTest {

    /** Strips the start code and NAL header and undoes emulation prevention. */
    private static byte[] rbsp(byte[] nal) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int zeros = 0;
        for (int i = 5; i < nal.length; i++) {
            int v = nal[i] & 0xff;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            out.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    @Test
    public void startCodeAndSeiHeader() {
        byte[] nal = TimestampSei.build(1, 2, 3);
        assertArrayEquals(new byte[] {0, 0, 0, 1, 0x06}, Arrays.copyOf(nal, 5));
    }

    @Test
    public void payloadRoundTrips() {
        long seq = 123456789L, sensorNs = 987654321012345L, unixNs = 1759700000123456789L;
        byte[] r = rbsp(TimestampSei.build(seq, sensorNs, unixNs));

        assertEquals(43, r.length);
        assertEquals(5, r[0]);                    // user_data_unregistered
        assertEquals(40, r[1]);                   // payloadSize
        assertEquals(0x80, r[42] & 0xff);         // rbsp_trailing_bits
        ByteBuffer p = ByteBuffer.wrap(r, 2, 40);
        byte[] uuid = new byte[16];
        p.get(uuid);
        assertArrayEquals(TimestampSei.UUID, uuid);
        assertEquals(seq, p.getLong());
        assertEquals(sensorNs, p.getLong());
        assertEquals(unixNs, p.getLong());
    }

    @Test
    public void emulationPreventionBreaksStartCodes() {
        // seq 0 and small timestamps are mostly zero bytes: the raw payload is full of 00 00 0x runs.
        byte[] nal = TimestampSei.build(0, 1, 0);
        for (int i = 5; i + 2 < nal.length; i++) {
            boolean startCodeLike = nal[i] == 0 && nal[i + 1] == 0 && (nal[i + 2] & 0xff) <= 2;
            assertEquals("forbidden 00 00 0x at " + i, false, startCodeLike);
        }
        ByteBuffer p = ByteBuffer.wrap(rbsp(nal), 2 + 16, 24);
        assertEquals(0, p.getLong());
        assertEquals(1, p.getLong());
        assertEquals(0, p.getLong());
    }
}
