package com.xpe.mobile.audio;

public final class PlaybackTimestamp {
    private PlaybackTimestamp() {
    }

    public static long positionMillis(long anchorMediaTimeUs, long anchorSystemTimeNs,
                                      float clockRate, long nowSystemTimeNs,
                                      long fallbackPositionMs) {
        if (anchorMediaTimeUs < 0L || anchorSystemTimeNs <= 0L
                || !Float.isFinite(clockRate) || clockRate < 0f
                || nowSystemTimeNs < anchorSystemTimeNs) {
            return Math.max(0L, fallbackPositionMs);
        }
        double elapsedMs = (nowSystemTimeNs - anchorSystemTimeNs) / 1_000_000.0;
        double positionMs = anchorMediaTimeUs / 1000.0 + elapsedMs * clockRate;
        if (!Double.isFinite(positionMs) || positionMs >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0L, Math.round(positionMs));
    }
}
