package dev.mocapstudio.camera;

import com.harpia.generated.ControlSetting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The camera controls applied so far (the accepted, possibly clamped, values), by key, so a restart after a
 * reboot or a crash puts them back on the repeating request. Length-delimited ControlSetting messages, written
 * to a temp file and renamed.
 */
final class ControlStore {
    private final File file;

    ControlStore(File file) {
        this.file = file;
    }

    /** The saved controls in the order they were first set; empty if none or unreadable. */
    synchronized Map<String, ControlSetting> load() {
        Map<String, ControlSetting> out = new LinkedHashMap<>();
        try (InputStream in = new FileInputStream(file)) {
            ControlSetting s;
            while ((s = ControlSetting.parseDelimitedFrom(in)) != null) out.put(s.getKey(), s);
        } catch (IOException e) {
            out.clear();
        }
        return out;
    }

    /** Adds or replaces these controls and saves them all. */
    synchronized void put(Iterable<ControlSetting> settings) throws IOException {
        Map<String, ControlSetting> all = load();
        for (ControlSetting s : settings) all.put(s.getKey(), s);
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            for (ControlSetting s : all.values()) s.writeDelimitedTo(out);
        }
        if (!tmp.renameTo(file)) throw new IOException("could not replace " + file);
    }
}
