package com.xpe.mobile.audio;

public final class PlaybackSeekCoordinator {
    public enum Action {
        RETRY,
        START
    }

    private static final long POSITION_TOLERANCE_MS = 250L;
    private static final int MAX_RETRIES = 3;

    private int retryCount;

    public void begin() {
        retryCount = 0;
    }

    public Action onSeekComplete(long targetPositionMs, long reportedPositionMs) {
        if (absoluteDifference(Math.max(0L, targetPositionMs),
                Math.max(0L, reportedPositionMs)) > POSITION_TOLERANCE_MS
                && retryCount < MAX_RETRIES) {
            retryCount++;
            return Action.RETRY;
        }
        return Action.START;
    }

    public void reset() {
        retryCount = 0;
    }

    int retryCount() {
        return retryCount;
    }

    private static long absoluteDifference(long left, long right) {
        return left >= right ? left - right : right - left;
    }
}
