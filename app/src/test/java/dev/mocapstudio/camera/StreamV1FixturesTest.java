package dev.mocapstudio.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The app's SEI and sync reply bytes equal the stream protocol v1 fixtures. */
public class StreamV1FixturesTest {

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Test
    public void seiBytesEqualTheTabletExcerpt() throws Exception {
        byte[] stream = Fixtures.bytes("tablet-excerpt.h264");
        List<String> rows = java.nio.file.Files.readAllLines(Fixtures.streamV1("tablet-excerpt.timestamps.csv"));
        assertEquals("frame,seq,sensor_ns,device_unix_ns", rows.get(0));
        int pos = 0;
        for (String row : rows.subList(1, rows.size())) {
            String[] f = row.split(",");
            byte[] sei = TimestampSei.build(Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]));
            int at = indexOf(stream, sei, pos);
            assertTrue("SEI of frame " + f[0] + " not found in order", at >= 0);
            pos = at + sei.length;
        }
    }

    private static String field(String json, String object, String key) {
        Matcher m = Pattern.compile("\"" + object + "\"\\s*:\\s*\\{[^}]*\"" + key + "\"\\s*:\\s*\"?([0-9a-f]+)")
                .matcher(json);
        assertTrue(object + "." + key, m.find());
        return m.group(1);
    }

    @Test
    public void syncReplyEqualsTheFixture() throws Exception {
        String json = Fixtures.text("sync.json");
        byte[] request = Fixtures.hex(field(json, "request", "hex"));
        byte[] expected = Fixtures.hex(field(json, "reply", "hex"));
        long sensorNs = Long.parseLong(field(json, "reply", "sensor_ns"));
        long unixNs = Long.parseLong(field(json, "reply", "device_unix_ns"));

        byte[] out = new byte[TimeSyncServer.REPLY_BYTES];
        assertTrue(TimeSyncServer.reply(request, request.length, sensorNs, unixNs, out));
        assertArrayEquals(expected, out);
    }

    @Test
    public void syncReadsOnlyTheFirstEightBytes() {
        byte[] request = new byte[64];
        for (int i = 0; i < request.length; i++) request[i] = (byte) (i + 1);
        byte[] out = new byte[TimeSyncServer.REPLY_BYTES];
        assertTrue(TimeSyncServer.reply(request, request.length, 1, 2, out));
        assertArrayEquals(Fixtures.hex("0102030405060708" + "0000000000000001" + "0000000000000002"), out);
    }

    @Test
    public void syncIgnoresRequestsShorterThanTheToken() {
        assertFalse(TimeSyncServer.reply(new byte[64], 7, 1, 2, new byte[TimeSyncServer.REPLY_BYTES]));
    }
}
