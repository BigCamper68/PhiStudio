package com.xpe.mobile.noiseeditor;

import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.preview.NoiseFieldFrame;
import java.util.ArrayList;
import java.util.List;

/** Read-only editor information; uses the same f32 time boundaries as the native renderer. */
public final class NoiseFieldPresentation {
    public enum State { OUTSIDE, INACTIVE, READY, ACTIVE }
    private NoiseFieldPresentation() { }

    public static State state(NoiseField field, double seconds) {
        float time = (float) seconds;
        if (time < (float) field.appear || time >= (float) field.disappear) return State.OUTSIDE;
        if (time >= (float) field.enable && time < (float) field.disable) return State.ACTIVE;
        if (time < (float) field.enable && time >= (float) field.enable - .5f) return State.READY;
        return State.INACTIVE;
    }

    /** Clip individual outline edges, never create an artificial edge along the viewport. */
    public static List<double[]> outline(NoiseFieldFrame.Zone zone, double aspect) {
        List<double[]> edges = new ArrayList<>(4);
        if (zone == null || !Double.isFinite(aspect) || aspect <= 0) return edges;
        double rad = zone.rotation * .017453292f, c = Math.cos(rad), s = Math.sin(rad);
        double[] corners = new double[8];
        for (int i = 0; i < 4; i++) {
            double dx = (i == 0 || i == 3 ? -.5 : .5) * zone.width;
            double dy = (i < 2 ? -.5 : .5) * zone.height;
            corners[i * 2] = zone.x + dx * c - dy * s;
            corners[i * 2 + 1] = zone.y + dx * s + dy * c;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double[] edge = clip(corners[i * 2], corners[i * 2 + 1], corners[j * 2], corners[j * 2 + 1], aspect);
            if (edge != null) edges.add(edge);
        }
        return edges;
    }

    private static double[] clip(double ax, double ay, double bx, double by, double aspect) {
        if (!Double.isFinite(ax + ay + bx + by)) return null;
        double dx = bx - ax, dy = by - ay, from = 0, to = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {ax + 1, 1 - ax, ay + 1 / aspect, 1 / aspect - ay};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return null; continue; }
            double t = q[i] / p[i];
            if (p[i] < 0) from = Math.max(from, t); else to = Math.min(to, t);
            if (from > to) return null;
        }
        // Bound even the last rounding error before passing coordinates to Android Canvas.
        return new double[] {bound(ax + from * dx, 1), bound(ay + from * dy, 1 / aspect),
                bound(ax + to * dx, 1), bound(ay + to * dy, 1 / aspect)};
    }
    private static double bound(double value, double half) { return Math.max(-half, Math.min(half, value)); }
}
