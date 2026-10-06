package dev.mocapstudio.camera;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** The stream protocol v1 fixtures of mocap-contracts (third_party/mocap-contracts/fixtures/stream-v1). */
final class Fixtures {
    private Fixtures() { }

    static Path streamV1(String name) {
        return Paths.get(System.getProperty("fixtures"), "stream-v1", name);
    }

    static byte[] bytes(String name) throws IOException {
        return Files.readAllBytes(streamV1(name));
    }

    static String text(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
