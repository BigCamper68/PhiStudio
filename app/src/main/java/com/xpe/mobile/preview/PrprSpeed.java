package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.util.*;

/** Scroll height in authored Y units, integrated in seconds (not beats). */
final class PrprSpeed {
    private static final double UNIT = 100.0 / 0.83175;
    private final PrprTime time;
    private final List<List<Segment>> layers = new ArrayList<>();

    PrprSpeed(ChartDocument chart, JudgeLine line, double maximumBeat, boolean modernPath, PrprTime time) {
        this.time = time;
        double maximum = time.seconds(maximumBeat);
        if (!modernPath) {
            buildClassic(line, maximum);
            return;
        }
        for (EventLayer layer : line.eventLayers) {
            List<LineEvent> events = new ArrayList<>(layer.events(EventType.SPEED));
            events.sort(Comparator.comparingDouble(e -> e.startTime.toDouble()));
            List<Segment> segments = new ArrayList<>();
            double cursor = 0, last = 0, height = 0;
            for (LineEvent event : events) {
                double start = Math.max(cursor, time.seconds(event.startTime.toDouble()));
                double end = Math.max(start, time.seconds(event.endTime.toDouble()));
                height = append(segments, cursor, start, height, last, last, null, chart.rpeVersion);
                height = append(segments, start, end, height, event.start, event.end, event, chart.rpeVersion);
                cursor = end;
                last = event.end;
            }
            append(segments, cursor, maximum, height, last, last, null, chart.rpeVersion);
            layers.add(segments);
        }
    }

    private double append(List<Segment> segments, double start, double end, double height,
                          double from, double to, LineEvent event, int version) {
        if (end - start <= 1e-5) return height;
        Segment segment = new Segment(start, end, height, from, to, event, version);
        segments.add(segment);
        return segment.at(end);
    }

    // The classic parser samples the endpoint 0.1 ms early, including across gaps
    // and sign changes. Keep that historical quirk instead of an ideal integral.
    private void buildClassic(JudgeLine line, double maximum) {
        TreeSet<Double> boundaries = new TreeSet<>();
        List<List<LineEvent>> tracks = new ArrayList<>();
        for (EventLayer layer : line.eventLayers) {
            List<LineEvent> events = new ArrayList<>(layer.events(EventType.SPEED));
            events.sort(Comparator.comparingDouble(e -> e.startTime.toDouble()));
            if (events.isEmpty()) continue;
            tracks.add(events);
            for (LineEvent event : events) {
                boundaries.add(time.seconds(event.startTime.toDouble()));
                boundaries.add(time.seconds(event.endTime.toDouble()));
            }
        }
        if (boundaries.isEmpty()) return;
        boundaries.add(maximum);
        List<Double> points = new ArrayList<>(boundaries);
        for (int i = 0; i + 1 < points.size(); i++) {
            double start = points.get(i), end = points.get(i + 1);
            double a = classicValue(tracks, start), b = classicValue(tracks, end - 1e-4);
            if (a * b < 0) boundaries.add(start + (end - start) * a / (a - b));
        }
        points = new ArrayList<>(boundaries);
        List<Segment> segments = new ArrayList<>();
        double height = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            double start = points.get(i), end = points.get(i + 1);
            height = append(segments, start, end, height, classicValue(tracks, start),
                    classicValue(tracks, end - 1e-4), null, 0);
        }
        layers.add(segments);
    }

    private double classicValue(List<List<LineEvent>> tracks, double seconds) {
        double sum = 0;
        for (List<LineEvent> events : tracks) {
            LineEvent current = events.get(0);
            for (LineEvent event : events) {
                if (time.seconds(event.startTime.toDouble()) > seconds) break;
                current = event;
            }
            double start = time.seconds(current.startTime.toDouble()), end = time.seconds(current.endTime.toDouble());
            sum += seconds >= end ? current.end : end <= start ? current.start
                    : current.start + (current.end - current.start) * (seconds - start) / (end - start);
        }
        return sum;
    }

    double distanceAt(double beat) {
        double seconds = time.seconds(beat), result = 0;
        for (List<Segment> segments : layers) {
            if (segments.isEmpty()) continue;
            int low = 0, high = segments.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (segments.get(mid).start <= seconds) low = mid + 1; else high = mid;
            }
            Segment segment = segments.get(Math.max(0, low - 1));
            result += segment.at(Math.min(seconds, segment.end));
        }
        return result * UNIT;
    }

    private static final class Segment {
        final double start, end, height, from, to;
        final LineEvent event;
        final int version, easing;
        final double legacyK, legacyB;
        final boolean linear;

        Segment(double start, double end, double height, double from, double to, LineEvent event, int version) {
            this.start = start; this.end = end; this.height = height;
            this.from = from; this.to = to; this.event = event; this.version = version;
            easing = event == null ? 1 : event.sourceSpeedEasing();
            double k = 0, b = from;
            boolean fallback = event == null || easing == 1;
            if (event != null && easing > 1 && version < 170) {
                double df0 = derivative(0), df1 = derivative(1), denominator = df1 - df0;
                fallback = !Double.isFinite(denominator) || Math.abs(denominator) < 1e-8;
                if (!fallback) {
                    k = (float) ((float) (to - from) / (float) denominator);
                    b = (float) from - (float) k * (float) df0;
                    double total = k * curve(1) + b;
                    fallback = !Double.isFinite(total) || Math.abs(total * UNIT / 450) < 1e-5;
                }
            } else if (event != null && easing > 1) {
                double total = from + (to - from) * integral(1);
                fallback = !Double.isFinite(total) || Math.abs(total * UNIT / 450) < 1e-5;
            }
            legacyK = k; legacyB = b; linear = fallback;
        }

        double curve(double t) {
            return PrprEvents.progress(t, easing, event.easingLeft, event.easingRight, event.bezier, event.bezierPoints);
        }
        double derivative(float t) {
            float left = Math.max(t - 1e-6f, 1e-7f), right = Math.min(t + 1e-6f, 1 - 1e-7f);
            return ((float) curve(right) - (float) curve(left)) / (right - left);
        }
        double integral(double t) {
            if (!event.bezier) {
                double left = Math.max(0, Math.min(1, event.easingLeft));
                double right = Math.max(0, Math.min(1, event.easingRight));
                if (left >= right || left < 1e-5 && Math.abs(right - 1) < 1e-5) return Easing.integral(easing, t);
                double from = PrprEasing.value(easing, (float) left), to = PrprEasing.value(easing, (float) right);
                if (Math.abs(to - from) < 1e-8) return t * t / 2;
                double x = left + (right - left) * t;
                return (Easing.integral(easing, x) - Easing.integral(easing, left) - from * (x - left))
                        / ((right - left) * (to - from));
            }
            return PrprBezier.integral((float) t, event.bezierPoints);
        }
        double at(double seconds) {
            double t = (seconds - start) / (end - start);
            if (t == 0) return height;
            double area;
            if (linear) area = from * t + (to - from) * t * t / 2;
            else if (easing == 0) area = from * t;
            else if (version < 170) area = (float) legacyK * (float) curve(t) + (float) legacyB * (float) t;
            else area = from * t + (to - from) * integral(t);
            return height + (end - start) * area;
        }
    }
}
