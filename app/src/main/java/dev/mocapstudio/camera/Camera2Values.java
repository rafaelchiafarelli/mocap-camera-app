package dev.mocapstudio.camera;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.TonemapCurve;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera2 values in the plain form ControlCatalog reads, keyed by their Camera2 names: Range → Span,
 * Rational → Float, Rect → int[4], Size → int[2], MeteringRectangle[] → int[] (x, y, w, h, weight per region),
 * RggbChannelVector → float[4], ColorSpaceTransform → float[9], TonemapCurve → float[] (R, G, B points),
 * byte[]/boolean[] → int[], double[] → float[]; anything else unknown → its text.
 */
final class Camera2Values {
    private Camera2Values() { }

    static Map<String, Object> characteristics(CameraCharacteristics c) {
        Map<String, Object> out = new HashMap<>();
        for (CameraCharacteristics.Key<?> k : c.getKeys()) {
            Object v = c.get(k);
            if (v != null) out.put(k.getName(), normalize(v));
        }
        Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (step != null) out.put(ControlCatalog.AE_COMPENSATION_STEP, step.toString());  // "1/2", exact
        return out;
    }

    static List<String> requestKeys(CameraCharacteristics c) {
        List<String> out = new ArrayList<>();
        for (CaptureRequest.Key<?> k : c.getAvailableCaptureRequestKeys()) out.add(k.getName());
        return out;
    }

    /** The values a capture result reports, for every key it has. */
    static Map<String, Object> values(CaptureResult r) {
        Map<String, Object> out = new HashMap<>();
        for (CaptureResult.Key<?> k : r.getKeys()) {
            Object v = r.get(k);
            if (v != null) out.put(k.getName(), normalize(v));
        }
        return out;
    }

    /** The values a request builder holds for the camera's request keys (the template's defaults). */
    static Map<String, Object> values(CaptureRequest.Builder b, CameraCharacteristics c) {
        Map<String, Object> out = new HashMap<>();
        for (CaptureRequest.Key<?> k : c.getAvailableCaptureRequestKeys()) {
            Object v = b.get(k);
            if (v != null) out.put(k.getName(), normalize(v));
        }
        return out;
    }

    static Object normalize(Object v) {
        if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof int[] || v instanceof long[] || v instanceof float[]) return v;
        if (v instanceof Range) {
            Range<?> r = (Range<?>) v;
            return new ControlCatalog.Span((Number) r.getLower(), (Number) r.getUpper());
        }
        if (v instanceof Range[]) {
            Range<?>[] rs = (Range<?>[]) v;
            ControlCatalog.Span[] out = new ControlCatalog.Span[rs.length];
            for (int i = 0; i < rs.length; i++) out[i] = (ControlCatalog.Span) normalize(rs[i]);
            return out;
        }
        if (v instanceof Rational) return ((Rational) v).floatValue();
        if (v instanceof Rational[]) {
            Rational[] rs = (Rational[]) v;
            float[] out = new float[rs.length];
            for (int i = 0; i < rs.length; i++) out[i] = rs[i].floatValue();
            return out;
        }
        if (v instanceof Rect) {
            Rect r = (Rect) v;
            return new int[]{r.left, r.top, r.right, r.bottom};
        }
        if (v instanceof Size) return new int[]{((Size) v).getWidth(), ((Size) v).getHeight()};
        if (v instanceof Size[]) {
            Size[] ss = (Size[]) v;
            int[] out = new int[ss.length * 2];
            for (int i = 0; i < ss.length; i++) { out[2 * i] = ss[i].getWidth(); out[2 * i + 1] = ss[i].getHeight(); }
            return out;
        }
        if (v instanceof MeteringRectangle[]) {
            MeteringRectangle[] ms = (MeteringRectangle[]) v;
            int[] out = new int[ms.length * 5];
            for (int i = 0; i < ms.length; i++) {
                out[5 * i] = ms[i].getX();
                out[5 * i + 1] = ms[i].getY();
                out[5 * i + 2] = ms[i].getWidth();
                out[5 * i + 3] = ms[i].getHeight();
                out[5 * i + 4] = ms[i].getMeteringWeight();
            }
            return out;
        }
        if (v instanceof RggbChannelVector) {
            RggbChannelVector g = (RggbChannelVector) v;
            return new float[]{g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue()};
        }
        if (v instanceof ColorSpaceTransform) {
            Rational[] m = new Rational[9];
            ((ColorSpaceTransform) v).copyElements(m, 0);
            return normalize(m);
        }
        if (v instanceof TonemapCurve) {
            TonemapCurve t = (TonemapCurve) v;
            List<Float> all = new ArrayList<>();
            for (int ch = TonemapCurve.CHANNEL_RED; ch <= TonemapCurve.CHANNEL_BLUE; ch++) {
                float[] pts = new float[t.getPointCount(ch) * TonemapCurve.POINT_SIZE];
                t.copyColorCurve(ch, pts, 0);
                for (float p : pts) all.add(p);
            }
            float[] out = new float[all.size()];
            for (int i = 0; i < out.length; i++) out[i] = all.get(i);
            return out;
        }
        if (v instanceof byte[]) {
            byte[] bs = (byte[]) v;
            int[] out = new int[bs.length];
            for (int i = 0; i < bs.length; i++) out[i] = bs[i];
            return out;
        }
        if (v instanceof boolean[]) {
            boolean[] bs = (boolean[]) v;
            int[] out = new int[bs.length];
            for (int i = 0; i < bs.length; i++) out[i] = bs[i] ? 1 : 0;
            return out;
        }
        if (v instanceof double[]) {
            double[] ds = (double[]) v;
            float[] out = new float[ds.length];
            for (int i = 0; i < ds.length; i++) out[i] = (float) ds[i];
            return out;
        }
        return String.valueOf(v);
    }
}
