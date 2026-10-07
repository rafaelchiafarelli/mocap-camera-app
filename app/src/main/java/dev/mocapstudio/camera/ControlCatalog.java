package dev.mocapstudio.camera;

import com.harpia.generated.ControlBackend;
import com.harpia.generated.ControlCapability;
import com.harpia.generated.ControlMenuOption;
import com.harpia.generated.ControlValue;
import com.harpia.generated.ControlValueType;
import com.harpia.generated.Flag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every settable Camera2 control of a camera, as ControlCapability (camera_control.harpia): one per key of
 * getAvailableCaptureRequestKeys(), under its native Camera2 name, never translated.
 *
 * Type, range and options come from the matching characteristic (SPECS below). A key whose limits the device
 * doesn't report is still listed, with min/max/options absent ("range unknown"), never guessed; a key the table
 * doesn't know gets its type from its current or default value. Values reported by the device are passed on
 * as reported, even implausible ones.
 *
 * Plain Java: every value is already normalised (Camera2Values): Integer, Long, Float, Boolean, String, int[],
 * long[], float[], Span (a Range), Span[]. Characteristics and values are keyed by their Camera2 names.
 */
final class ControlCatalog {

    /** A Camera2 Range, normalised. */
    static final class Span {
        final Number lower, upper;

        Span(Number lower, Number upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    private enum Limits {
        /** min/max from a Range characteristic */
        RANGE,
        /** max only, from a scalar characteristic (min is the API's 0) */
        ZERO_TO,
        /** max only, from a scalar characteristic (min not reported) */
        MAX,
        /** menu: options from an int[] characteristic of available values */
        MENU,
        /** min/max of a float[] characteristic of available values */
        FLOAT_CHOICES,
        /** menu over a Range[] characteristic: option value = index, name = "[lo, hi]" */
        RANGE_CHOICES,
        /** max from a Rect characteristic (as int[] {left, top, right, bottom}) */
        RECT,
        /** nothing reported: range unknown */
        NONE,
    }

    private static final class Spec {
        final ControlValueType type;
        final Limits limits;
        final String source;
        final String unit;
        final String[] names;

        Spec(ControlValueType type, Limits limits, String source, String unit, String... names) {
            this.type = type;
            this.limits = limits;
            this.source = source;
            this.unit = unit;
            this.names = names;
        }
    }

    private static final ControlValueType INT = ControlValueType.CONTROL_VALUE_TYPE_INT;
    private static final ControlValueType FLOAT = ControlValueType.CONTROL_VALUE_TYPE_FLOAT;
    private static final ControlValueType FLAG = ControlValueType.CONTROL_VALUE_TYPE_FLAG;
    private static final ControlValueType MENU = ControlValueType.CONTROL_VALUE_TYPE_MENU;
    private static final ControlValueType STRING = ControlValueType.CONTROL_VALUE_TYPE_STRING;
    private static final ControlValueType INT_LIST = ControlValueType.CONTROL_VALUE_TYPE_INT_LIST;
    private static final ControlValueType FLOAT_LIST = ControlValueType.CONTROL_VALUE_TYPE_FLOAT_LIST;

    static final String AE_COMPENSATION_STEP = "android.control.aeCompensationStep";

    /** The Camera2 request keys (API 24-34) and where their limits come from. Menu names are the API's. */
    private static final Map<String, Spec> SPECS = new HashMap<>();

    private static void spec(String key, ControlValueType type, Limits limits, String source, String unit,
                             String... names) {
        SPECS.put(key, new Spec(type, limits, source, unit, names));
    }

    private static void menu(String key, String available, String... names) {
        spec(key, MENU, available == null ? Limits.NONE : Limits.MENU, available, "", names);
    }

    static {
        // sensor
        spec("android.sensor.exposureTime", INT, Limits.RANGE, "android.sensor.info.exposureTimeRange", "ns");
        spec("android.sensor.sensitivity", INT, Limits.RANGE, "android.sensor.info.sensitivityRange", "ISO");
        spec("android.sensor.frameDuration", INT, Limits.MAX, "android.sensor.info.maxFrameDuration", "ns");
        menu("android.sensor.testPatternMode", "android.sensor.availableTestPatternModes",
                "OFF", "SOLID_COLOR", "COLOR_BARS", "COLOR_BARS_FADE_TO_GRAY", "PN9");
        spec("android.sensor.testPatternData", INT_LIST, Limits.NONE, null, "");
        menu("android.sensor.pixelMode", null, "DEFAULT", "MAXIMUM_RESOLUTION");
        // control
        menu("android.control.mode", "android.control.availableModes", "OFF", "AUTO", "USE_SCENE_MODE",
                "OFF_KEEP_STATE", "USE_EXTENDED_SCENE_MODE");
        menu("android.control.aeMode", "android.control.aeAvailableModes", "OFF", "ON", "ON_AUTO_FLASH",
                "ON_ALWAYS_FLASH", "ON_AUTO_FLASH_REDEYE", "ON_EXTERNAL_FLASH");
        spec("android.control.aeLock", FLAG, Limits.NONE, null, "");
        spec("android.control.aeExposureCompensation", INT, Limits.RANGE, "android.control.aeCompensationRange", "");
        spec("android.control.aeTargetFpsRange", INT_LIST, Limits.RANGE_CHOICES,
                "android.control.aeAvailableTargetFpsRanges", "fps");
        menu("android.control.aeAntibandingMode", "android.control.aeAvailableAntibandingModes",
                "OFF", "50HZ", "60HZ", "AUTO");
        menu("android.control.aePrecaptureTrigger", null, "IDLE", "START", "CANCEL");
        spec("android.control.aeRegions", INT_LIST, Limits.NONE, null, "px");
        menu("android.control.afMode", "android.control.afAvailableModes", "OFF", "AUTO", "MACRO",
                "CONTINUOUS_VIDEO", "CONTINUOUS_PICTURE", "EDOF");
        menu("android.control.afTrigger", null, "IDLE", "START", "CANCEL");
        spec("android.control.afRegions", INT_LIST, Limits.NONE, null, "px");
        menu("android.control.awbMode", "android.control.awbAvailableModes", "OFF", "AUTO", "INCANDESCENT",
                "FLUORESCENT", "WARM_FLUORESCENT", "DAYLIGHT", "CLOUDY_DAYLIGHT", "TWILIGHT", "SHADE");
        spec("android.control.awbLock", FLAG, Limits.NONE, null, "");
        spec("android.control.awbRegions", INT_LIST, Limits.NONE, null, "px");
        menu("android.control.captureIntent", null, "CUSTOM", "PREVIEW", "STILL_CAPTURE", "VIDEO_RECORD",
                "VIDEO_SNAPSHOT", "ZERO_SHUTTER_LAG", "MANUAL", "MOTION_TRACKING");
        menu("android.control.effectMode", "android.control.availableEffects", "OFF", "MONO", "NEGATIVE",
                "SOLARIZE", "SEPIA", "POSTERIZE", "WHITEBOARD", "BLACKBOARD", "AQUA");
        menu("android.control.sceneMode", "android.control.availableSceneModes", "DISABLED", "FACE_PRIORITY",
                "ACTION", "PORTRAIT", "LANDSCAPE", "NIGHT", "NIGHT_PORTRAIT", "THEATRE", "BEACH", "SNOW",
                "SUNSET", "STEADYPHOTO", "FIREWORKS", "SPORTS", "PARTY", "CANDLELIGHT", "BARCODE",
                "HIGH_SPEED_VIDEO", "HDR");
        menu("android.control.videoStabilizationMode", "android.control.availableVideoStabilizationModes",
                "OFF", "ON", "PREVIEW_STABILIZATION");
        spec("android.control.postRawSensitivityBoost", INT, Limits.RANGE,
                "android.control.postRawSensitivityBoostRange", "ISO/100");
        spec("android.control.enableZsl", FLAG, Limits.NONE, null, "");
        spec("android.control.zoomRatio", FLOAT, Limits.RANGE, "android.control.zoomRatioRange", "x");
        menu("android.control.extendedSceneMode", null, "DISABLED", "BOKEH_STILL_CAPTURE", "BOKEH_CONTINUOUS");
        menu("android.control.settingsOverride", null, "OFF", "ZOOM");
        menu("android.control.autoframing", null, "OFF", "ON", "AUTO");
        // lens
        spec("android.lens.aperture", FLOAT, Limits.FLOAT_CHOICES, "android.lens.info.availableApertures", "f-number");
        spec("android.lens.filterDensity", FLOAT, Limits.FLOAT_CHOICES,
                "android.lens.info.availableFilterDensities", "EV");
        spec("android.lens.focalLength", FLOAT, Limits.FLOAT_CHOICES, "android.lens.info.availableFocalLengths", "mm");
        spec("android.lens.focusDistance", FLOAT, Limits.ZERO_TO, "android.lens.info.minimumFocusDistance", "diopter");
        menu("android.lens.opticalStabilizationMode", "android.lens.info.availableOpticalStabilization", "OFF", "ON");
        // image processing
        menu("android.colorCorrection.mode", null, "TRANSFORM_MATRIX", "FAST", "HIGH_QUALITY");
        spec("android.colorCorrection.transform", FLOAT_LIST, Limits.NONE, null, "");
        spec("android.colorCorrection.gains", FLOAT_LIST, Limits.NONE, null, "");
        menu("android.colorCorrection.aberrationMode", "android.colorCorrection.availableAberrationModes",
                "OFF", "FAST", "HIGH_QUALITY");
        menu("android.noiseReduction.mode", "android.noiseReduction.availableNoiseReductionModes",
                "OFF", "FAST", "HIGH_QUALITY", "MINIMAL", "ZERO_SHUTTER_LAG");
        menu("android.edge.mode", "android.edge.availableEdgeModes", "OFF", "FAST", "HIGH_QUALITY", "ZERO_SHUTTER_LAG");
        menu("android.hotPixel.mode", "android.hotPixel.availableHotPixelModes", "OFF", "FAST", "HIGH_QUALITY");
        menu("android.shading.mode", "android.shading.availableModes", "OFF", "FAST", "HIGH_QUALITY");
        menu("android.tonemap.mode", "android.tonemap.availableToneMapModes", "CONTRAST_CURVE", "FAST",
                "HIGH_QUALITY", "GAMMA_VALUE", "PRESET_CURVE");
        spec("android.tonemap.curve", FLOAT_LIST, Limits.NONE, null, "");
        spec("android.tonemap.gamma", FLOAT, Limits.NONE, null, "");
        menu("android.tonemap.presetCurve", null, "SRGB", "REC709");
        spec("android.blackLevel.lock", FLAG, Limits.NONE, null, "");
        menu("android.distortionCorrection.mode", "android.distortionCorrection.availableModes",
                "OFF", "FAST", "HIGH_QUALITY");
        spec("android.scaler.cropRegion", INT_LIST, Limits.RECT, "android.sensor.info.activeArraySize", "px");
        menu("android.scaler.rotateAndCrop", "android.scaler.availableRotateAndCropModes",
                "NONE", "90", "180", "270", "AUTO");
        // flash, statistics, jpeg, reprocess
        menu("android.flash.mode", null, "OFF", "SINGLE", "TORCH");
        spec("android.flash.strengthLevel", INT, Limits.MAX, "android.flash.torchStrengthMaxLevel", "");
        menu("android.statistics.faceDetectMode", "android.statistics.info.availableFaceDetectModes",
                "OFF", "SIMPLE", "FULL");
        spec("android.statistics.hotPixelMapMode", FLAG, Limits.NONE, null, "");
        menu("android.statistics.lensShadingMapMode", "android.statistics.info.availableLensShadingMapModes", "OFF", "ON");
        menu("android.statistics.oisDataMode", "android.statistics.info.availableOisDataModes", "OFF", "ON");
        spec("android.jpeg.gpsLocation", STRING, Limits.NONE, null, "");
        spec("android.jpeg.orientation", INT, Limits.NONE, null, "deg");
        spec("android.jpeg.quality", INT, Limits.NONE, null, "");
        spec("android.jpeg.thumbnailQuality", INT, Limits.NONE, null, "");
        spec("android.jpeg.thumbnailSize", INT_LIST, Limits.NONE, null, "px");
        spec("android.reprocess.effectiveExposureFactor", FLOAT, Limits.NONE, null, "");
    }

    private ControlCatalog() { }

    /**
     * One capability per request key, in the device's order.
     * @param chars    the camera's characteristics, normalised, by Camera2 name
     * @param current  the latest capture result's values (may be empty: no current values)
     * @param defaults the request template's values (may be empty)
     */
    static List<ControlCapability> capabilities(List<String> requestKeys, Map<String, Object> chars,
                                                Map<String, Object> current, Map<String, Object> defaults) {
        List<ControlCapability> out = new ArrayList<>();
        for (String key : requestKeys) out.add(capability(key, chars, current.get(key), defaults.get(key)));
        return out;
    }

    static ControlCapability capability(String key, Map<String, Object> chars, Object current, Object dflt) {
        Spec spec = SPECS.get(key);
        ControlValueType type = spec != null ? spec.type : typeOf(current != null ? current : dflt);
        ControlCapability.Builder c = ControlCapability.newBuilder()
                .setBackend(ControlBackend.CONTROL_BACKEND_CAMERA2)
                .setKey(key)
                .setValueType(type)
                .setReadOnly(Flag.FLAG_OFF)
                .setUnit(spec == null ? "" : spec.unit);
        if (spec != null) limits(c, spec, chars, key);
        ControlValue v = value(type, current);
        if (v != null) c.setCurrentValue(v);
        ControlValue d = value(type, dflt);
        if (d != null) c.setDefaultValue(d);
        return c.build();
    }

    private static void limits(ControlCapability.Builder c, Spec spec, Map<String, Object> chars, String key) {
        Object src = spec.source == null ? null : chars.get(spec.source);
        if (src == null) return;  // not reported: range unknown
        switch (spec.limits) {
            case RANGE:
                if (src instanceof Span) {
                    Span s = (Span) src;
                    c.setMinValue(scalar(spec.type, s.lower)).setMaxValue(scalar(spec.type, s.upper));
                    if ("android.control.aeExposureCompensation".equals(key)) {
                        c.setStep(ControlValue.newBuilder().setIntValue(1));
                        Object step = chars.get(AE_COMPENSATION_STEP);
                        if (step instanceof String) c.setUnit("steps of " + step + " EV");
                    }
                }
                break;
            case ZERO_TO:
                if (src instanceof Number) c.setMinValue(scalar(spec.type, 0)).setMaxValue(scalar(spec.type, (Number) src));
                break;
            case MAX:
                if (src instanceof Number) c.setMaxValue(scalar(spec.type, (Number) src));
                break;
            case MENU:
                if (src instanceof int[]) {
                    for (int v : (int[]) src) {
                        String name = v >= 0 && v < spec.names.length ? spec.names[v] : Integer.toString(v);
                        c.addOptions(ControlMenuOption.newBuilder().setValue(v).setName(name));
                    }
                }
                break;
            case FLOAT_CHOICES:
                if (src instanceof float[] && ((float[]) src).length > 0) {
                    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                    for (float f : (float[]) src) { lo = Math.min(lo, f); hi = Math.max(hi, f); }
                    c.setMinValue(ControlValue.newBuilder().setFloatValue(lo)).setMaxValue(ControlValue.newBuilder().setFloatValue(hi));
                }
                break;
            case RANGE_CHOICES:
                if (src instanceof Span[]) {
                    Span[] spans = (Span[]) src;
                    for (int i = 0; i < spans.length; i++) {
                        c.addOptions(ControlMenuOption.newBuilder().setValue(i)
                                .setName("[" + spans[i].lower + ", " + spans[i].upper + "]"));
                    }
                }
                break;
            case RECT:
                if (src instanceof int[] && ((int[]) src).length == 4) {
                    int[] r = (int[]) src;
                    c.setMaxValue(ControlValue.newBuilder().addIntValues(r[0]).addIntValues(r[1])
                            .addIntValues(r[2]).addIntValues(r[3]));
                }
                break;
            case NONE:
                break;
        }
    }

    /** The type of a key the table doesn't know, from a value of it; STRING when there is none. */
    static ControlValueType typeOf(Object v) {
        if (v instanceof Boolean) return FLAG;
        if (v instanceof Float || v instanceof Double) return FLOAT;
        if (v instanceof Number) return INT;
        if (v instanceof int[] || v instanceof long[] || v instanceof Span) return INT_LIST;
        if (v instanceof float[]) return FLOAT_LIST;
        return STRING;
    }

    private static ControlValue scalar(ControlValueType type, Number n) {
        ControlValue.Builder b = ControlValue.newBuilder();
        if (type == FLOAT) b.setFloatValue(n.floatValue());
        else b.setIntValue(n.longValue());
        return b.build();
    }

    /** A normalised Camera2 value as a ControlValue of the control's type, or null if there is none. */
    static ControlValue value(ControlValueType type, Object v) {
        if (v == null) return null;
        ControlValue.Builder b = ControlValue.newBuilder();
        if (v instanceof Boolean) {
            b.setFlagValue((Boolean) v ? Flag.FLAG_ON : Flag.FLAG_OFF);
        } else if (v instanceof Float || v instanceof Double) {
            b.setFloatValue(((Number) v).floatValue());
        } else if (v instanceof Number) {
            b.setIntValue(((Number) v).longValue());
        } else if (v instanceof Span) {
            Span s = (Span) v;
            if (type == FLOAT_LIST) b.addFloatValues(s.lower.floatValue()).addFloatValues(s.upper.floatValue());
            else b.addIntValues(s.lower.longValue()).addIntValues(s.upper.longValue());
        } else if (v instanceof int[]) {
            for (int x : (int[]) v) b.addIntValues(x);
        } else if (v instanceof long[]) {
            for (long x : (long[]) v) b.addIntValues(x);
        } else if (v instanceof float[]) {
            for (float x : (float[]) v) b.addFloatValues(x);
        } else {
            b.setTextValue(String.valueOf(v));
        }
        return b.build();
    }
}
