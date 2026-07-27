package com.xpe.mobile.audio;

import java.io.File;

public final class PcmAudioAsset {
    public final File file;
    public final int sampleRate;
    public final int channelCount;
    public final long totalFrames;

    public PcmAudioAsset(File file, int sampleRate, int channelCount, long totalFrames) {
        if (file == null) throw new IllegalArgumentException("PCM file is required");
        if (sampleRate <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        if (channelCount < 1 || channelCount > 2) {
            throw new IllegalArgumentException("Only mono or stereo PCM is supported");
        }
        if (totalFrames < 0L) throw new IllegalArgumentException("Frame count cannot be negative");
        this.file = file;
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.totalFrames = totalFrames;
    }

    public int bytesPerFrame() {
        return channelCount * 2;
    }

    public long durationMillis() {
        return positionMillisForFrame(totalFrames);
    }

    public long frameForPositionMillis(long positionMs) {
        double frame = Math.max(0L, positionMs) * (double) sampleRate / 1000.0;
        if (!Double.isFinite(frame) || frame >= totalFrames) return totalFrames;
        return Math.max(0L, Math.round(frame));
    }

    public long positionMillisForFrame(long frame) {
        long clamped = Math.max(0L, Math.min(frame, totalFrames));
        double millis = clamped * 1000.0 / sampleRate;
        if (!Double.isFinite(millis) || millis >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0L, Math.round(millis));
    }

    public long byteOffsetForFrame(long frame) {
        long clamped = Math.max(0L, Math.min(frame, totalFrames));
        int frameSize = bytesPerFrame();
        if (clamped > Long.MAX_VALUE / frameSize) return Long.MAX_VALUE;
        return clamped * frameSize;
    }
}
