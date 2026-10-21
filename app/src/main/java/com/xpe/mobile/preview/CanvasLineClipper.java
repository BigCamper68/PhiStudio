package com.xpe.mobile.preview;

import java.util.Arrays;

/** Bounds ordinary line geometry before Android Canvas tessellates a rotated quad. */
final class CanvasLineClipper {
    private CanvasLineClipper() { }

    static boolean needsClipping(double x, double y, double scaleX, double scaleY, double aspect) {
        // The normal line already extends three viewport widths on either side.
        // Keep that fast path, but do not submit the million-pixel local rectangles
        // produced by extended-event preroll (e.g. Micrology) to the GPU Canvas.
        return Math.abs(scaleX) * 6 > 8 || Math.abs(scaleY) * .005 > 8
                || Math.abs(x / 675) > 8 || Math.abs(y / 450 / aspect) > 8;
    }

    /** Y-up vertices in units of half the viewport width, already inside the viewport. */
    static double[] polygon(double x, double y, double rotation, double scaleX,
                            double scaleY, double aspect) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(rotation)
                || !Double.isFinite(scaleX) || !Double.isFinite(scaleY)
                || !Double.isFinite(aspect) || aspect <= 0) return new double[0];
        double halfLength = 6 * Math.abs(scaleX);
        double halfThickness = (Math.abs(scaleY - 1) > 1e-4 ? .0076 : .01) * Math.abs(scaleY) / 2;
        if (halfLength == 0 || halfThickness == 0) return new double[0];
        double cx = x / 675, cy = y / 450 / aspect;
        double radians = Math.toRadians(-PrprRotation.screenAngle(rotation));
        double cos = Math.cos(radians), sin = Math.sin(radians);
        // Intersect the small viewport with the four line half-planes. Starting
        // with the huge line corners would discard its thin dimension through
        // cancellation. The separate PrprLineClipper retains extreme f32 parity.
        double[] in = new double[16], out = new double[16];
        double[] viewport = {-1, -1 / aspect, 1, -1 / aspect, 1, 1 / aspect, -1, 1 / aspect};
        System.arraycopy(viewport, 0, in, 0, viewport.length);
        int count = 4;
        for (int plane = 0; plane < 4 && count > 0; plane++) {
            double nx = plane < 2 ? cos : -sin;
            double ny = plane < 2 ? sin : cos;
            double sign = (plane & 1) == 0 ? 1 : -1;
            double half = plane < 2 ? halfLength : halfThickness;
            int nextCount = 0;
            for (int i = 0; i < count; i++) {
                int previous = (i + count - 1) % count;
                double da = half - sign * (nx * (in[previous * 2] - cx) + ny * (in[previous * 2 + 1] - cy));
                double db = half - sign * (nx * (in[i * 2] - cx) + ny * (in[i * 2 + 1] - cy));
                boolean insideA = da >= 0, insideB = db >= 0;
                if (insideA != insideB) {
                    double t = da / (da - db);
                    out[nextCount * 2] = in[previous * 2] + t * (in[i * 2] - in[previous * 2]);
                    out[nextCount * 2 + 1] = in[previous * 2 + 1]
                            + t * (in[i * 2 + 1] - in[previous * 2 + 1]);
                    nextCount++;
                }
                if (insideB) {
                    out[nextCount * 2] = in[i * 2];
                    out[nextCount * 2 + 1] = in[i * 2 + 1];
                    nextCount++;
                }
            }
            double[] swap = in; in = out; out = swap; count = nextCount;
        }
        return count < 3 ? new double[0] : Arrays.copyOf(in, count * 2);
    }
}
