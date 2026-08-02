package com.xpe.mobile.preview;

/** Coordinate projection shared by the native preview renderer. */
public final class RpeProjection {
    public static final double WIDTH = 1350.0;
    public static final double HEIGHT = 900.0;

    private RpeProjection() {
    }

    /**
     * Returns one pixels-per-RPE-unit scale for both axes.
     *
     * <p>RPE horizontal coordinates are defined against a fixed 1350-unit screen width.
     * The player aspect ratio changes the vertically visible range; it must not expose extra
     * horizontal chart space. Width-based uniform scaling therefore preserves circles and
     * angles while keeping the intended left/right screen boundaries at x = +/-675.
     */
    public static double uniformScale(double viewportWidth, double viewportHeight) {
        if (!Double.isFinite(viewportWidth) || !Double.isFinite(viewportHeight)
                || viewportWidth <= 0.0 || viewportHeight <= 0.0) {
            return 0.0;
        }
        return viewportWidth / WIDTH;
    }

    public static float screenAngle(double rotationDegrees) {
        if (!Double.isFinite(rotationDegrees)) return 0f;
        return (float) -rotationDegrees;
    }
}
