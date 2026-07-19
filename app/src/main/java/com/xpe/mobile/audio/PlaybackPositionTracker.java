package com.xpe.mobile.audio;

/**
 * Maintains a continuous editor clock while using MediaPlayer positions as phase observations.
 *
 * <p>MediaPlayer timestamps can briefly describe the pre-seek decoder position and some OEM
 * decoders re-anchor compressed audio timestamps in visible steps. Returning those samples
 * directly makes the chart grid jump even though audio playback itself is continuous. This
 * tracker instead advances from a monotonic system-clock anchor and applies only small phase
 * corrections when a timestamp or fallback position agrees with the expected seek neighborhood.
 */
public final class PlaybackPositionTracker {
    private static final long OBSERVATION_GRACE_NS = 100_000_000L;
    private static final long INITIAL_PRESENTATION_GATE_NS = 250_000_000L;
    private static final long OBSERVATION_TOLERANCE_MS = 250L;
    private static final double CORRECTION_GAIN = 0.08;
    private static final long MAX_CORRECTION_PER_SAMPLE_MS = 8L;

    private double clockAnchorMediaMs;
    private long clockAnchorSystemNs;
    private long observationAllowedAfterNs;
    private long lastReturnedPositionMs;
    private float clockRate;
    private boolean clockStarted;
    private boolean awaitingTimestampRebase;
    private boolean awaitingInitialPresentation;
    private long initialPresentationDeadlineNs;

    public void startAfterSeek(long mediaPositionMs, float playbackRate, long nowSystemNs) {
        clockAnchorMediaMs = Math.max(0L, mediaPositionMs);
        clockAnchorSystemNs = Math.max(0L, nowSystemNs);
        observationAllowedAfterNs = saturatingAdd(
                clockAnchorSystemNs, OBSERVATION_GRACE_NS);
        lastReturnedPositionMs = toLongPosition(clockAnchorMediaMs);
        clockRate = Float.isFinite(playbackRate) && playbackRate >= 0f
                ? playbackRate : 1f;
        clockStarted = true;
        awaitingTimestampRebase = true;
        awaitingInitialPresentation = mediaPositionMs <= 1L && clockRate > 0f;
        initialPresentationDeadlineNs = awaitingInitialPresentation
                ? saturatingAdd(clockAnchorSystemNs, INITIAL_PRESENTATION_GATE_NS) : 0L;
    }

    public void reset() {
        clockAnchorMediaMs = 0L;
        clockAnchorSystemNs = 0L;
        observationAllowedAfterNs = 0L;
        lastReturnedPositionMs = 0L;
        clockRate = 0f;
        clockStarted = false;
        awaitingTimestampRebase = false;
        awaitingInitialPresentation = false;
        initialPresentationDeadlineNs = 0L;
    }

    public long positionMillis(long timestampMediaTimeUs, long timestampSystemNs,
                               float timestampClockRate, long nowSystemNs,
                               long fallbackPositionMs) {
        long safeFallback = Math.max(0L, fallbackPositionMs);
        boolean timestampKnown = isTimestampKnown(
                timestampMediaTimeUs, timestampSystemNs, timestampClockRate)
                && (!clockStarted || Math.abs(timestampClockRate - clockRate) <= 0.05f);
        long timestampPosition = timestampKnown
                ? PlaybackTimestamp.positionMillis(
                timestampMediaTimeUs, timestampSystemNs, timestampClockRate,
                nowSystemNs, safeFallback)
                : safeFallback;

        if (!clockStarted || clockAnchorSystemNs <= 0L) {
            return timestampKnown ? timestampPosition : safeFallback;
        }
        if (nowSystemNs < clockAnchorSystemNs) return lastReturnedPositionMs;

        if (awaitingInitialPresentation) {
            boolean timestampAdvancedNearStart = timestampKnown
                    && timestampPosition > lastReturnedPositionMs
                    && absoluteDifference(timestampPosition, lastReturnedPositionMs)
                    <= OBSERVATION_TOLERANCE_MS;
            if (timestampAdvancedNearStart) {
                awaitingInitialPresentation = false;
                awaitingTimestampRebase = false;
                clockAnchorMediaMs = timestampPosition;
                clockAnchorSystemNs = nowSystemNs;
                lastReturnedPositionMs = timestampPosition;
                return timestampPosition;
            }
            if (nowSystemNs < initialPresentationDeadlineNs) {
                // A zero-position seek completes before compressed audio reaches the output on
                // some devices. Keep the chart at zero until MediaTimestamp proves presentation
                // has advanced, instead of starting the visual clock from the seek callback.
                clockAnchorSystemNs = nowSystemNs;
                return lastReturnedPositionMs;
            }
            awaitingInitialPresentation = false;
            awaitingTimestampRebase = false;
            long fallbackAnchor = Math.max(lastReturnedPositionMs, safeFallback);
            clockAnchorMediaMs = fallbackAnchor;
            clockAnchorSystemNs = nowSystemNs;
            lastReturnedPositionMs = fallbackAnchor;
            return fallbackAnchor;
        }

        double predicted = predictPositionMillis(nowSystemNs);
        double corrected = predicted;

        if (nowSystemNs >= observationAllowedAfterNs) {
            long observation = selectObservation(
                    predicted, timestampKnown, timestampPosition, safeFallback);
            if (observation >= 0L) {
                awaitingTimestampRebase = false;
                double correction = (observation - predicted) * CORRECTION_GAIN;
                correction = Math.max(-MAX_CORRECTION_PER_SAMPLE_MS,
                        Math.min(MAX_CORRECTION_PER_SAMPLE_MS, correction));
                corrected += correction;
            }
        }

        // Playback must never make the editor timeline run backwards. A negative correction
        // slows the next few frames instead of producing a visible jump toward an older sample.
        if (clockRate > 0f && corrected < lastReturnedPositionMs) {
            corrected = lastReturnedPositionMs;
        }
        corrected = Math.max(0.0, corrected);
        long rounded = toLongPosition(corrected);
        clockAnchorMediaMs = corrected;
        clockAnchorSystemNs = nowSystemNs;
        lastReturnedPositionMs = rounded;
        return rounded;
    }

    public boolean isAwaitingTimestampRebase() {
        return awaitingTimestampRebase;
    }

    private static long selectObservation(double predicted, boolean timestampKnown,
                                          long timestampPosition, long fallbackPosition) {
        boolean timestampNearClock = timestampKnown
                && absoluteDifference(timestampPosition, predicted)
                <= OBSERVATION_TOLERANCE_MS;
        boolean fallbackNearClock = absoluteDifference(fallbackPosition, predicted)
                <= OBSERVATION_TOLERANCE_MS;

        if (timestampNearClock && fallbackNearClock) {
            // MediaTimestamp is presentation-aware, so prefer it when both sources are sane.
            return timestampPosition;
        }
        if (timestampNearClock) return timestampPosition;
        if (fallbackNearClock) return fallbackPosition;
        return -1L;
    }

    private static boolean isTimestampKnown(long mediaTimeUs, long systemTimeNs,
                                             float clockRate) {
        return mediaTimeUs >= 0L && systemTimeNs > 0L
                && Float.isFinite(clockRate) && clockRate >= 0f;
    }

    private double predictPositionMillis(long nowSystemNs) {
        double elapsedMs = (nowSystemNs - clockAnchorSystemNs) / 1_000_000.0;
        double predicted = clockAnchorMediaMs + elapsedMs * clockRate;
        return Double.isFinite(predicted) ? predicted : Long.MAX_VALUE;
    }

    private static double absoluteDifference(long left, double right) {
        return Math.abs(left - right);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static long toLongPosition(double positionMs) {
        if (!Double.isFinite(positionMs) || positionMs >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(positionMs));
    }
}
