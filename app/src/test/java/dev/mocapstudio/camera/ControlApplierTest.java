package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.harpia.generated.ControlCapability;
import com.harpia.generated.ControlResult;
import com.harpia.generated.ControlSetting;
import com.harpia.generated.ControlStatus;
import com.harpia.generated.ControlValue;
import com.harpia.generated.Flag;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * control/3 with a fake camera: capabilities from ControlCatalog over fake characteristics, read-back values
 * standing in for the capture result.
 */
public class ControlApplierTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final List<String> KEYS = Arrays.asList("android.control.aeMode", "android.sensor.exposureTime",
            "android.sensor.sensitivity", "android.control.aeExposureCompensation", "android.control.aeLock",
            "android.control.afMode", "android.lens.focusDistance", "android.control.aeTargetFpsRange",
            "android.control.awbMode", "android.colorCorrection.gains", "android.control.mode");

    /** A FULL camera with MANUAL_SENSOR. */
    private static Map<String, Object> full() {
        Map<String, Object> c = new HashMap<>();
        c.put("android.control.aeAvailableModes", new int[]{0, 1});
        c.put("android.control.afAvailableModes", new int[]{0, 1, 3});
        c.put("android.control.awbAvailableModes", new int[]{0, 1});
        c.put("android.control.availableModes", new int[]{0, 1});
        c.put("android.sensor.info.exposureTimeRange", new ControlCatalog.Span(10_000L, 100_000_000L));
        c.put("android.sensor.info.sensitivityRange", new ControlCatalog.Span(100, 3200));
        c.put("android.control.aeCompensationRange", new ControlCatalog.Span(-12, 12));
        c.put("android.lens.info.minimumFocusDistance", 10f);
        c.put("android.control.aeAvailableTargetFpsRanges",
                new ControlCatalog.Span[]{new ControlCatalog.Span(15, 30), new ControlCatalog.Span(30, 30)});
        return c;
    }

    private static Map<String, ControlCapability> caps(Map<String, Object> chars) {
        Map<String, ControlCapability> out = new HashMap<>();
        for (ControlCapability c : ControlCatalog.capabilities(KEYS, chars, Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap())) {
            out.put(c.getKey(), c);
        }
        return out;
    }

    /** AE, AF, AWB and 3A in their usual automatic modes. */
    private static Map<String, ControlValue> autoModes() {
        Map<String, ControlValue> m = new HashMap<>();
        m.put("android.control.aeMode", i(1));
        m.put("android.control.afMode", i(3));
        m.put("android.control.awbMode", i(1));
        m.put("android.control.mode", i(1));
        return m;
    }

    private static ControlValue i(long v) {
        return ControlValue.newBuilder().setIntValue(v).build();
    }

    private static ControlValue f(float v) {
        return ControlValue.newBuilder().setFloatValue(v).build();
    }

    private static ControlSetting set(String key, ControlValue v) {
        return ControlSetting.newBuilder().setKey(key).setValue(v).build();
    }

    private static final Set<String> RESULT_KEYS = new HashSet<>(Arrays.asList("android.sensor.timestamp"));

    private static ControlApplier.Plan plan(Map<String, Object> chars, boolean manualSensor, ControlSetting... s) {
        return ControlApplier.plan(Arrays.asList(s), caps(chars), manualSensor, RESULT_KEYS, autoModes());
    }

    @Test
    public void readBackValueIsReported() {
        ControlApplier.Plan p = plan(full(), true, set("android.control.aeExposureCompensation", i(3)));
        ControlApplier.Accepted a = p.accepted.get("android.control.aeExposureCompensation");
        ControlResult r = ControlApplier.result(a, i(3));
        assertEquals(ControlStatus.CONTROL_STATUS_APPLIED, r.getStatus());
        assertEquals(3, r.getApplied().getIntValue());
        // the camera applied something else: reported as read back, CLAMPED
        r = ControlApplier.result(a, i(2));
        assertEquals(ControlStatus.CONTROL_STATUS_CLAMPED, r.getStatus());
        assertEquals(2, r.getApplied().getIntValue());
        assertEquals(3, r.getRequested().getIntValue());
    }

    @Test
    public void outOfRangeIsClampedToTheCapability() {
        ControlApplier.Plan p = plan(full(), true, set("android.control.aeMode", i(0)),
                set("android.sensor.exposureTime", i(500_000_000L)), set("android.sensor.sensitivity", i(50)));
        ControlApplier.Accepted exposure = p.accepted.get("android.sensor.exposureTime");
        assertEquals(100_000_000L, exposure.value.getIntValue());
        assertTrue(exposure.note.contains("clamped"));
        ControlResult r = ControlApplier.result(exposure, i(100_000_000L));
        assertEquals(ControlStatus.CONTROL_STATUS_CLAMPED, r.getStatus());
        assertEquals(100_000_000L, r.getApplied().getIntValue());
        assertEquals(500_000_000L, r.getRequested().getIntValue());
        assertEquals(100, p.accepted.get("android.sensor.sensitivity").value.getIntValue());
    }

    @Test
    public void aMenuValueTheCameraDoesNotOfferIsRefused() {
        Map<String, Object> c = full();
        c.put("android.control.aeAvailableModes", new int[]{1});
        ControlApplier.Plan p = plan(c, true, set("android.control.aeMode", i(0)),
                set("android.control.aeTargetFpsRange", ControlValue.newBuilder().addIntValues(60).addIntValues(60).build()));
        assertEquals(ControlStatus.CONTROL_STATUS_UNSUPPORTED, p.refused.get("android.control.aeMode").getStatus());
        assertTrue(p.refused.get("android.control.aeMode").getNote().contains("1 ON"));
        assertEquals(ControlStatus.CONTROL_STATUS_UNSUPPORTED,
                p.refused.get("android.control.aeTargetFpsRange").getStatus());
        // an offered fps range is accepted
        p = plan(c, true, set("android.control.aeTargetFpsRange",
                ControlValue.newBuilder().addIntValues(30).addIntValues(30).build()));
        assertTrue(p.accepted.containsKey("android.control.aeTargetFpsRange"));
    }

    @Test
    public void manualExposureWithoutAeOffIsAReportedConflict() {
        ControlApplier.Plan p = plan(full(), true, set("android.sensor.exposureTime", i(8_000_000L)));
        ControlResult r = p.refused.get("android.sensor.exposureTime");
        assertEquals(ControlStatus.CONTROL_STATUS_FAILED, r.getStatus());
        assertTrue(r.getNote(), r.getNote().contains("mode conflict") && r.getNote().contains("android.control.aeMode OFF"));
        assertFalse(r.hasApplied());
        assertFalse("the app never flips the mode", p.accepted.containsKey("android.control.aeMode"));
    }

    @Test
    public void manualExposureWithAeOffInTheSameRequestIsAccepted() {
        ControlApplier.Plan p = plan(full(), true, set("android.sensor.exposureTime", i(8_000_000L)),
                set("android.control.aeMode", i(0)));
        assertTrue(p.accepted.containsKey("android.sensor.exposureTime"));
        assertTrue(p.accepted.containsKey("android.control.aeMode"));
        assertEquals(Arrays.asList("android.sensor.exposureTime", "android.control.aeMode"), p.order);
    }

    @Test
    public void manualFocusAndGainsNeedTheirModesToo() {
        ControlApplier.Plan p = plan(full(), true, set("android.lens.focusDistance", f(2f)),
                set("android.colorCorrection.gains", ControlValue.newBuilder().addFloatValues(2).addFloatValues(1)
                        .addFloatValues(1).addFloatValues(1.5f).build()));
        assertTrue(p.refused.get("android.lens.focusDistance").getNote().contains("android.control.afMode OFF"));
        assertTrue(p.refused.get("android.colorCorrection.gains").getNote().contains("android.control.awbMode OFF"));
        p = plan(full(), true, set("android.lens.focusDistance", f(2f)), set("android.control.afMode", i(0)));
        assertTrue(p.accepted.containsKey("android.lens.focusDistance"));
    }

    @Test
    public void limitedWithoutManualSensorExposureTimeIsUnsupported() {
        Map<String, Object> m7 = new HashMap<>();  // LIMITED, BACKWARD_COMPATIBLE only, as the M7
        m7.put("android.control.aeAvailableModes", new int[]{1});
        m7.put("android.sensor.info.exposureTimeRange", new ControlCatalog.Span(250_000_000L, 7_500_000_000_000_000L));
        m7.put("android.control.aeCompensationRange", new ControlCatalog.Span(-4, 4));
        ControlApplier.Plan p = plan(m7, false, set("android.sensor.exposureTime", i(8_000_000L)),
                set("android.sensor.sensitivity", i(400)), set("android.control.aeExposureCompensation", i(-2)),
                set("android.control.aeLock", ControlValue.newBuilder().setFlagValue(Flag.FLAG_ON).build()));
        assertEquals(ControlStatus.CONTROL_STATUS_UNSUPPORTED, p.refused.get("android.sensor.exposureTime").getStatus());
        assertTrue(p.refused.get("android.sensor.exposureTime").getNote().contains("MANUAL_SENSOR"));
        assertEquals(ControlStatus.CONTROL_STATUS_UNSUPPORTED, p.refused.get("android.sensor.sensitivity").getStatus());
        assertTrue(p.accepted.containsKey("android.control.aeExposureCompensation"));
        assertTrue(p.accepted.containsKey("android.control.aeLock"));
    }

    @Test
    public void unknownReadOnlyAndBadlyTypedSettings() {
        ControlApplier.Plan p = plan(full(), true, set("com.vendor.nope", i(1)),
                set("android.sensor.timestamp", i(1)), set("android.control.aeLock", i(1)),
                set("android.control.aeExposureCompensation", i(1)), set("android.control.aeExposureCompensation", i(2)));
        assertEquals(ControlStatus.CONTROL_STATUS_UNSUPPORTED, p.refused.get("com.vendor.nope").getStatus());
        assertEquals(ControlStatus.CONTROL_STATUS_READ_ONLY, p.refused.get("android.sensor.timestamp").getStatus());
        assertEquals(ControlStatus.CONTROL_STATUS_FAILED, p.refused.get("android.control.aeLock").getStatus());
        assertTrue(p.refused.get("android.control.aeLock").getNote().contains("flag_value"));
        assertEquals(1, p.accepted.get("android.control.aeExposureCompensation").value.getIntValue());
        assertEquals(1, p.problems.size());  // one result per key: the second is a problem, not a result
    }

    @Test
    public void aFlagTheCameraIgnoresIsFailedWithWhatItReports() {
        // the M7: AE lock set, the capture result still says OFF
        ControlApplier.Plan p = plan(full(), true, set("android.control.aeLock",
                ControlValue.newBuilder().setFlagValue(Flag.FLAG_ON).build()));
        ControlResult r = ControlApplier.result(p.accepted.get("android.control.aeLock"),
                ControlValue.newBuilder().setFlagValue(Flag.FLAG_OFF).build());
        assertEquals(ControlStatus.CONTROL_STATUS_FAILED, r.getStatus());
        assertEquals(Flag.FLAG_OFF, r.getApplied().getFlagValue());
        assertTrue(r.getNote().contains("reports another value"));
    }

    @Test
    public void notReadBackIsSaid() {
        ControlApplier.Plan p = plan(full(), true, set("android.control.aeLock",
                ControlValue.newBuilder().setFlagValue(Flag.FLAG_ON).build()));
        ControlResult r = ControlApplier.result(p.accepted.get("android.control.aeLock"), null);
        assertEquals(ControlStatus.CONTROL_STATUS_APPLIED, r.getStatus());
        assertFalse(r.hasApplied());
        assertTrue(r.getNote().contains("not read back"));
    }

    @Test
    public void appliedControlsSurviveARestart() throws Exception {
        File file = new File(tmp.getRoot(), "controls.pb");
        new ControlStore(file).put(Arrays.asList(set("android.control.aeLock",
                ControlValue.newBuilder().setFlagValue(Flag.FLAG_ON).build()), set("android.control.aeExposureCompensation", i(-2))));
        new ControlStore(file).put(Collections.singletonList(set("android.control.aeExposureCompensation", i(-1))));
        Map<String, ControlSetting> back = new ControlStore(file).load();  // a new store, as after a restart
        assertEquals(Arrays.asList("android.control.aeLock", "android.control.aeExposureCompensation"),
                Arrays.asList(back.keySet().toArray()));
        assertEquals(-1, back.get("android.control.aeExposureCompensation").getValue().getIntValue());
        assertTrue(new ControlStore(new File(tmp.getRoot(), "none.pb")).load().isEmpty());
    }
}
