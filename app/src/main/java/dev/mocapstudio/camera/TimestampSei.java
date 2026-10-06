package dev.mocapstudio.camera;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Builds an H.264 SEI NAL (user_data_unregistered, payloadType 5) carrying a frame's timestamps, so they
 * travel inside the elementary stream and survive saving it to a file.
 *
 * Payload (40 bytes, big-endian): 16-byte UUID "MOCAPSTUDIO-TS01", frame seq, sensor-clock ns,
 * tablet Unix ns at capture (sensor ns + the tablet's wall-clock offset; not synced to anything).
 */
final class TimestampSei {
    static final byte[] UUID = "MOCAPSTUDIO-TS01".getBytes(StandardCharsets.US_ASCII);

    private TimestampSei() { }

    static byte[] build(long seq, long sensorNs, long unixNs) {
        ByteBuffer payload = ByteBuffer.allocate(40);
        payload.put(UUID).putLong(seq).putLong(sensorNs).putLong(unixNs);

        ByteArrayOutputStream rbsp = new ByteArrayOutputStream(48);
        rbsp.write(5);                    // payloadType: user_data_unregistered
        rbsp.write(40);                   // payloadSize
        rbsp.write(payload.array(), 0, 40);
        rbsp.write(0x80);                 // rbsp_trailing_bits

        ByteArrayOutputStream nal = new ByteArrayOutputStream(64);
        nal.write(0); nal.write(0); nal.write(0); nal.write(1);
        nal.write(0x06);                  // nal_unit_type 6 = SEI
        int zeros = 0;
        for (byte x : rbsp.toByteArray()) {  // emulation prevention
            int v = x & 0xff;
            if (zeros >= 2 && v <= 3) {
                nal.write(3);
                zeros = 0;
            }
            nal.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return nal.toByteArray();
    }
}
