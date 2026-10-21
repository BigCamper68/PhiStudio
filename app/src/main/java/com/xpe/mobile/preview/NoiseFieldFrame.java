package com.xpe.mobile.preview;

import com.xpe.mobile.model.NoiseField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable native block geometry shared by display, export and touch blocking. */
public final class NoiseFieldFrame {
    public static final NoiseFieldFrame EMPTY = new NoiseFieldFrame(0, 1.5f, Collections.emptyList(), Collections.emptyList());
    public final float seconds, aspect;
    public final List<Zone> zones;
    public final List<Touch> touches;
    public NoiseFieldFrame(float seconds, float aspect, List<Zone> zones, List<Touch> touches) {
        this.seconds = seconds; this.aspect = aspect;
        this.zones = Collections.unmodifiableList(new ArrayList<>(zones));
        this.touches = Collections.unmodifiableList(new ArrayList<>(touches));
    }
    public boolean visible() { return !zones.isEmpty() || !touches.isEmpty(); }

    public static final class Touch {
        public final float x, y, scale;
        public final boolean held;
        public Touch(float x, float y, float scale) { this(x, y, scale, true); }
        public Touch(float x, float y, float scale, boolean held) {
            this.x = x; this.y = y; this.scale = scale; this.held = held;
        }
    }
    public static final class Zone {
        /** Width-normalized, Y up: viewport is [-1,1] x [-1/aspect,1/aspect]. */
        public final float x, y, width, height, rotation, opacity, yScale;
        public final boolean active, ready, subtract;
        public final int color;
        Zone(float x, float y, float width, float height, float rotation, float opacity,
             boolean active, boolean ready, boolean subtract) {
            this(x, y, width, height, rotation, opacity, active, ready, subtract, NoiseField.DEFAULT_COLOR);
        }
        Zone(float x, float y, float width, float height, float rotation, float opacity,
             boolean active, boolean ready, boolean subtract, int color) {
            this(x, y, width, height, rotation, opacity, active, ready, subtract, color, 1);
        }
        Zone(float x, float y, float width, float height, float rotation, float opacity,
             boolean active, boolean ready, boolean subtract, int color, float yScale) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.rotation = rotation; this.opacity = opacity;
            this.active = active; this.ready = ready; this.subtract = subtract;
            this.color = color;
            this.yScale = yScale;
        }
        public float[] corners() {
            float rad = rotation * .017453292f, c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            float[] points = new float[8];
            for (int i = 0; i < 4; i++) {
                float dx = (i == 0 || i == 3 ? -.5f : .5f) * width;
                float dy = (i < 2 ? -.5f : .5f) * height;
                points[i * 2] = x + dx * c - dy * s;
                points[i * 2 + 1] = yScale == 1 ? y + dx * s + dy * c : y + (dx * s + dy * c) * yScale;
            }
            return points;
        }
        boolean contains(float px, float py, float inset) {
            if (width <= 0 || height <= 0) return false;
            float rad = rotation * .017453292f, c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            float dx = px - x, dy = (py - y) / yScale;
            float sign = subtract ? 1 : -1;
            float hx = .5f + sign * Math.min(.25f, inset / width);
            float hy = .5f + sign * Math.min(.25f, inset / height);
            return Math.abs((c * dx + s * dy) / width) <= hx
                    && Math.abs((-s * dx + c * dy) / height) <= hy;
        }
    }

    public boolean blocks(float x, float y) {
        boolean normal = false, insetNormal = false;
        int subtract = 0, insetSubtract = 0;
        float inset = .05f * 2 / aspect;
        for (Zone zone : zones) {
            if (!zone.active) continue;
            if (zone.contains(x, y, 0)) {
                if (zone.subtract) subtract++; else normal = true;
            }
            if (zone.contains(x, y, inset)) {
                if (zone.subtract) insetSubtract++; else insetNormal = true;
            }
        }
        return normal != ((subtract & 1) != 0) && insetNormal != ((insetSubtract & 1) != 0);
    }
}
