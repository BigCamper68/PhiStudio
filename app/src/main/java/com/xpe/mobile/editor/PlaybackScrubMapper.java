package com.xpe.mobile.editor;

/** Pure mapping helpers for the editor's audio scrub bar. */
public final class PlaybackScrubMapper {
    private PlaybackScrubMapper() {
    }

    public static double fractionForPosition(long positionMs, long durationMs) {
        if (durationMs <= 0L) return 0.0;
        return clamp(positionMs / (double) durationMs);
    }

    public static long positionForFraction(double fraction, long durationMs) {
        if (durationMs <= 0L) return 0L;
        return Math.round(clamp(fraction) * durationMs);
    }

    public static double fractionForX(float x, float left, float right) {
        if (!(right > left)) return 0.0;
        return clamp((x - left) / (double) (right - left));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
