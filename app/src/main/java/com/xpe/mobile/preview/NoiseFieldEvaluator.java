package com.xpe.mobile.preview;

import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.model.NoiseField.Key;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** PreviewBlockControl: scale around anchors, rotate around anchors, then move. */
public final class NoiseFieldEvaluator {
    private static final float[][] EASING = new float[15][101];
    static {
        for (int type = 0; type < 15; type++) for (int i = 0; i <= 100; i++) {
            float p = i / 100f;
            if (type == 0) EASING[type][i] = p;
            else if (type == 13) EASING[type][i] = 0;
            else if (type == 14) EASING[type][i] = 1;
            else {
                int n = (type - 1) / 3 + 2, direction = (type - 1) % 3;
                EASING[type][i] = direction == 0 ? (float) Math.pow(p, n)
                        : direction == 1 ? 1 - (float) Math.pow(1 - p, n)
                        : p < .5f ? (float) Math.pow(2 * p, n) / 2
                        : 1 - (float) Math.pow(2 - 2 * p, n) / 2;
            }
        }
    }
    private NoiseFieldEvaluator() { }
    public static float ease(int type, float progress) {
        float p = Math.max(0, Math.min(1, progress)) * 100;
        int index = Math.min(100, (int) p);
        float[] table = EASING[type >= 0 && type < 15 ? type : 0];
        return index == 100 ? table[100] : table[index] + (p - index) * (table[index + 1] - table[index]);
    }
    public static NoiseFieldFrame evaluate(List<NoiseField> fields, double seconds, double aspect) {
        if (fields.isEmpty()) return NoiseFieldFrame.EMPTY;
        float time = (float) seconds, ratio = (float) aspect;
        List<NoiseFieldFrame.Zone> zones = new ArrayList<>();
        for (NoiseField field : fields) {
            NoiseFieldFrame.Zone zone = sample(field, time, ratio);
            if (zone != null) zones.add(zone);
        }
        return new NoiseFieldFrame(time, ratio, zones, Collections.emptyList());
    }
    public static NoiseFieldFrame.Zone sample(NoiseField field, float time, float aspect) {
        if (time < (float) field.appear || time >= (float) field.disappear) return null;
        return geometry(field, time, aspect);
    }

    /** Editor selection guides need the same animated geometry outside the visible lifetime. */
    public static NoiseFieldFrame.Zone geometry(NoiseField field, float time, float aspect) {
        NoiseFieldFrame.Zone zone = transform(field, time, aspect);
        return zone == null || zone.width == 0 || zone.height == 0 ? null : zone;
    }

    /** Export also needs collapsed rectangles, so growth from zero remains continuous. */
    public static NoiseFieldFrame.Zone transform(NoiseField field, float time, float aspect) {
        // Original camera: orthographicSize=5. Preserve the native f32 world
        // arithmetic until the final width-normalized renderer projection.
        float sw = 10 * aspect, sh = 10;
        float left = (field.left - .5f) * sw, right = (field.right - .5f) * sw;
        float bottom = (field.bottom - .5f) * sh, top = (field.top - .5f) * sh;
        float baseX = (left + right) * .5f, baseY = (bottom + top) * .5f;
        float x = baseX, y = baseY, sx = 1, sy = 1, rotation = 0;
        int si = index(field.scale, time), ri = index(field.rotate, time), mi = index(field.move, time);
        if (si >= 0) {
            sx = value(field.scale, si, time, true); sy = value(field.scale, si, time, false);
            for (int k = 0; k <= si; k++) {
                if (k + 1 >= field.scale.size()) break;
                Key a = field.scale.get(k), b = field.scale.get(k + 1);
                float ax = (a.anchorX - .5f) * sw, ay = (a.anchorY - .5f) * sh;
                x = ax + (x - ax) * div(k == si ? sx : b.x, a.x);
                y = ay + (y - ay) * div(k == si ? sy : b.y, a.y);
            }
        }
        if (ri >= 0) {
            rotation = value(field.rotate, ri, time, true);
            for (int k = 0; k <= ri; k++) {
                if (k + 1 >= field.rotate.size()) break;
                Key a = field.rotate.get(k), b = field.rotate.get(k + 1);
                float delta = (k == ri ? rotation : b.x) - a.x;
                float rad = delta * .017453292f, c = (float) Math.cos(rad), s = (float) Math.sin(rad);
                float ax = (a.anchorX - .5f) * sw, ay = (a.anchorY - .5f) * sh;
                float dx = x - ax, dy = y - ay;
                x = ax + (dx * c - dy * s); y = ay + (dx * s + dy * c);
            }
        }
        if (mi >= 0) {
            x += (value(field.move, mi, time, true) - .5f) * sw - baseX;
            y += (value(field.move, mi, time, false) - .5f) * sh - baseY;
        }
        float width = Math.abs((right - left) * sx), height = Math.abs((top - bottom) * sy);
        if (!Float.isFinite(x + y + width + height + rotation)) return null;
        boolean active = time >= (float) field.enable && time < (float) field.disable;
        boolean ready = !active && time < (float) field.enable && time >= (float) field.enable - .5f;
        boolean fade = !((float) field.appear >= (float) field.enable && (float) field.appear < (float) field.disable);
        float opacity = !active && fade ? Math.max(0, Math.min(1, (time - (float) field.appear) / .5f)) : 1;
        return new NoiseFieldFrame.Zone(x * 2 / sw, y * 2 / sw, width * 2 / sw, height * 2 / sw,
                rotation, opacity, active, ready, field.subtract, field.color);
    }
    private static int index(List<Key> keys, float time) {
        int low = 0, high = keys.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if ((float) keys.get(middle).time <= time) low = middle + 1; else high = middle;
        }
        return low - 1;
    }
    private static float value(List<Key> keys, int index, float time, boolean x) {
        Key a = keys.get(index);
        float from = x ? a.x : a.y;
        if (index + 1 == keys.size()) return from;
        Key b = keys.get(index + 1);
        float progress = (time - (float) a.time) / ((float) b.time - (float) a.time);
        return from + ((x ? b.x : b.y) - from) * ease(x ? a.easeX : a.easeY, progress);
    }
    private static float div(float numerator, float denominator) {
        return Math.abs(denominator) < Math.max(Math.abs(denominator) * 1e-6f, Float.MIN_VALUE * 8)
                ? 1 : numerator / denominator;
    }
}
