package com.xpe.mobile.preview;

import java.util.Arrays;

/** Clips the two f32 triangles emitted by macroquad's ordinary judge-line quad. */
final class PrprLineClipper {
    private PrprLineClipper() { }

    static boolean needsClipping(double scaleX, double scaleY) {
        // Beyond this precision, even the +/-1 clip planes can round away.
        return Math.ulp(6f * Math.abs((float) scaleX)) >= 1f
                || Math.ulp(.005f * Math.abs((float) scaleY)) >= 1f;
    }

    static float[][] polygons(double x, double y, double rotation, double scaleX,
                              double scaleY, double aspectRatio) {
        float aspect = (float) aspectRatio;
        float cos = PrprRotation.cos(rotation), sin = PrprRotation.sin(rotation);
        float sx = (float) scaleX, sy = (float) scaleY;
        float a = cos * sx, b = -sin * sy, c = sin * sx, d = cos * sy;
        float tx = (float) x * (2f / 1350f);
        float ty = ((float) y * (2f / 900f)) / aspect;
        float thickness = Math.abs(sy - 1f) > 1e-4f ? .0076f : .01f;
        // Keep the draw_line normal calculation and its vertex order.
        float half = 12f / (12f / (thickness * .5f));
        float[] quad = new float[8];
        for (int i = 0; i < 4; i++) {
            float vx = i < 2 ? -6f : 6f, vy = (i & 1) == 0 ? half : -half;
            quad[i * 2] = (a * vx + b * vy) + tx;
            quad[i * 2 + 1] = ((c * vx + d * vy) + ty) * aspect;
        }
        return new float[][]{triangle(quad, 0, 1, 2), triangle(quad, 2, 1, 3)};
    }

    private static float[] triangle(float[] quad, int a, int b, int c) {
        float[] in = new float[20], out = new float[20];
        int[] vertices = {a, b, c};
        for (int i = 0; i < 3; i++) {
            in[i * 2] = quad[vertices[i] * 2];
            in[i * 2 + 1] = quad[vertices[i] * 2 + 1];
            if (!Float.isFinite(in[i * 2]) || !Float.isFinite(in[i * 2 + 1])) return new float[0];
        }
        int count = 3;
        for (int step = 0; step < 4 && count > 0; step++) {
            // At extreme f32 extents, plane order changes the visible half.
            // Left then right matches the reported mobile Phira appearance;
            // the opposite order in the Mesa comparison kept the wrong side.
            int plane = step < 2 ? step ^ 1 : step;
            int axis = plane / 2;
            float sign = (plane & 1) == 0 ? 1f : -1f;
            int nextCount = 0;
            for (int i = 0; i < count; i++) {
                int previous = (i + count - 1) % count;
                float da = 1f - sign * in[previous * 2 + axis];
                float db = 1f - sign * in[i * 2 + axis];
                boolean insideA = da >= 0, insideB = db >= 0;
                if (insideA != insideB) {
                    // Anchor at the inside endpoint to retain the second boundary.
                    // Reversing this interpolation loses the second clip boundary.
                    int from = insideA ? previous : i, to = insideA ? i : previous;
                    float fromDistance = insideA ? da : db, toDistance = insideA ? db : da;
                    float t = fromDistance / (fromDistance - toDistance);
                    for (int j = 0; j < 2; j++)
                        out[nextCount * 2 + j] = in[from * 2 + j] + t * (in[to * 2 + j] - in[from * 2 + j]);
                    nextCount++;
                }
                if (insideB) {
                    out[nextCount * 2] = in[i * 2]; out[nextCount * 2 + 1] = in[i * 2 + 1]; nextCount++;
                }
            }
            float[] swap = in; in = out; out = swap; count = nextCount;
        }
        return Arrays.copyOf(in, count * 2);
    }
}
