package com.xpe.mobile.audio;

/** Pure helpers for converting Android audio presentation timestamps into PCM frames. */
public final class PcmPresentationClock {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private PcmPresentationClock() {
    }

    public static long timestampFrames(long timestampFramePosition,
                                       long timestampNanoTime, long nowNanoTime,
                                       int sampleRate, float speed, long maximumFrames) {
        long baseFrames = Integer.toUnsignedLong((int) timestampFramePosition);
        if (sampleRate <= 0 || !Float.isFinite(speed) || speed <= 0.0f) {
            return clamp(baseFrames, maximumFrames);
        }
        double deltaFrames = (nowNanoTime - timestampNanoTime) / NANOS_PER_SECOND
                * sampleRate * speed;
        double currentFrames = baseFrames + deltaFrames;
        if (!Double.isFinite(currentFrames)) return clamp(baseFrames, maximumFrames);
        return clamp((long) Math.floor(currentFrames), maximumFrames);
    }

    public static long wallClockFrames(long playbackStartNanoTime, long nowNanoTime,
                                       int sampleRate, float speed, long maximumFrames) {
        if (playbackStartNanoTime <= 0L || nowNanoTime <= playbackStartNanoTime) return 0L;
        return elapsedFrames(nowNanoTime - playbackStartNanoTime,
                sampleRate, speed, maximumFrames);
    }

    private static long elapsedFrames(long elapsedNanoTime, int sampleRate,
                                      float speed, long maximumFrames) {
        if (elapsedNanoTime <= 0L || sampleRate <= 0 || !Float.isFinite(speed)
                || speed <= 0.0f || maximumFrames <= 0L) return 0L;
        double frames = elapsedNanoTime / NANOS_PER_SECOND * sampleRate * speed;
        if (!Double.isFinite(frames) || frames <= 0.0) return 0L;
        return Math.min(maximumFrames, (long) Math.floor(frames));
    }

    private static long clamp(long value, long maximum) {
        long safeMaximum = Math.max(0L, maximum);
        return Math.max(0L, Math.min(value, safeMaximum));
    }
}
