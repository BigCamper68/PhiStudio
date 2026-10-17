package com.xpe.mobile.preview;

/** Default prpr square emitter, in normalized world units (noteScale = 1). */
final class PrprParticleMotion {
    // Preserve PhiStudio's bundled Phigros sprite diameter (256 * 6 / 8000).
    static final float FX_SCALE = .96f;
    double distance;
    private double elapsed;
    private double velocity = 2.5;
    private boolean initialized;

    static float opacity(float progress) {
        return Math.max(0, progress < .5f ? 1 - .6f * progress : 1.4f * (1 - progress));
    }

    void advance(double age) {
        if (!initialized || age < elapsed || age - elapsed > .25) {
            distance = 0;
            elapsed = 0;
            velocity = 2.5;
            initialized = true;
            // A sought-to effect has no display history. Reconstruct at 60 Hz.
            while (age - elapsed > 1.0 / 60.0) step(1.0 / 60.0);
        }
        step(Math.max(0, age - elapsed));
    }

    private void step(double dt) {
        velocity *= 1 - 6 * dt;
        distance += velocity * dt;
        elapsed += dt;
    }
}
