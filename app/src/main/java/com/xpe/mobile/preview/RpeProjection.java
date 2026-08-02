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
     * <p>The gameplay viewport may be wider than the 3:2 RPE coordinate space. Using
     * independent X/Y scales distorts circles and changes the visible angle of judgment lines.
     */
    public static double uniformScale(double viewportWidth, double viewportHeight) {
        if (!Double.isFinite(viewportWidth) || !Double.isFinite(viewportHeight)
                || viewportWidth <= 0.0 || viewportHeight <= 0.0) {
            return 0.0;
        }
        return Math.min(viewportWidth / WIDTH, viewportHeight / HEIGHT);
    }

    public static float screenAngle(double rotationDegrees) {
        if (!Double.isFinite(rotationDegrees)) return 0f;
        return (float) -rotationDegrees;
    }
}
