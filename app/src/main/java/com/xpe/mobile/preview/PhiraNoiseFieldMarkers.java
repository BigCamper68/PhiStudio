/* SPDX-License-Identifier: GPL-3.0-only
 * Line2Area lifecycle/transform adaptation from Phira-Pro rpe_block.rs,
 * commit abf652d2bbf1e04e6e5e0619edd4d5623a52634c; see assets/noise-field/NOTICE.txt.
 */
package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;

/** Phira-Pro rpe_block.rs protocol: speed-start lifecycle codes and RGB colorEvents.
 * Image guides do not draw as judge lines. Native fields and markers share masks and input.
 */
public final class PhiraNoiseFieldMarkers {
    private static final Map<ChartDocument, Cache> CACHE = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private PhiraNoiseFieldMarkers() { }
    public static int kind(String path) {
        if (path == null) return -1;
        String name = path.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        return name.equalsIgnoreCase("isSubtract0.png") ? 0 : name.equalsIgnoreCase("isSubtract1.png") ? 1 : -1;
    }
    static NoiseFieldFrame evaluate(ChartDocument chart, PreparedChart prepared, double seconds, double aspect) {
        NoiseFieldFrame nativeFrame = NoiseFieldEvaluator.evaluate(chart.noiseFields, seconds, aspect);
        if (chart.noiseMarkerSizes.isEmpty()) return nativeFrame;
        Cache cache = CACHE.get(chart);
        if (cache == null || cache.prepared != prepared) {
            cache = new Cache(chart, prepared); CACHE.put(chart, cache);
        }
        List<NoiseFieldFrame.Zone> zones = new ArrayList<>(nativeFrame.zones);
        float stretch = (float) (1.5 / aspect);
        for (Marker marker : cache.markers) {
            int[] pixels = chart.noiseMarkerSizes.get(chart.judgeLines.get(marker.id).texture);
            if (pixels == null) continue;
            Span span = marker.at(seconds);
            if (span == null) continue;
            JudgeLine line = chart.judgeLines.get(marker.id);
            var pose = PreviewLineEvaluator.resolveSingleLineAtSeconds(chart, prepared, marker.id,
                    seconds, new boolean[chart.judgeLines.size()], 1);
            float sx = (float) PreviewLineEvaluator.lineScaleAtSeconds(line, line.extended, true, seconds, prepared.time);
            float sy = (float) PreviewLineEvaluator.lineScaleAtSeconds(line, line.extended, false, seconds, prepared.time);
            if (Math.abs(sx / 675) < 1e-6 || Math.abs(sy / 675) < 1e-6) continue;
            float width = pixels[0] * sx / 675, height = pixels[1] * sy / 675;
            float angle = (float) pose.rotationDegrees;
            float c = (float) Math.cos(angle * .017453292f), s = (float) Math.sin(angle * .017453292f);
            float dx = (.5f - marker.ax) * width, dy = (.5f - marker.ay) * height;
            float x = (float) pose.x / 675 + c * dx - s * dy;
            float y = ((float) pose.y / 675 + s * dx + c * dy) * stretch;
            if (!Float.isFinite(x + y + width + height + angle + stretch)) continue;
            int color = PrprEvents.colorAtSeconds(line.extended.colorEvents, prepared.time, seconds,
                    NoiseField.DEFAULT_COLOR & 0xffffff) | 0xff000000;
            boolean active = span.phase == 2;
            boolean ready = !active && seconds < span.times[1] && seconds >= span.times[1] - .5;
            zones.add(new NoiseFieldFrame.Zone(x, y, Math.abs(width), Math.abs(height), angle, 1,
                    active, ready, marker.invert, color, stretch));
        }
        return new NoiseFieldFrame((float) seconds, (float) aspect, zones, java.util.Collections.emptyList());
    }
    private static final class Cache {
        final PreparedChart prepared;
        final List<Marker> markers = new ArrayList<>();
        Cache(ChartDocument chart, PreparedChart prepared) {
            this.prepared = prepared;
            for (int i = 0; i < chart.judgeLines.size(); i++) {
                JudgeLine line = chart.judgeLines.get(i);
                int kind = kind(line.texture);
                if (kind >= 0) markers.add(new Marker(i, kind == 1, line, prepared));
            }
            markers.sort(Comparator.comparingInt((Marker m) -> chart.judgeLines.get(m.id).zOrder).thenComparingInt(m -> m.id));
        }
    }
    private static final class Span {
        final double start;
        double end;
        final int phase;
        double[] times;
        Span(double start, double end, int phase) { this.start = start; this.end = end; this.phase = phase; }
    }
    private static final class Marker {
        final int id;
        final boolean invert;
        float ax = .5f, ay = .5f;
        final List<Span> spans = new ArrayList<>();
        Marker(int id, boolean invert, JudgeLine line, PreparedChart prepared) {
            this.id = id; this.invert = invert;
            float[] anchor = line.imageAnchor(); ax = anchor[0]; ay = anchor[1];
            List<double[]> events = new ArrayList<>();
            for (var layer : line.eventLayers) for (var event : layer.events(EventType.SPEED))
                if (event.start >= 1 && event.start <= 4 && event.start == Math.rint(event.start))
                    events.add(new double[]{prepared.time.seconds(event.startTime.toDouble()), event.start});
            events.sort(Comparator.comparingDouble(e -> e[0]));
            if (events.isEmpty()) { fromAlpha(line, prepared); return; }
            double[] times = infinity();
            int previous = 0, first = 0;
            for (double[] event : events) {
                double t = event[0]; int code = (int) event[1];
                if (code <= previous && Double.isFinite(times[0])) {
                    finish(first, times, t); times = infinity(); first = spans.size();
                }
                if (!spans.isEmpty()) spans.get(spans.size() - 1).end = Math.min(spans.get(spans.size() - 1).end, t);
                if (code == 4) {
                    finish(first, times, t); times = infinity(); first = spans.size();
                } else {
                    times[0] = Math.min(times[0], t); times[code - 1] = t;
                    spans.add(new Span(t, Double.POSITIVE_INFINITY, code == 2 ? 2 : 1));
                }
                previous = code;
            }
            finish(first, times, Double.POSITIVE_INFINITY);
        }
        void finish(int first, double[] times, double end) {
            times[3] = end;
            for (int i = first; i < spans.size(); i++) {
                Span span = spans.get(i); span.end = Math.min(span.end, end); span.times = times.clone();
            }
        }
        private double alpha(PreparedChart prepared, double time) {
            return prepared.lines[id].valueAt(EventType.ALPHA, prepared.time.beat(time), time);
        }
        private int phase(double alpha) { return !Double.isFinite(alpha) || alpha <= 0 ? 0 : alpha <= 128 ? 1 : 2; }
        void fromAlpha(JudgeLine line, PreparedChart prepared) {
            TreeSet<Double> knots = new TreeSet<>(); knots.add(0.);
            for (var layer : line.eventLayers) for (var event : layer.events(EventType.ALPHA)) {
                knots.add(prepared.time.seconds(event.startTime.toDouble())); knots.add(prepared.time.seconds(event.endTime.toDouble()));
            }
            List<Double> keys = new ArrayList<>(knots);
            for (int i = 0; i + 1 < keys.size(); i++) {
                double a = keys.get(i), b = keys.get(i + 1), before = b - Math.min((b - a) * 1e-8, 1e-8);
                for (double threshold : new double[]{0, 128}) if ((alpha(prepared, a) > threshold) != (alpha(prepared, before) > threshold)) {
                    double lo = a, hi = before;
                    for (int step = 0; step < 40; step++) {
                        double mid = (lo + hi) / 2;
                        if ((alpha(prepared, mid) > threshold) == (alpha(prepared, a) > threshold)) lo = mid; else hi = mid;
                    }
                    knots.add(hi);
                }
            }
            keys = new ArrayList<>(knots);
            for (int i = 0; i < keys.size(); i++) {
                double start = keys.get(i), end = i + 1 < keys.size() ? keys.get(i + 1) : Double.POSITIVE_INFINITY;
                int state = phase(alpha(prepared, Double.isFinite(end) ? (start + end) / 2 : start));
                if (!spans.isEmpty() && spans.get(spans.size() - 1).phase == state) spans.get(spans.size() - 1).end = end;
                else spans.add(new Span(start, end, state));
            }
            for (int first = 0; first < spans.size();) {
                if (spans.get(first).phase == 0) { first++; continue; }
                int end = first; while (end < spans.size() && spans.get(end).phase != 0) end++;
                double disappear = end < spans.size() ? spans.get(end).start : Double.POSITIVE_INFINITY;
                double enable = Double.POSITIVE_INFINITY;
                for (int i = end - 1; i >= first; i--) {
                    Span span = spans.get(i); if (span.phase == 2) enable = span.start;
                    span.times = new double[]{spans.get(first).start, enable,
                            span.phase == 2 ? span.end : Double.POSITIVE_INFINITY, disappear};
                }
                boolean wasActive = false;
                for (int i = first; i < end; i++) {
                    Span span = spans.get(i);
                    if (wasActive) span.times[0] = Math.min(span.times[0], span.start - .5);
                    wasActive |= span.phase == 2;
                }
                first = end;
            }
        }
        Span at(double time) {
            int low = 0, high = spans.size();
            while (low < high) { int mid = (low + high) >>> 1; if (spans.get(mid).start <= time) low = mid + 1; else high = mid; }
            if (low == 0) return null;
            Span span = spans.get(low - 1);
            return span.phase == 0 || time >= span.end ? null : span;
        }
        static double[] infinity() { return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}; }
    }
}
