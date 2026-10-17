package com.xpe.mobile.preview;

/** Float arithmetic and solver tolerances used for prpr playback curves. */
final class PrprBezier {
    private PrprBezier() { }

    static float integral(float x, double[] points) {
        // GeneralIntTween uses three-point Gauss-Legendre quadrature, including
        // the same approximate inverse used by the visual animation.
        float radius = x / 2;
        float sum = 0;
        sum += (5f / 9) * value(radius * (1 - .7745967f), points);
        sum += (8f / 9) * value(radius, points);
        sum += (5f / 9) * value(radius * (1 + .7745967f), points);
        return radius * sum;
    }

    static float value(float x, double[] points) {
        float x1 = (float) points[0], x2 = (float) points[2];
        float t = x;
        if (x != 0 && x != 1) {
            // The engine chooses a table cell from x, rather than searching the
            // table's values. Retain that choice, including pre-roll extrapolation.
            int cell = Math.max(0, Math.min(19, (int) (x / .05f)));
            float left = sample(x1, x2, cell * .05f);
            float right = sample(x1, x2, (cell + 1) * .05f);
            t = .05f * (cell + (x - left) / (right - left));
            float slope = slope(x1, x2, t);
            if (slope >= .001f) {
                for (int i = 0; i < 4; i++) {
                    slope = slope(x1, x2, t);
                    if (slope <= 1e-7f) break;
                    t -= (sample(x1, x2, t) - x) / slope;
                }
            } else if (slope > 1e-7f) {
                float low = cell * .05f, high = (cell + 1) * .05f;
                t = (low + high) / 2;
                for (int i = 0; i < 10; i++) {
                    float error = sample(x1, x2, t) - x;
                    if (Math.abs(error) <= 1e-7f) break;
                    if (error > 0) high = t; else low = t;
                    t = (low + high) / 2;
                }
            }
        }
        return sample((float) points[1], (float) points[3], t);
    }

    private static float sample(float a, float b, float t) {
        float cubic = (a - b) * 3 + 1, quadratic = b * 3 - a * 6, linear = a * 3;
        return ((cubic * t + quadratic) * t + linear) * t;
    }

    private static float slope(float a, float b, float t) {
        float cubic = (a - b) * 3 + 1, quadratic = b * 3 - a * 6, linear = a * 3;
        return (cubic * 3 * t + quadratic * 2) * t + linear;
    }
}
