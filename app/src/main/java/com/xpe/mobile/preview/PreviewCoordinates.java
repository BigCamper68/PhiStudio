package com.xpe.mobile.preview;

/** Maps the complete authored RPE coordinate area into any preview aspect ratio. */
public final class PreviewCoordinates {
    private PreviewCoordinates() { }

    public static double scaleX(double width) {
        return width / 1350.0;
    }

    public static double scaleY(double height) {
        return height / 900.0;
    }

    /** prpr: normalize local X by 675 and local Y by 450/aspect, THEN rotate.
     * Texture geometry remains uniformly width-scaled; travel distances do not.
     * Returns pixel offsets in the chart's Y-up coordinate system. */
    public static double localX(double x, double y, double rotation, double sx, double sy) {
        return PrprRotation.cos(rotation) * x * sx - PrprRotation.sin(rotation) * y * sy;
    }

    public static double localY(double x, double y, double rotation, double sx, double sy) {
        return PrprRotation.sin(rotation) * x * sx + PrprRotation.cos(rotation) * y * sy;
    }
}
