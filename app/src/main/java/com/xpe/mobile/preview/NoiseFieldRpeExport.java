package com.xpe.mobile.preview;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.NoiseField;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Phira-Pro Line2Area: guide PNG, RGB colorEvents and speed-start lifecycle codes.
 * Geometry is authored in the standard 1350x900 canvas (3:2). Other viewports
 * follow the player's canvas projection, rather than native screen percentages.
 */
public final class NoiseFieldRpeExport {
    public final List<JSONObject> lines = new ArrayList<>();
    public final JSONObject source;
    private static final double MAX_STEP = 1.0 / 120;
    // Per-channel curve budget in RPE canvas pixels; f32 arithmetic has its
    // own rounding floor. Final corner error is measured independently.
    private static final double PIXEL_ERROR = .0005;
    private static final int MAX_SAMPLES = 2000000;
    private final PrprTime time;
    private final double pixelError;
    private int samples;

    public NoiseFieldRpeExport(ChartDocument chart, String textureFolder) throws JSONException {
        this(chart, textureFolder, PIXEL_ERROR);
    }

    /** Reconstruct the exact old tracks when recovering a saved checkpoint 9 project. */
    static NoiseFieldRpeExport legacy(ChartDocument chart, String textureFolder) throws JSONException {
        return new NoiseFieldRpeExport(chart, textureFolder, .025);
    }

    private NoiseFieldRpeExport(ChartDocument chart, String textureFolder, double pixelError) throws JSONException {
        this.pixelError = pixelError;
        time = new PrprTime(chart);
        JSONArray original = new JSONArray();
        for (int i = 0; i < chart.noiseFields.size(); i++) {
            NoiseField field = chart.noiseFields.get(i);
            original.put(field.toExportJson());
            lines.add(line(field, i, textureFolder));
        }
        JSONArray bpms = new JSONArray();
        var changes = new ArrayList<>(chart.bpmChanges);
        changes.sort(java.util.Comparator.comparing(change -> change.startTime));
        for (var bpm : changes) bpms.put(bpm.toJson());
        source = new JSONObject().put("version", 2).put("baseLineCount", chart.judgeLines.size())
                .put("fields", original).put("bpmHash", NoiseFieldRpeSource.hash(bpms))
                .put("lineHash", NoiseFieldRpeSource.hash(new JSONArray(lines)));
    }

    public static boolean hasCustomColors(ChartDocument chart) {
        if (chart != null) for (NoiseField field : chart.noiseFields)
            if (field.color != NoiseField.DEFAULT_COLOR) return true;
        return false;
    }

    private JSONObject line(NoiseField field, int index, String folder) throws JSONException {
        if (field.appear > field.enable || field.enable > field.disable || field.disable > field.disappear)
            throw new JSONException("Noise Field " + (index + 1)
                    + ": Phira-Pro requires appear <= enable <= disable <= disappear; use native export for this lifetime");
        TreeSet<Double> times = new TreeSet<>();
        times.add(field.appear); times.add(field.disappear);
        times.add(field.enable); times.add(field.disable);
        // Exact knots and their left limits preserve Hold, Jump and duplicate-time steps.
        for (var keys : List.of(field.move, field.scale, field.rotate)) for (NoiseField.Key key : keys) {
            double t = (float) key.time;
            if (t >= field.appear && t <= field.disappear) {
                times.add(t);
                double before = Math.nextDown((float) t);
                if (before >= field.appear) times.add(before);
            }
        }
        if (pixelError < .025) for (var keys : List.of(field.move, field.scale, field.rotate)) {
            // Native easing interpolates a 101-entry table. Preserve its slope
            // changes explicitly instead of refining blindly across a table knot.
            for (int i = 0; i + 1 < keys.size(); i++) {
                NoiseField.Key key = keys.get(i);
                if ((key.easeX <= 0 || key.easeX >= 13) && (key.easeY <= 0 || key.easeY >= 13)) continue;
                double start = (float) key.time, duration = (double) (float) keys.get(i + 1).time - start;
                for (int step = 1; step < 100; step++) {
                    double t = start + duration * step / 100;
                    if (t > field.appear && t < field.disappear) times.add(t);
                }
            }
        }
        List<Point> points = new ArrayList<>();
        Point previous = null;
        for (double t : times) {
            Point next = point(field, t);
            if (previous == null) points.add(next);
            else subdivide(field, previous, next, points, 0);
            previous = next;
        }
        JSONObject layer = new JSONObject();
        String[] names = {"moveXEvents", "moveYEvents", "rotateEvents"};
        for (int channel = 0; channel < 3; channel++) layer.put(names[channel], events(points, channel));
        JSONArray speed = new JSONArray();
        double[] life = {field.appear, field.enable, field.disable, field.disappear};
        // Stable native order is significant when several phases share the same time.
        List<Integer> codes = new ArrayList<>(List.of(0, 1, 2, 3));
        codes.sort(java.util.Comparator.comparingDouble(i -> life[i]));
        for (int code : codes) speed.put(event(life[code], life[code], code + 1, code + 1));
        layer.put("speedEvents", speed);
        layer.put("alphaEvents", new JSONArray().put(event(0, 0, 255, 255)));
        int color = field.color;
        JSONArray rgb = new JSONArray().put((color >> 16) & 255).put((color >> 8) & 255).put(color & 255);
        JSONObject colorEvent = event(field.appear, field.appear, 0, 0).put("start", rgb).put("end", rgb);
        JSONObject extended = new JSONObject().put("scaleXEvents", events(points, 3))
                .put("scaleYEvents", events(points, 4))
                .put("colorEvents", new JSONArray().put(colorEvent));
        return new JSONObject().put("Name", "Noise Field " + (index + 1)).put("Group", 0)
                .put("Texture", folder + "/isSubtract" + (field.subtract ? "1" : "0") + ".png")
                .put("father", -1).put("rotateWithFather", false).put("bpmfactor", 1)
                .put("isCover", 1).put("zOrder", index).put("anchor", new JSONArray().put(.5).put(.5))
                .put("notes", new JSONArray()).put("numOfNotes", 0)
                .put("eventLayers", new JSONArray().put(layer)).put("extended", extended);
    }

    private Point point(NoiseField field, double t) throws JSONException {
        if (++samples > MAX_SAMPLES) throw new JSONException("Noise field export exceeds the animation sample limit");
        NoiseFieldFrame.Zone zone = NoiseFieldEvaluator.transform(field, (float) t, 1.5f);
        if (zone == null) throw new JSONException("Noise field has a non-finite animated transform");
        // The generated PNG is 1x1; image-line scale is pixels * 2 / 1350.
        // Keep precise export knots on the native f32 clock. Attaching a rounded
        // transform to an unrounded timestamp introduces an avoidable phase error.
        return new Point(pixelError < .025 ? (float) t : t, new double[]{zone.x * 675., zone.y * 675., -zone.rotation,
                zone.width * 675., zone.height * 675.});
    }

    private void subdivide(NoiseField field, Point a, Point b, List<Point> out, int depth) throws JSONException {
        double duration = b.t - a.t;
        if (duration <= 0) { out.add(b); return; }
        Point middle = point(field, (a.t + b.t) / 2);
        if (middle.t <= a.t || middle.t >= b.t) { out.add(b); return; }
        boolean split = duration > MAX_STEP && animated(a, b, middle);
        for (double part : new double[]{.25, .5, .75}) {
            Point test = part == .5 ? middle : point(field, a.t + duration * part);
            double ratio = pixelError < .025 ? (test.t - a.t) / duration : part;
            double angleError = Math.abs(test.v[2] - lerp(a.v[2], b.v[2], ratio));
            double extent = Math.max(Math.max(test.v[3], test.v[4]), 1);
            if ((angleError - rounding(a, b, test, 2)) * Math.PI / 180 * extent > pixelError) split = true;
            for (int c : new int[]{0, 1, 3, 4})
                if (Math.abs(test.v[c] - lerp(a.v[c], b.v[c], ratio)) > pixelError + rounding(a, b, test, c)) split = true;
        }
        // A native key boundary is one f32 ULP wide; do not smear it over a frame.
        if (split && depth < 24 && duration > Math.max(1e-8, Math.ulp((float) b.t))) {
            subdivide(field, a, middle, out, depth + 1);
            subdivide(field, middle, b, out, depth + 1);
        } else out.add(b);
    }

    private double rounding(Point a, Point b, Point test, int channel) {
        if (pixelError >= .025) return 0; // Exact legacy reconstruction.
        // Do not turn native f32 arithmetic noise into millions of tiny events.
        // This floor is separate from the geometric curve approximation budget.
        double magnitude = Math.max(Math.abs(test.v[channel]), Math.max(Math.abs(a.v[channel]), Math.abs(b.v[channel])));
        return 4 * Math.ulp((float) magnitude);
    }

    private static boolean animated(Point a, Point b, Point mid) {
        for (int c = 0; c < 5; c++) if (a.v[c] != b.v[c] || a.v[c] != mid.v[c]) return true;
        return false;
    }

    private JSONArray events(List<Point> points, int channel) throws JSONException {
        // Collapse runs that are constant/linear within the same error bound.
        List<Point> kept = new ArrayList<>();
        for (Point p : points) {
            while (kept.size() >= 2) {
                Point a = kept.get(kept.size() - 2), b = kept.get(kept.size() - 1);
                double part = (b.t - a.t) / (p.t - a.t);
                if (Math.abs(b.v[channel] - lerp(a.v[channel], p.v[channel], part)) > .00001) break;
                kept.remove(kept.size() - 1);
            }
            kept.add(p);
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i + 1 < kept.size(); i++) {
            Point a = kept.get(i), b = kept.get(i + 1);
            result.put(event(a.t, b.t, a.v[channel], b.v[channel]));
        }
        if (result.length() == 0) {
            Point p = points.get(0); result.put(event(p.t, p.t, p.v[channel], p.v[channel]));
        }
        return result;
    }

    private JSONObject event(double start, double end, double a, double b) throws JSONException {
        return new JSONObject().put("startTime", beat(start)).put("endTime", beat(end))
                .put("start", a).put("end", b).put("easingType", 1)
                .put("easingLeft", 0).put("easingRight", 1)
                .put("bezier", 0).put("bezierPoints", new JSONArray().put(0).put(0).put(0).put(0));
    }
    private JSONArray beat(double seconds) {
        return BeatTime.fromDouble(time.beat(seconds), 1000000000).toJson();
    }
    private static double lerp(double a, double b, double p) { return a + (b - a) * p; }
    private static final class Point {
        final double t; final double[] v;
        Point(double t, double[] v) { this.t = t; this.v = v; }
    }
}
