package com.xpe.mobile.audio;

public final class PcmPlaybackClock {
    private PcmPlaybackClock() {
    }

    public static long positionMillis(PcmAudioAsset asset, long startFrame,
                                      long playbackHeadFrames) {
        if (asset == null) throw new IllegalArgumentException("PCM asset is required");
        long safeStart = Math.max(0L, Math.min(startFrame, asset.totalFrames));
        long remaining = asset.totalFrames - safeStart;
        long safeHead = Math.max(0L, Math.min(playbackHeadFrames, remaining));
        return asset.positionMillisForFrame(safeStart + safeHead);
    }
}
