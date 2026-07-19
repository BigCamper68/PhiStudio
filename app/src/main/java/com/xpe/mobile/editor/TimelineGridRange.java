package com.xpe.mobile.editor;

/** Calculates the inclusive subdivision-step range visible in a vertical beat timeline. */
public final class TimelineGridRange {
    public static final class Range {
        public final int firstStep;
        public final int lastStep;

        Range(int firstStep, int lastStep) {
            this.firstStep = firstStep;
            this.lastStep = lastStep;
        }
    }

    private TimelineGridRange() {
    }

    public static Range visible(double currentBeat, float centerY, float top, float bottom,
                                float pixelsPerBeat, int subdivision) {
        if (!Double.isFinite(currentBeat) || !Float.isFinite(centerY)
                || !Float.isFinite(top) || !Float.isFinite(bottom)
                || !Float.isFinite(pixelsPerBeat) || pixelsPerBeat <= 0f
                || subdivision <= 0) {
            throw new IllegalArgumentException("Timeline geometry must be finite and positive");
        }
        double topBeat = currentBeat + (centerY - top) / pixelsPerBeat;
        double bottomBeat = currentBeat + (centerY - bottom) / pixelsPerBeat;
        double minimumBeat = Math.min(topBeat, bottomBeat);
        double maximumBeat = Math.max(topBeat, bottomBeat);
        int firstBase = safeFloor(minimumBeat * subdivision);
        int lastBase = safeCeil(maximumBeat * subdivision);
        int first = firstBase == Integer.MIN_VALUE ? Integer.MIN_VALUE : firstBase - 1;
        int last = lastBase == Integer.MAX_VALUE ? Integer.MAX_VALUE : lastBase + 1;
        return new Range(Math.max(0, first), Math.max(0, last));
    }

    private static int safeFloor(double value) {
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.floor(value);
    }

    private static int safeCeil(double value) {
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.ceil(value);
    }
}
