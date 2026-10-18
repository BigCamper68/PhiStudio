package com.xpe.mobile.preview;

/** Rotation2<f32>: convert the authored angle before evaluating trigonometry. */
final class PrprRotation {
    private PrprRotation() { }

    static float radians(double degrees) {
        return (float) degrees * ((float) Math.PI / 180f);
    }

    static float cos(double degrees) { return (float) Math.cos(radians(degrees)); }
    static float sin(double degrees) { return (float) Math.sin(radians(degrees)); }

    static float screenAngle(double degrees) {
        // Canvas must receive the orientation of the native f32 matrix. Passing
        // billions of degrees to Skia performs a different argument reduction.
        return (float) -Math.toDegrees(Math.atan2(sin(degrees), cos(degrees)));
    }
}
