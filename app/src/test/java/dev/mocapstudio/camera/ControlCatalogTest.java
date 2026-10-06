package dev.mocapstudio.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.harpia.generated.ControlBackend;
import com.harpia.generated.ControlCapability;
import com.harpia.generated.ControlMenuOption;
import com.harpia.generated.ControlValueType;
import com.harpia.generated.Flag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** control/2 with fake characteristics: the M7's (as dumpsys media.camera reports them) and a FULL device's. */
public class ControlCatalogTest {
    private static final Map<String, Object> NONE = Collections.emptyMap();

    /** The Multilaser M7's camera 0, as reported (implausible exposure range included). */
    private static Map<String, Object> m7() {
        Map<String, Object> c = new HashMap<>();
        c.put("android.control.aeCompensationRange", new ControlCatalog.Span(-4, 4));
        c.put(ControlCatalog.AE_COMPENSATION_STEP, "1/2");
        c.put("android.control.afAvailableModes", new int[]{0});
        c.put("android.control.aeAvailableAntibandingModes", new int[]{3});
        c.put("android.sensor.info.exposureTimeRange", new ControlCatalog.Span(250_000_000L, 7_500_000_000_000_000L));
        c.put("android.sensor.info.sensitivityRange", new ControlCatalog.Span(400, 400));
        c.put("android.control.aeAvailableModes", new int[]{1});
        c.put("android.control.awbAvailableModes", new int[]{0, 1});
        c.put("android.lens.info.minimumFocusDistance", 0f);
        c.put("android.control.aeAvailableTargetFpsRanges",
                new ControlCatalog.Span[]{new ControlCatalog.Span(6, 30), new ControlCatalog.Span(30, 30)});
        c.put("android.lens.info.availableApertures", new float[]{2.0f});
        c.put("android.sensor.info.activeArraySize", new int[]{0, 0, 1600, 1200});
        return c;
    }

    private static ControlCapability find(List<ControlCapability> caps, String key) {
        for (ControlCapability c : caps) if (c.getKey().equals(key)) return c;
        throw new AssertionError(key + " not listed");
    }

    private static List<String> names(ControlCapability c) {
        List<String> out = new ArrayList<>();
        for (ControlMenuOption o : c.getOptionsList()) out.add(o.getValue() + ":" + o.getName());
        return out;
    }

    @Test
    public void everyAvailableKeyIsListedInOrderUnderItsCamera2Name() {
        List<String> keys = Arrays.asList("android.control.aeMode", "android.sensor.exposureTime",
                "com.vendor.secret.mode", "android.jpeg.quality", "android.control.aeTargetFpsRange");
        List<ControlCapability> caps = ControlCatalog.capabilities(keys, m7(), NONE, NONE);
        List<String> listed = new ArrayList<>();
        for (ControlCapability c : caps) {
            listed.add(c.getKey());
            assertEquals(ControlBackend.CONTROL_BACKEND_CAMERA2, c.getBackend());
            assertEquals(Flag.FLAG_OFF, c.getReadOnly());
            assertTrue(c.getKey(), c.getValueType() != ControlValueType.CONTROL_VALUE_TYPE_UNSET);
        }
        assertEquals(keys, listed);
    }

    @Test
    public void rangesComeFromTheCharacteristicAsReported() {
        List<ControlCapability> caps = ControlCatalog.capabilities(Arrays.asList(
                "android.sensor.exposureTime", "android.sensor.sensitivity", "android.lens.focusDistance",
                "android.lens.aperture", "android.scaler.cropRegion"), m7(), NONE, NONE);
        ControlCapability exposure = find(caps, "android.sensor.exposureTime");
        assertEquals(ControlValueType.CONTROL_VALUE_TYPE_INT, exposure.getValueType());
        assertEquals(250_000_000L, exposure.getMinValue().getIntValue());
        assertEquals(7_500_000_000_000_000L, exposure.getMaxValue().getIntValue());  // implausible, passed on
        assertEquals("ns", exposure.getUnit());
        ControlCapability iso = find(caps, "android.sensor.sensitivity");
        assertEquals(400, iso.getMinValue().getIntValue());
        assertEquals(400, iso.getMaxValue().getIntValue());
        ControlCapability focus = find(caps, "android.lens.focusDistance");
        assertEquals(ControlValueType.CONTROL_VALUE_TYPE_FLOAT, focus.getValueType());
        assertEquals(0f, focus.getMinValue().getFloatValue(), 0);
        assertEquals(0f, focus.getMaxValue().getFloatValue(), 0);  // fixed focus
        ControlCapability aperture = find(caps, "android.lens.aperture");
        assertEquals(2.0f, aperture.getMinValue().getFloatValue(), 0);
        assertEquals(2.0f, aperture.getMaxValue().getFloatValue(), 0);
        ControlCapability crop = find(caps, "android.scaler.cropRegion");
        assertEquals(Arrays.asList(0L, 0L, 1600L, 1200L), crop.getMaxValue().getIntValuesList());
    }

    @Test
    public void exposureCompensationHasItsStep() {
        ControlCapability ev = ControlCatalog.capability("android.control.aeExposureCompensation", m7(), null, null);
        assertEquals(-4, ev.getMinValue().getIntValue());
        assertEquals(4, ev.getMaxValue().getIntValue());
        assertEquals(1, ev.getStep().getIntValue());
        assertEquals("steps of 1/2 EV", ev.getUnit());
    }

    @Test
    public void menusListOnlyWhatTheDeviceOffers() {
        Map<String, Object> c = m7();
        assertEquals(Collections.singletonList("1:ON"),
                names(ControlCatalog.capability("android.control.aeMode", c, null, null)));
        assertEquals(Collections.singletonList("0:OFF"),
                names(ControlCatalog.capability("android.control.afMode", c, null, null)));
        assertEquals(Arrays.asList("0:OFF", "1:AUTO"),
                names(ControlCatalog.capability("android.control.awbMode", c, null, null)));
        assertEquals(Collections.singletonList("3:AUTO"),
                names(ControlCatalog.capability("android.control.aeAntibandingMode", c, null, null)));
        assertEquals(Arrays.asList("0:[6, 30]", "1:[30, 30]"),
                names(ControlCatalog.capability("android.control.aeTargetFpsRange", c, null, null)));
    }

    @Test
    public void aMenuValueOutsideTheApiNamesKeepsItsNumber() {
        Map<String, Object> c = new HashMap<>();
        c.put("android.control.aeAvailableModes", new int[]{0, 1, 42});
        assertEquals(Arrays.asList("0:OFF", "1:ON", "42:42"),
                names(ControlCatalog.capability("android.control.aeMode", c, null, null)));
    }

    @Test
    public void aKeyWithoutAReportedRangeIsListedAsRangeUnknown() {
        Map<String, Object> c = new HashMap<>();  // the device reports nothing
        for (String key : Arrays.asList("android.sensor.exposureTime", "android.control.aeMode",
                "android.control.aeExposureCompensation", "android.colorCorrection.gains", "android.jpeg.quality")) {
            ControlCapability cap = ControlCatalog.capability(key, c, null, null);
            assertFalse(key, cap.hasMinValue());
            assertFalse(key, cap.hasMaxValue());
            assertFalse(key, cap.hasStep());
            assertEquals(key, 0, cap.getOptionsCount());
            assertTrue(key, cap.getValueType() != ControlValueType.CONTROL_VALUE_TYPE_UNSET);
        }
    }

    @Test
    public void unknownKeysTakeTheirTypeFromTheirValue() {
        assertEquals(ControlValueType.CONTROL_VALUE_TYPE_INT,
                ControlCatalog.capability("com.vendor.level", NONE, 3, null).getValueType());
        assertEquals(ControlValueType.CONTROL_VALUE_TYPE_FLAG,
                ControlCatalog.capability("com.vendor.on", NONE, null, Boolean.TRUE).getValueType());
        assertEquals(ControlValueType.CONTROL_VALUE_TYPE_FLOAT_LIST,
                ControlCatalog.capability("com.vendor.gains", NONE, new float[]{1, 2}, null).getValueType());
        assertEquals(ControlValueType.CONTROL_VALUE_TYPE_STRING,
                ControlCatalog.capability("com.vendor.nothing", NONE, null, null).getValueType());
    }

    @Test
    public void currentAndDefaultValuesFromTheCaptureResultAndTemplate() {
        Map<String, Object> current = new HashMap<>();
        current.put("android.sensor.exposureTime", 33_000_000L);
        current.put("android.control.aeLock", Boolean.FALSE);
        current.put("android.control.aeTargetFpsRange", new ControlCatalog.Span(30, 30));
        current.put("android.colorCorrection.gains", new float[]{1.9f, 1f, 1f, 1.6f});
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("android.control.aeMode", 1);
        List<ControlCapability> caps = ControlCatalog.capabilities(Arrays.asList("android.sensor.exposureTime",
                "android.control.aeLock", "android.control.aeTargetFpsRange", "android.colorCorrection.gains",
                "android.control.aeMode"), m7(), current, defaults);
        assertEquals(33_000_000L, find(caps, "android.sensor.exposureTime").getCurrentValue().getIntValue());
        assertEquals(Flag.FLAG_OFF, find(caps, "android.control.aeLock").getCurrentValue().getFlagValue());
        assertEquals(Arrays.asList(30L, 30L),
                find(caps, "android.control.aeTargetFpsRange").getCurrentValue().getIntValuesList());
        assertEquals(4, find(caps, "android.colorCorrection.gains").getCurrentValue().getFloatValuesCount());
        ControlCapability ae = find(caps, "android.control.aeMode");
        assertFalse(ae.hasCurrentValue());
        assertEquals(1, ae.getDefaultValue().getIntValue());
    }
}
