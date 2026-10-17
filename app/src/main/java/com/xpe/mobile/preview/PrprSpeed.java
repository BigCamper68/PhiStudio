package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.util.*;
import org.json.JSONArray;

/** The RPE floor keyframes, evaluated in the renderer's float domain. */
final class PrprSpeed {
    private static final double EPS = 1e-5;
    private static final float SPEED_RATIO = (float) (10.0 / 45.0 / .83175);
    private static final Curve LINEAR = x -> x;
    private static final Curve HOLD = x -> 0f;
    private final PrprTime time;
    private final List<Animation> layers = new ArrayList<>();

    PrprSpeed(ChartDocument chart, JudgeLine line, double maximumBeat, boolean modernPath, PrprTime time) {
        this(time, chart, line, time.seconds(maximumBeat), modernPath);
    }

    static PrprSpeed withMaximumSeconds(ChartDocument chart, JudgeLine line, double maximumSeconds,
                                        boolean modernPath, PrprTime time) {
        return new PrprSpeed(time, chart, line, maximumSeconds, modernPath);
    }

    static PrprSpeed withMaximumSeconds(ChartDocument chart, JudgeLine line, double maximumSeconds,
                                        boolean modernPath, PrprTime time, boolean preservedPec) {
        return new PrprSpeed(time, chart, line, maximumSeconds, modernPath, preservedPec);
    }

    private PrprSpeed(PrprTime time, ChartDocument chart, JudgeLine line, double maximum, boolean modernPath) {
        this(time, chart, line, maximum, modernPath, chart.hasPreservedPecHeights());
    }

    private PrprSpeed(PrprTime time, ChartDocument chart, JudgeLine line, double maximum,
                      boolean modernPath, boolean preservedPec) {
        this.time = time;
        if (preservedPec && buildPreservedPec(chart, line)) return;
        if (modernPath) buildModern(chart, line, maximum);
        else buildClassic(line, maximum);
    }

    private boolean buildPreservedPec(ChartDocument chart, JudgeLine line) {
        JSONArray table = chart.preservedPecHeights(line);
        if (table == null || table.length() == 0) return false;
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < table.length(); i++) {
            JSONArray pair = table.optJSONArray(i);
            if (pair == null || pair.length() != 2) return false;
            double seconds = pair.optDouble(0, Double.NaN); float value = (float) pair.optDouble(1, Double.NaN);
            if (!Double.isFinite(seconds) || !Float.isFinite(value)) return false;
            frames.add(new Frame(seconds, value, i + 1 == table.length() ? HOLD : LINEAR));
        }
        layers.add(new Animation(frames)); return true;
    }

    private void buildModern(ChartDocument chart, JudgeLine line, double maximum) {
        for (EventLayer layer : line.eventLayers) {
            List<LineEvent> events = new ArrayList<>(layer.events(EventType.SPEED));
            if (events.isEmpty()) continue;
            events.sort(Comparator.comparingDouble(e -> e.startTime.toDouble()));
            FloorBuilder builder = new FloorBuilder();
            double cursor = 0;
            float lastSpeed = 0;
            for (LineEvent event : events) {
                double start = Math.max(cursor, time.seconds(event.startTime.toDouble()));
                double end = Math.max(start, time.seconds(event.endTime.toDouble()));
                float from = event.normalizedSpeedStart(SPEED_RATIO), to = event.normalizedSpeedEnd(SPEED_RATIO);
                builder.push(cursor, start, LINEAR, lastSpeed);
                if (end > start + EPS) {
                    int easing = event.sourceSpeedEasing();
                    if (easing == 0) builder.push(start, end, LINEAR, from);
                    else if (easing <= 1) {
                        if (from * to < 0f) {
                            float x = from / (from - to);
                            double middle = start + (end - start) * x;
                            builder.push(start, middle, linearSpeed(from, 0f), midpoint(from, 0f));
                            builder.push(middle, end, linearSpeed(0f, to), midpoint(0f, to));
                        } else builder.push(start, end, linearSpeed(from, to), midpoint(from, to));
                    } else {
                        SpeedCurve curve = new SpeedCurve(event, chart.rpeVersion, from, to);
                        builder.push(start, end, curve.curve, curve.factor);
                    }
                }
                cursor = end;
                lastSpeed = to;
            }
            builder.push(cursor, maximum, LINEAR, lastSpeed);
            if (Math.abs(builder.frames.get(builder.frames.size() - 1).time - maximum) > EPS)
                builder.frames.add(new Frame(maximum, (float) builder.height, HOLD));
            layers.add(new Animation(builder.frames));
        }
    }

    // The classic parser preserves source order and samples interval ends 0.1 ms early.
    private void buildClassic(JudgeLine line, double maximum) {
        List<Animation> speeds = new ArrayList<>();
        TreeSet<Double> boundaries = new TreeSet<>();
        for (EventLayer layer : line.eventLayers) {
            List<Frame> frames = new ArrayList<>();
            for (LineEvent event : layer.events(EventType.SPEED)) {
                double start = time.seconds(event.startTime.toDouble()), end = time.seconds(event.endTime.toDouble());
                frames.add(new Frame(start, event.normalizedSpeedStart(SPEED_RATIO), LINEAR));
                frames.add(new Frame(end, event.normalizedSpeedEnd(SPEED_RATIO), HOLD));
                boundaries.add(start); boundaries.add(end);
            }
            if (!frames.isEmpty()) speeds.add(new Animation(frames));
        }
        if (speeds.isEmpty()) return;
        boundaries.add(maximum);
        List<Double> points = new ArrayList<>(boundaries);
        for (int i = 0; i + 1 < points.size(); i++) {
            double start = points.get(i), end = points.get(i + 1);
            float a = sampleSpeeds(speeds, start), b = sampleSpeeds(speeds, end - 1e-4);
            if (Math.signum(a) * Math.signum(b) < 0f)
                boundaries.add(start + (end - start) * (a / (a - b)));
        }
        points = new ArrayList<>(boundaries);
        List<Frame> frames = new ArrayList<>();
        double height = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            double start = points.get(i), end = points.get(i + 1);
            float a = sampleSpeeds(speeds, start), b = sampleSpeeds(speeds, end - 1e-4);
            frames.add(new Frame(start, (float) height, linearSpeed(a, b)));
            height += (double) (a + b) * (end - start) / 2;
        }
        if (!frames.isEmpty()) {
            frames.add(new Frame(maximum, (float) height, HOLD));
            layers.add(new Animation(frames));
        }
    }

    private static float sampleSpeeds(List<Animation> tracks, double seconds) {
        float sum = 0;
        // Anim::chain adds from the final layer back to the first, rounding every sum.
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Animation track = tracks.get(i);
            track.setTime(seconds);
            sum = track.now() + sum;
        }
        return sum;
    }

    double distanceAt(double beat) { return distanceAtSeconds(time.seconds(beat)); }

    double distanceAtSeconds(double seconds) {
        float result = 0;
        for (int i = layers.size() - 1; i >= 0; i--)
            result = layers.get(i).at(seconds) + result;
        return (double) result * 450;
    }

    private interface Curve { float at(float x); }

    private static final class Frame {
        final double time;
        float value;
        Curve curve;
        Frame(double time, float value, Curve curve) { this.time = time; this.value = value; this.curve = curve; }
    }

    private static final class Animation {
        final List<Frame> frames;
        final boolean sorted;
        double time;
        int cursor;
        Animation(List<Frame> frames) {
            this.frames = frames;
            boolean sorted = true;
            for (int i = 1; i < frames.size(); i++) sorted &= frames.get(i - 1).time <= frames.get(i).time;
            this.sorted = sorted;
        }
        void setTime(double seconds) {
            if (seconds == time) return;
            if (sorted) {
                int low = 0, high = frames.size();
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    if (frames.get(middle).time <= seconds) low = middle + 1; else high = middle;
                }
                cursor = Math.max(0, low - 1);
            } else {
                while (cursor + 1 < frames.size() && frames.get(cursor + 1).time <= seconds) cursor++;
                while (cursor > 0 && frames.get(cursor).time > seconds) cursor--;
            }
            time = seconds;
        }
        float now() { return atCursor(time, cursor); }
        float at(double seconds) { setTime(seconds); return now(); }
        float atCursor(double seconds, int i) {
            Frame first = frames.get(i);
            if (i + 1 == frames.size()) return first.value;
            Frame next = frames.get(i + 1);
            float progress = (float) ((seconds - first.time) / (next.time - first.time));
            return first.value + (next.value - first.value) * first.curve.at(progress);
        }
    }

    private static final class FloorBuilder {
        final List<Frame> frames = new ArrayList<>();
        double height;
        FloorBuilder() { frames.add(new Frame(0, 0f, LINEAR)); }
        void push(double start, double end, Curve curve, float factor) {
            if (end - start <= EPS) return;
            Frame last = frames.get(frames.size() - 1);
            if (Math.abs(last.time - start) < EPS) { last.value = (float) height; last.curve = curve; }
            else frames.add(new Frame(start, (float) height, curve));
            height += (double) factor * (end - start);
        }
    }

    private static float midpoint(float a, float b) {
        // f32::midpoint avoids overflowing the addition for finite authored speeds.
        if (Math.abs(a) <= Float.MAX_VALUE / 2 && Math.abs(b) <= Float.MAX_VALUE / 2) return (a + b) / 2f;
        if (Math.abs(a) < Float.MIN_NORMAL * 2) return a + b / 2f;
        if (Math.abs(b) < Float.MIN_NORMAL * 2) return a / 2f + b;
        return a / 2f + b / 2f;
    }

    private static Curve linearSpeed(float from, float to) {
        if (Math.abs(from - to) < EPS) return LINEAR;
        return Math.abs(from) > Math.abs(to)
                ? window(4, 0f, 1f - to / from) : window(5, from / to, 1f);
    }

    private static Curve window(int easing, float left, float right) {
        float from = PrprEasing.value(easing, left), to = PrprEasing.value(easing, right);
        return x -> (PrprEasing.value(easing, left + (right - left) * x) - from) / (to - from);
    }

    private static final class SpeedCurve {
        final Curve curve;
        final float factor;
        SpeedCurve(LineEvent event, int version, float from, float to) {
            int source = event.sourceSpeedEasing();
            int easing = source >= 1 && source <= 29 ? source : 1;
            float left = Math.max(0f, Math.min(1f, (float) event.easingLeft));
            float right = Math.max(0f, Math.min(1f, (float) event.easingRight));
            boolean clamped = easing > 1 && left < right
                    && !(Math.abs(left) < EPS && Math.abs(right - 1f) < EPS);
            Curve tween = event.bezier ? x -> PrprBezier.value(x, event.bezierPoints)
                    : clamped ? window(easing, left, right) : x -> PrprEasing.value(easing, x);
            float k, b;
            Curve integral;
            if (version < 170) {
                float df0 = derivative(tween, 0f), df1 = derivative(tween, 1f), denominator = df1 - df0;
                if (!Float.isFinite(denominator) || Math.abs(denominator) < 1e-8f) {
                    curve = linearSpeed(from, to); factor = (from + to) / 2f; return;
                }
                k = (to - from) / denominator;
                b = from - k * df0;
                integral = tween;
            } else {
                k = to - from; b = from;
                integral = event.bezier ? x -> PrprBezier.integral(x, event.bezierPoints)
                        : clamped ? integralWindow(easing, left, right) : x -> integral(easing, x);
            }
            float total = integral.at(1f) * k + b;
            if (!Float.isFinite(total) || Math.abs(total) < EPS) {
                curve = linearSpeed(from, to); factor = (from + to) / 2f;
            } else {
                factor = total;
                curve = x -> {
                    if (x <= 0f) return 0f;
                    if (x >= 1f) return 1f;
                    float value = (integral.at(x) * k + b * x) / total;
                    return Float.isFinite(value) ? value : x;
                };
            }
        }
    }

    private static float derivative(Curve curve, float x) {
        float left = Math.max(x - 1e-6f, 1e-7f), right = Math.min(x + 1e-6f, 1f - 1e-7f);
        return right <= left ? 0f : (curve.at(right) - curve.at(left)) / (right - left);
    }

    private static Curve integralWindow(int easing, float left, float right) {
        float from = PrprEasing.value(easing, left), to = PrprEasing.value(easing, right);
        float denominator = to - from, base = integral(easing, left);
        return x -> {
            if (!Float.isFinite(denominator) || Math.abs(denominator) < 1e-8f) return x * x / 2f;
            float at = left + (right - left) * x;
            float value = integral(easing, at) - base - from * (at - left);
            return value / ((right - left) * denominator);
        };
    }

    private static final int[] FAMILY = {0,0,1,1,2,2,1,2,3,3,4,4,3,4,5,5,6,6,7,7,8,8,7,8,9,9,10,10,10,9};
    private static final int[] DIRECTION = {0,0,1,0,1,0,2,2,1,0,1,0,2,2,1,0,1,0,1,0,1,0,2,2,1,0,1,0,2,2};
    private static final float PI = (float) Math.PI;
    private static final float LN2 = (float) Math.log(2);

    private static float integral(int easing, float x) {
        if (easing < 2 || easing >= FAMILY.length) return x * x / 2f;
        int family = FAMILY[easing];
        if (DIRECTION[easing] == 1) return x + integralIn(family, 1f - x) - integralIn(family, 1f);
        if (DIRECTION[easing] == 2) {
            float doubled = x * 2f;
            return doubled < 1f ? integralIn(family, doubled) / 4f : x - .5f + integralIn(family, 2f - doubled) / 4f;
        }
        return integralIn(family, x);
    }

    private static float power(float x, int n) {
        float value = 1f;
        while (n > 0) { if ((n & 1) != 0) value *= x; n >>>= 1; if (n > 0) x *= x; }
        return value;
    }

    private static float integralIn(int family, float x) {
        switch (family) {
            case 1: return x - (float) Math.sin(x * PI / 2f) * (2f / PI);
            case 2: return power(x, 3) / 3f;
            case 3: return power(x, 4) / 4f;
            case 4: return power(x, 5) / 5f;
            case 5: return power(x, 6) / 6f;
            case 6: return ((float) Math.pow(2f, 10f * x - 10f) - (float) Math.pow(2f, -10f)) / (10f * LN2);
            case 7: return x - .5f * (x * (float) Math.sqrt(1f - x * x) + (float) Math.asin(x));
            case 8: return ((1.70158f + 1f) * x / 4f - 1.70158f / 3f) * x * x * x;
            case 9: return elasticIntegral(x) - elasticIntegral(0f);
            case 10: return x - bounceIntegral(1f) + bounceIntegral(1f - x);
            default: return x * x / 2f;
        }
    }

    private static float elasticIntegral(float x) {
        float b = 2f * PI / 3f, u = 10f * x - 10f, v = (x * 10f - 10.75f) * b;
        return -((float) Math.pow(2f, u) / (10f * (LN2 * LN2 + b * b)))
                * (LN2 * (float) Math.sin(v) - b * (float) Math.cos(v));
    }

    private static float bouncePart(float u, float offset, float base) {
        return 7.5625f / 3f * power(u - offset, 3) + base * u;
    }

    private static float bounceIntegral(float u) {
        float end1 = 1f / 2.75f, end2 = 2f / 2.75f, end3 = 2.5f / 2.75f;
        float c2 = bouncePart(end1, 0f, 0f) - bouncePart(end1, 1.5f / 2.75f, .75f);
        float c3 = bouncePart(end2, 1.5f / 2.75f, .75f) + c2 - bouncePart(end2, 2.25f / 2.75f, .9375f);
        float c4 = bouncePart(end3, 2.25f / 2.75f, .9375f) + c3 - bouncePart(end3, 2.625f / 2.75f, .984375f);
        if (u < end1) return bouncePart(u, 0f, 0f);
        if (u < end2) return bouncePart(u, 1.5f / 2.75f, .75f) + c2;
        if (u < end3) return bouncePart(u, 2.25f / 2.75f, .9375f) + c3;
        return bouncePart(u, 2.625f / 2.75f, .984375f) + c4;
    }
}
