package com.xpe.mobile.model;

/** RPE easing identifiers used by current RPE charts and renderers. */
public final class Easing {
    public static final int MIN_TYPE = 1;
    public static final int MAX_TYPE = 29;

    private static final double EPSILON = 1.0e-12;

    private Easing() {
    }

    /** Evaluates one full RPE easing curve. Unknown identifiers fall back to Linear. */
    public static double apply(int type, double input) {
        double t = clamp(input);
        switch (type) {
            case 2: return Math.sin(t * Math.PI / 2.0); // Out Sine
            case 3: return 1.0 - Math.cos(t * Math.PI / 2.0); // In Sine
            case 4: return 1.0 - (1.0 - t) * (1.0 - t); // Out Quad
            case 5: return t * t; // In Quad
            case 6: return (1.0 - Math.cos(Math.PI * t)) / 2.0; // In Out Sine
            case 7: // In Out Quad
                return t < 0.5
                        ? 2.0 * t * t
                        : 1.0 - Math.pow(-2.0 * t + 2.0, 2.0) / 2.0;
            case 8: return 1.0 - Math.pow(1.0 - t, 3.0); // Out Cubic
            case 9: return t * t * t; // In Cubic
            case 10: return 1.0 - Math.pow(1.0 - t, 4.0); // Out Quart
            case 11: return Math.pow(t, 4.0); // In Quart
            case 12: // In Out Cubic
                return t < 0.5
                        ? 4.0 * t * t * t
                        : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
            case 13: // In Out Quart
                return t < 0.5
                        ? 8.0 * Math.pow(t, 4.0)
                        : 1.0 - Math.pow(-2.0 * t + 2.0, 4.0) / 2.0;
            case 14: return 1.0 - Math.pow(1.0 - t, 5.0); // Out Quint
            case 15: return Math.pow(t, 5.0); // In Quint
            case 16: // Out Expo
                return t == 1.0 ? 1.0 : 1.0 - Math.pow(2.0, -10.0 * t);
            case 17: // In Expo
                return t == 0.0 ? 0.0 : Math.pow(2.0, 10.0 * t - 10.0);
            case 18: return Math.sqrt(1.0 - Math.pow(t - 1.0, 2.0)); // Out Circ
            case 19: return 1.0 - Math.sqrt(1.0 - t * t); // In Circ
            case 20: { // Out Back
                double c1 = 1.70158;
                double c3 = c1 + 1.0;
                return 1.0 + c3 * Math.pow(t - 1.0, 3.0)
                        + c1 * Math.pow(t - 1.0, 2.0);
            }
            case 21: { // In Back
                double c1 = 1.70158;
                double c3 = c1 + 1.0;
                return c3 * t * t * t - c1 * t * t;
            }
            case 22: // In Out Circ
                return t < 0.5
                        ? (1.0 - Math.sqrt(1.0 - Math.pow(2.0 * t, 2.0))) / 2.0
                        : (Math.sqrt(1.0 - Math.pow(-2.0 * t + 2.0, 2.0)) + 1.0) / 2.0;
            case 23: { // In Out Back
                double c1 = 1.70158;
                double c2 = c1 * 1.525;
                return t < 0.5
                        ? Math.pow(2.0 * t, 2.0) * ((c2 + 1.0) * 2.0 * t - c2) / 2.0
                        : (Math.pow(2.0 * t - 2.0, 2.0)
                        * ((c2 + 1.0) * (2.0 * t - 2.0) + c2) + 2.0) / 2.0;
            }
            case 24: { // Out Elastic
                double c4 = (2.0 * Math.PI) / 3.0;
                return t == 0.0 ? 0.0 : t == 1.0 ? 1.0
                        : Math.pow(2.0, -10.0 * t)
                        * Math.sin((t * 10.0 - 0.75) * c4) + 1.0;
            }
            case 25: { // In Elastic
                double c4 = (2.0 * Math.PI) / 3.0;
                return t == 0.0 ? 0.0 : t == 1.0 ? 1.0
                        : -Math.pow(2.0, 10.0 * t - 10.0)
                        * Math.sin((t * 10.0 - 10.75) * c4);
            }
            case 26: return outBounce(t);
            case 27: return 1.0 - outBounce(1.0 - t);
            case 28:
                return t < 0.5
                        ? (1.0 - outBounce(1.0 - 2.0 * t)) / 2.0
                        : (1.0 + outBounce(2.0 * t - 1.0)) / 2.0;
            case 29: { // In Out Elastic (present in current RPE renderer mapping)
                if (t == 0.0 || t == 1.0) return t;
                double doubled = t * 2.0;
                return doubled < 1.0
                        ? inElastic(doubled) / 2.0
                        : 1.0 - inElastic(2.0 - doubled) / 2.0;
            }
            default: return t;
        }
    }

    /**
     * Evaluates a normalized slice of an RPE easing curve. The slice still maps its event's
     * start to 0 and end to 1, which is required after cutting a nonlinear event.
     */
    public static double applyWindowed(int type, double input, double left, double right) {
        double t = clamp(input);
        double normalizedLeft = validLeft(left, right);
        double normalizedRight = validRight(left, right);
        double start = apply(type, normalizedLeft);
        double end = apply(type, normalizedRight);
        double value = apply(type,
                normalizedLeft + (normalizedRight - normalizedLeft) * t);
        return normalizeWindow(t, start, end, value);
    }

    /**
     * Integrates a normalized RPE easing curve from zero to {@code input}. This mirrors the
     * antiderivatives used by Phira's RPE 1.7 speed-event path.
     */
    public static double integral(int type, double input) {
        double t = clamp(input);
        switch (type) {
            case 2: return outIntegral(Family.SINE, t);
            case 3: return inIntegral(Family.SINE, t);
            case 4: return outIntegral(Family.QUAD, t);
            case 5: return inIntegral(Family.QUAD, t);
            case 6: return inOutIntegral(Family.SINE, t);
            case 7: return inOutIntegral(Family.QUAD, t);
            case 8: return outIntegral(Family.CUBIC, t);
            case 9: return inIntegral(Family.CUBIC, t);
            case 10: return outIntegral(Family.QUART, t);
            case 11: return inIntegral(Family.QUART, t);
            case 12: return inOutIntegral(Family.CUBIC, t);
            case 13: return inOutIntegral(Family.QUART, t);
            case 14: return outIntegral(Family.QUINT, t);
            case 15: return inIntegral(Family.QUINT, t);
            case 16: return outIntegral(Family.EXPO, t);
            case 17: return inIntegral(Family.EXPO, t);
            case 18: return outIntegral(Family.CIRC, t);
            case 19: return inIntegral(Family.CIRC, t);
            case 20: return outIntegral(Family.BACK, t);
            case 21: return inIntegral(Family.BACK, t);
            case 22: return inOutIntegral(Family.CIRC, t);
            case 23: return inOutIntegral(Family.BACK, t);
            case 24: return outIntegral(Family.ELASTIC, t);
            case 25: return inIntegral(Family.ELASTIC, t);
            case 26: return outIntegral(Family.BOUNCE, t);
            case 27: return inIntegral(Family.BOUNCE, t);
            case 28: return inOutIntegral(Family.BOUNCE, t);
            case 29: return inOutIntegral(Family.ELASTIC, t);
            default: return t * t / 2.0;
        }
    }

    /** Integrates the normalized, windowed easing used by a cut RPE event. */
    public static double integralWindowed(int type, double input,
                                          double left, double right) {
        double t = clamp(input);
        double normalizedLeft = validLeft(left, right);
        double normalizedRight = validRight(left, right);
        double span = normalizedRight - normalizedLeft;
        double startValue = apply(type, normalizedLeft);
        double endValue = apply(type, normalizedRight);
        double valueSpan = endValue - startValue;
        if (!Double.isFinite(valueSpan) || Math.abs(valueSpan) < EPSILON
                || span < EPSILON) {
            return t * t / 2.0;
        }
        double x = normalizedLeft + span * t;
        double area = integral(type, x) - integral(type, normalizedLeft)
                - startValue * span * t;
        double normalized = area / (span * valueSpan);
        return Double.isFinite(normalized) ? normalized : t * t / 2.0;
    }

    /** Evaluates a CSS-style cubic Bézier easing curve with endpoints (0,0) and (1,1). */
    public static double applyCubicBezier(double input, double x1, double y1,
                                          double x2, double y2) {
        double x = clamp(input);
        if (x == 0.0 || x == 1.0) return x;

        // RPE's editor uses normalized easing X coordinates. Clamping malformed imported
        // control X values keeps preview/evaluation finite without mutating their raw JSON.
        double controlX1 = clamp(x1);
        double controlX2 = clamp(x2);
        double parameter = x;
        for (int iteration = 0; iteration < 8; iteration++) {
            double error = cubic(parameter, controlX1, controlX2) - x;
            if (Math.abs(error) < 1.0e-7) break;
            double derivative = cubicDerivative(parameter, controlX1, controlX2);
            if (Math.abs(derivative) < 1.0e-7) break;
            double candidate = parameter - error / derivative;
            if (candidate < 0.0 || candidate > 1.0) break;
            parameter = candidate;
        }

        double low = 0.0;
        double high = 1.0;
        for (int iteration = 0; iteration < 18; iteration++) {
            double currentX = cubic(parameter, controlX1, controlX2);
            if (Math.abs(currentX - x) < 1.0e-7) break;
            if (currentX < x) low = parameter;
            else high = parameter;
            parameter = (low + high) / 2.0;
        }
        return cubic(parameter, y1, y2);
    }

    /** Evaluates a normalized slice of a custom cubic Bézier easing curve. */
    public static double applyCubicBezierWindowed(double input, double left, double right,
                                                  double x1, double y1,
                                                  double x2, double y2) {
        double t = clamp(input);
        double normalizedLeft = validLeft(left, right);
        double normalizedRight = validRight(left, right);
        double start = applyCubicBezier(normalizedLeft, x1, y1, x2, y2);
        double end = applyCubicBezier(normalizedRight, x1, y1, x2, y2);
        double value = applyCubicBezier(
                normalizedLeft + (normalizedRight - normalizedLeft) * t,
                x1, y1, x2, y2);
        return normalizeWindow(t, start, end, value);
    }

    /**
     * Integrates a custom Bézier easing with the same three-point Gauss rule used by Phira
     * for general speed-event tweens.
     */
    public static double integralCubicBezierWindowed(
            double input, double left, double right,
            double x1, double y1, double x2, double y2) {
        double x = clamp(input);
        if (x == 0.0) return 0.0;
        double node = 0.7745966692414834;
        double radius = x / 2.0;
        double first = applyCubicBezierWindowed(
                radius * (1.0 - node), left, right, x1, y1, x2, y2);
        double middle = applyCubicBezierWindowed(
                radius, left, right, x1, y1, x2, y2);
        double last = applyCubicBezierWindowed(
                radius * (1.0 + node), left, right, x1, y1, x2, y2);
        return radius * (5.0 / 9.0 * first
                + 8.0 / 9.0 * middle + 5.0 / 9.0 * last);
    }

    private static double normalizeWindow(double t, double start, double end, double value) {
        double span = end - start;
        if (!Double.isFinite(value) || !Double.isFinite(span) || Math.abs(span) < EPSILON) {
            return t;
        }
        if (t == 0.0 || t == 1.0) return t;
        return (value - start) / span;
    }

    private static double validLeft(double left, double right) {
        return validWindow(left, right) ? clamp(left) : 0.0;
    }

    private static double validRight(double left, double right) {
        return validWindow(left, right) ? clamp(right) : 1.0;
    }

    private enum Family {
        SINE,
        QUAD,
        CUBIC,
        QUART,
        QUINT,
        EXPO,
        CIRC,
        BACK,
        ELASTIC,
        BOUNCE
    }

    private static double outIntegral(Family family, double x) {
        return x + inIntegral(family, 1.0 - x) - inIntegral(family, 1.0);
    }

    private static double inOutIntegral(Family family, double x) {
        double doubled = x * 2.0;
        return doubled < 1.0
                ? inIntegral(family, doubled) / 4.0
                : x - 0.5 + inIntegral(family, 2.0 - doubled) / 4.0;
    }

    private static double inIntegral(Family family, double x) {
        switch (family) {
            case SINE:
                return x - Math.sin(x * Math.PI / 2.0) * 2.0 / Math.PI;
            case QUAD:
                return Math.pow(x, 3.0) / 3.0;
            case CUBIC:
                return Math.pow(x, 4.0) / 4.0;
            case QUART:
                return Math.pow(x, 5.0) / 5.0;
            case QUINT:
                return Math.pow(x, 6.0) / 6.0;
            case EXPO:
                return (Math.pow(2.0, 10.0 * x - 10.0) - Math.pow(2.0, -10.0))
                        / (10.0 * Math.log(2.0));
            case CIRC:
                return x - 0.5 * (x * Math.sqrt(Math.max(0.0, 1.0 - x * x))
                        + Math.asin(x));
            case BACK: {
                double c1 = 1.70158;
                double c3 = c1 + 1.0;
                return (c3 * x / 4.0 - c1 / 3.0) * x * x * x;
            }
            case ELASTIC:
                return elasticAntiderivative(x) - elasticAntiderivative(0.0);
            case BOUNCE:
                return x - bounceAntiderivative(1.0)
                        + bounceAntiderivative(1.0 - x);
            default:
                return x * x / 2.0;
        }
    }

    private static double elasticAntiderivative(double x) {
        double c4 = 2.0 * Math.PI / 3.0;
        double a = Math.log(2.0);
        double u = 10.0 * x - 10.0;
        double v = (x * 10.0 - 10.75) * c4;
        return -Math.pow(2.0, u) / (10.0 * (a * a + c4 * c4))
                * (a * Math.sin(v) - c4 * Math.cos(v));
    }

    private static double bounceAntiderivative(double x) {
        double n1 = 7.5625;
        double d1 = 2.75;
        double end1 = 1.0 / d1;
        double end2 = 2.0 / d1;
        double end3 = 2.5 / d1;
        double value1 = n1 / 3.0 * Math.pow(end1, 3.0);
        double c2 = value1 - (n1 / 3.0 * Math.pow(end1 - 1.5 / d1, 3.0)
                + 0.75 * end1);
        double value2 = n1 / 3.0 * Math.pow(end2 - 1.5 / d1, 3.0)
                + 0.75 * end2 + c2;
        double c3 = value2 - (n1 / 3.0 * Math.pow(end2 - 2.25 / d1, 3.0)
                + 0.9375 * end2);
        double value3 = n1 / 3.0 * Math.pow(end3 - 2.25 / d1, 3.0)
                + 0.9375 * end3 + c3;
        double c4 = value3 - (n1 / 3.0 * Math.pow(end3 - 2.625 / d1, 3.0)
                + 0.984375 * end3);
        if (x < end1) return n1 / 3.0 * Math.pow(x, 3.0);
        if (x < end2) {
            return n1 / 3.0 * Math.pow(x - 1.5 / d1, 3.0) + 0.75 * x + c2;
        }
        if (x < end3) {
            return n1 / 3.0 * Math.pow(x - 2.25 / d1, 3.0) + 0.9375 * x + c3;
        }
        return n1 / 3.0 * Math.pow(x - 2.625 / d1, 3.0) + 0.984375 * x + c4;
    }

    private static boolean validWindow(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right)
                && clamp(right) >= clamp(left);
    }

    private static double cubic(double t, double control1, double control2) {
        double inverse = 1.0 - t;
        return 3.0 * inverse * inverse * t * control1
                + 3.0 * inverse * t * t * control2
                + t * t * t;
    }

    private static double cubicDerivative(double t, double control1, double control2) {
        double inverse = 1.0 - t;
        return 3.0 * inverse * inverse * control1
                + 6.0 * inverse * t * (control2 - control1)
                + 3.0 * t * t * (1.0 - control2);
    }

    private static double outBounce(double t) {
        double n1 = 7.5625;
        double d1 = 2.75;
        if (t < 1.0 / d1) return n1 * t * t;
        if (t < 2.0 / d1) {
            t -= 1.5 / d1;
            return n1 * t * t + 0.75;
        }
        if (t < 2.5 / d1) {
            t -= 2.25 / d1;
            return n1 * t * t + 0.9375;
        }
        t -= 2.625 / d1;
        return n1 * t * t + 0.984375;
    }

    private static double inElastic(double t) {
        if (t == 0.0 || t == 1.0) return t;
        double c4 = (2.0 * Math.PI) / 3.0;
        return -Math.pow(2.0, 10.0 * t - 10.0)
                * Math.sin((t * 10.0 - 10.75) * c4);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
