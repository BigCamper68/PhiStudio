package com.xpe.mobile.audio;

public final class PcmPlaybackClock {
    private PcmPlaybackClock() {
    }

    public static long positionMillis(PcmAudioAsset asset, long startFrame,
                                      long playbackHeadFrames) {
        return positionMillis(asset, startFrame, playbackHeadFrames, 1f);
    }

    public static long positionMillis(PcmAudioAsset asset, long startFrame,
                                      long playbackHeadFrames, float speed) {
        if (asset == null) throw new IllegalArgumentException("PCM asset is required");
        if (!Float.isFinite(speed) || speed <= 0) throw new IllegalArgumentException("Invalid speed");
        long safeStart = Math.max(0L, Math.min(startFrame, asset.totalFrames));
        long remaining = asset.totalFrames - safeStart;
        long safeHead = Math.max(0L, Math.min(Math.round(Math.max(0L, playbackHeadFrames)
                * (double) speed), remaining));
        return asset.positionMillisForFrame(safeStart + safeHead);
    }
}
