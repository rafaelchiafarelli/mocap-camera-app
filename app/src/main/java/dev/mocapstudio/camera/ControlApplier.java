package dev.mocapstudio.camera;

import com.harpia.generated.ControlCapability;
import com.harpia.generated.ControlMenuOption;
import com.harpia.generated.ControlResult;
import com.harpia.generated.ControlSetting;
import com.harpia.generated.ControlStatus;
import com.harpia.generated.ControlValue;
import com.harpia.generated.ControlValueType;
import com.harpia.generated.Flag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what to do with each requested ControlSetting (control/3), before and after the camera applies it.
 *
 * plan(): each setting is validated against its ControlCapability. A key the camera doesn't offer is
 * UNSUPPORTED (READ_ONLY if it only appears in capture results), so are manual sensor keys on a camera without
 * MANUAL_SENSOR and menu values it doesn't offer; a value of the wrong type is FAILED; a number outside the
 * reported range is clamped to it. Dependent modes are never flipped: a manual exposure needs
 * android.control.aeMode OFF, a manual focus android.control.afMode OFF, manual colour gains/transform
 * android.control.awbMode OFF, a tonemap curve/gamma/preset its tonemap mode; unless that mode is already in
 * effect or set in the same request, the setting is FAILED as a mode conflict.
 *
 * result(): what the camera reports back for an accepted setting: APPLIED when the read-back value equals the
 * requested one; CLAMPED when it was clamped, or the camera applied another number; FAILED when a flag, menu or
 * list reads back as something else (the camera didn't take it). Plain Java.
 */
final class ControlApplier {

    /** One accepted setting: the value to put on the request (clamped if needed). */
    static final class Accepted {
        final String key;
        final ControlValueType type;
        final ControlValue requested;
        final ControlValue value;
        final String note;

        Accepted(String key, ControlValueType type, ControlValue requested, ControlValue value, String note) {
            this.key = key;
            this.type = type;
            this.requested = requested;
            this.value = value;
            this.note = note;
        }

        boolean clamped() {
            return !note.isEmpty();
        }
    }

    /** Per requested setting, in request order: either a final result (refused) or an accepted value. */
    static final class Plan {
        final List<String> order = new ArrayList<>();
        final Map<String, ControlResult> refused = new HashMap<>();
        final Map<String, Accepted> accepted = new LinkedHashMap<>();
        /** For ControlReply.problems: a key requested twice (the contract allows one result per key). */
        final List<String> problems = new ArrayList<>();
    }

    static final Set<String> MANUAL_SENSOR_KEYS = Collections.unmodifiableSet(new java.util.HashSet<>(Arrays.asList(
            "android.sensor.exposureTime", "android.sensor.sensitivity", "android.sensor.frameDuration")));

    private static final Set<String> THREE_A = Collections.unmodifiableSet(new java.util.HashSet<>(Arrays.asList(
            "android.control.aeMode", "android.control.afMode", "android.control.awbMode")));

    /** key → {mode key, mode value it needs, that value's name} */
    private static final Map<String, Object[]> NEEDS = new HashMap<>();

    static {
        for (String k : MANUAL_SENSOR_KEYS) NEEDS.put(k, new Object[]{"android.control.aeMode", 0L, "OFF"});
        NEEDS.put("android.lens.focusDistance", new Object[]{"android.control.afMode", 0L, "OFF"});
        NEEDS.put("android.colorCorrection.gains", new Object[]{"android.control.awbMode", 0L, "OFF"});
        NEEDS.put("android.colorCorrection.transform", new Object[]{"android.control.awbMode", 0L, "OFF"});
        NEEDS.put("android.tonemap.curve", new Object[]{"android.tonemap.mode", 0L, "CONTRAST_CURVE"});
        NEEDS.put("android.tonemap.gamma", new Object[]{"android.tonemap.mode", 3L, "GAMMA_VALUE"});
        NEEDS.put("android.tonemap.presetCurve", new Object[]{"android.tonemap.mode", 4L, "PRESET_CURVE"});
    }

    private ControlApplier() { }

    /**
     * @param caps         the camera's capabilities by key (ControlCatalog)
     * @param manualSensor whether the camera has the MANUAL_SENSOR capability
     * @param resultKeys   keys its capture results report (to tell READ_ONLY from UNSUPPORTED)
     * @param inEffect     the values in effect now (saved settings over the current capture result), by key
     */
    static Plan plan(List<ControlSetting> settings, Map<String, ControlCapability> caps, boolean manualSensor,
                     Set<String> resultKeys, Map<String, ControlValue> inEffect) {
        Plan plan = new Plan();
        for (ControlSetting s : settings) {
            String key = s.getKey();
            if (plan.order.contains(key)) {
                plan.problems.add(key + " requested twice: only the first is handled");
                continue;
            }
            plan.order.add(key);
            ControlCapability cap = caps.get(key);
            if (cap == null) {
                plan.refused.put(key, resultKeys.contains(key)
                        ? refuse(s, ControlStatus.CONTROL_STATUS_READ_ONLY, "reported by the camera, not settable")
                        : refuse(s, ControlStatus.CONTROL_STATUS_UNSUPPORTED, "not a control of this camera"));
                continue;
            }
            if (cap.getReadOnly() == Flag.FLAG_ON) {
                plan.refused.put(key, refuse(s, ControlStatus.CONTROL_STATUS_READ_ONLY, "read-only"));
                continue;
            }
            if (!manualSensor && MANUAL_SENSOR_KEYS.contains(key)) {
                plan.refused.put(key, refuse(s, ControlStatus.CONTROL_STATUS_UNSUPPORTED,
                        "needs the MANUAL_SENSOR capability, which this camera doesn't have"));
                continue;
            }
            String typeError = typeError(cap.getValueType(), s.getValue());
            if (typeError != null) {
                plan.refused.put(key, refuse(s, ControlStatus.CONTROL_STATUS_FAILED, typeError));
                continue;
            }
            if (cap.getOptionsCount() > 0 && !offered(cap, s.getValue())) {
                plan.refused.put(key, refuse(s, ControlStatus.CONTROL_STATUS_UNSUPPORTED,
                        "not offered by this camera: " + optionNames(cap)));
                continue;
            }
            StringBuilder note = new StringBuilder();
            ControlValue value = clamp(cap, s.getValue(), note);
            plan.accepted.put(key, new Accepted(key, cap.getValueType(), s.getValue(), value, note.toString()));
        }
        // dependent modes: never flipped, a conflict unless in effect or set in this request
        for (String key : new ArrayList<>(plan.accepted.keySet())) {
            Object[] need = NEEDS.get(key);
            if (need == null) continue;
            String modeKey = (String) need[0];
            Accepted mode = plan.accepted.get(modeKey);
            ControlValue effective = mode != null ? mode.value : inEffect.get(modeKey);
            boolean ok = effective != null && effective.hasIntValue() && effective.getIntValue() == (Long) need[1];
            if (!ok && THREE_A.contains(modeKey)) {
                // all of 3A off (android.control.mode OFF) also hands these to the request
                Accepted cm = plan.accepted.get("android.control.mode");
                ControlValue controlMode = cm != null ? cm.value : inEffect.get("android.control.mode");
                ok = controlMode != null && controlMode.hasIntValue() && controlMode.getIntValue() == 0;
            }
            if (!ok) {
                Accepted a = plan.accepted.remove(key);
                String now = effective == null ? "unknown" : effective.hasIntValue() ? Long.toString(effective.getIntValue()) : "?";
                plan.refused.put(key, refuse(settingOf(a), ControlStatus.CONTROL_STATUS_FAILED,
                        "mode conflict: needs " + modeKey + " " + need[2] + " (in effect: " + now
                                + "); set it in the same request, the app never changes it on its own"));
            }
        }
        return plan;
    }

    /** The final result of an accepted setting, from the value read back from the capture result (or null). */
    static ControlResult result(Accepted a, ControlValue readBack) {
        ControlResult.Builder r = ControlResult.newBuilder().setKey(a.key).setRequested(a.requested);
        if (readBack == null) {
            return r.setStatus(a.clamped() ? ControlStatus.CONTROL_STATUS_CLAMPED : ControlStatus.CONTROL_STATUS_APPLIED)
                    .setNote(join(a.note, "set, but the camera doesn't report it in its capture results: not read back"))
                    .build();
        }
        r.setApplied(readBack);
        if (same(a.requested, readBack)) return r.setStatus(ControlStatus.CONTROL_STATUS_APPLIED).setNote("").build();
        if (same(a.value, readBack)) return r.setStatus(ControlStatus.CONTROL_STATUS_CLAMPED).setNote(a.note).build();
        boolean number = a.type == ControlValueType.CONTROL_VALUE_TYPE_INT || a.type == ControlValueType.CONTROL_VALUE_TYPE_FLOAT;
        if (number) {
            return r.setStatus(ControlStatus.CONTROL_STATUS_CLAMPED)
                    .setNote(join(a.note, "the camera applied a different value")).build();
        }
        // a flag, menu or list the camera didn't take: nothing to clamp, the setting failed
        return r.setStatus(ControlStatus.CONTROL_STATUS_FAILED)
                .setNote(join(a.note, "set, but the camera reports another value (applied) after "
                        + "the frames it had to take it")).build();
    }

    static ControlResult failed(Accepted a, String why) {
        return ControlResult.newBuilder().setKey(a.key).setRequested(a.requested)
                .setStatus(ControlStatus.CONTROL_STATUS_FAILED).setNote(why).build();
    }

    private static ControlSetting settingOf(Accepted a) {
        return ControlSetting.newBuilder().setKey(a.key).setValue(a.requested).build();
    }

    private static ControlResult refuse(ControlSetting s, ControlStatus status, String note) {
        return ControlResult.newBuilder().setKey(s.getKey()).setRequested(s.getValue()).setStatus(status)
                .setNote(note).build();
    }

    private static String typeError(ControlValueType type, ControlValue v) {
        switch (type) {
            case CONTROL_VALUE_TYPE_INT:
            case CONTROL_VALUE_TYPE_MENU:
                return v.hasIntValue() ? null : "expected int_value";
            case CONTROL_VALUE_TYPE_FLOAT:
                return v.hasFloatValue() ? null : "expected float_value";
            case CONTROL_VALUE_TYPE_FLAG:
                return v.hasFlagValue() && v.getFlagValue() != Flag.FLAG_UNSET ? null : "expected flag_value ON or OFF";
            case CONTROL_VALUE_TYPE_STRING:
                return v.hasTextValue() ? null : "expected text_value";
            case CONTROL_VALUE_TYPE_INT_LIST:
                return v.getIntValuesCount() > 0 ? null : "expected int_values";
            case CONTROL_VALUE_TYPE_FLOAT_LIST:
                return v.getFloatValuesCount() > 0 ? null : "expected float_values";
            default:
                return "the control's type is unknown";
        }
    }

    /** Menus by their value; INT_LIST options (fps ranges) by their "[lo, hi]" name. */
    private static boolean offered(ControlCapability cap, ControlValue v) {
        for (ControlMenuOption o : cap.getOptionsList()) {
            if (cap.getValueType() == ControlValueType.CONTROL_VALUE_TYPE_INT_LIST) {
                if (o.getName().equals(listName(v.getIntValuesList()))) return true;
            } else if (v.hasIntValue() && o.getValue() == v.getIntValue()) {
                return true;
            }
        }
        return false;
    }

    private static String listName(List<Long> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) sb.append(i > 0 ? ", " : "").append(xs.get(i));
        return sb.append(']').toString();
    }

    private static String optionNames(ControlCapability cap) {
        List<String> names = new ArrayList<>();
        for (ControlMenuOption o : cap.getOptionsList()) {
            names.add(cap.getValueType() == ControlValueType.CONTROL_VALUE_TYPE_INT_LIST ? o.getName()
                    : o.getValue() + " " + o.getName());
        }
        return String.join(", ", names);
    }

    private static ControlValue clamp(ControlCapability cap, ControlValue v, StringBuilder note) {
        if (cap.getValueType() == ControlValueType.CONTROL_VALUE_TYPE_INT && v.hasIntValue()) {
            long x = v.getIntValue(), c = x;
            if (cap.hasMinValue() && cap.getMinValue().hasIntValue()) c = Math.max(c, cap.getMinValue().getIntValue());
            if (cap.hasMaxValue() && cap.getMaxValue().hasIntValue()) c = Math.min(c, cap.getMaxValue().getIntValue());
            if (c != x) {
                note.append("clamped to the camera's range ").append(range(cap));
                return v.toBuilder().setIntValue(c).build();
            }
        } else if (cap.getValueType() == ControlValueType.CONTROL_VALUE_TYPE_FLOAT && v.hasFloatValue()) {
            float x = v.getFloatValue(), c = x;
            if (cap.hasMinValue() && cap.getMinValue().hasFloatValue()) c = Math.max(c, cap.getMinValue().getFloatValue());
            if (cap.hasMaxValue() && cap.getMaxValue().hasFloatValue()) c = Math.min(c, cap.getMaxValue().getFloatValue());
            if (c != x) {
                note.append("clamped to the camera's range ").append(range(cap));
                return v.toBuilder().setFloatValue(c).build();
            }
        }
        return v;
    }

    private static String range(ControlCapability cap) {
        return "[" + (cap.hasMinValue() ? text(cap.getMinValue()) : "?") + ", "
                + (cap.hasMaxValue() ? text(cap.getMaxValue()) : "?") + "]";
    }

    private static String text(ControlValue v) {
        if (v.hasIntValue()) return Long.toString(v.getIntValue());
        if (v.hasFloatValue()) return Float.toString(v.getFloatValue());
        return "?";
    }

    /** Equal values; floats within 0.01 % (Camera2 rounds them). */
    static boolean same(ControlValue a, ControlValue b) {
        if (a.hasFloatValue() && b.hasFloatValue()) return close(a.getFloatValue(), b.getFloatValue());
        if (a.getFloatValuesCount() > 0 && a.getFloatValuesCount() == b.getFloatValuesCount()) {
            for (int i = 0; i < a.getFloatValuesCount(); i++) {
                if (!close(a.getFloatValues(i), b.getFloatValues(i))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean close(float x, float y) {
        return Math.abs(x - y) <= 1e-4f * Math.max(1f, Math.max(Math.abs(x), Math.abs(y)));
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "; " + b;
    }
}
