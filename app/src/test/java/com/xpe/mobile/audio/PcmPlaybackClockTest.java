package com.xpe.mobile.audio;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

public final class PcmPlaybackClockTest {
    private final PcmAudioAsset asset = new PcmAudioAsset(
            new File("track.pcm"), 48_000, 2, 48_000L * 180L);

    @Test
    public void nonZeroStartsUseExactDecodedFramePlusPlaybackHead() {
        long head = 12_000L;
        assertEquals(10_250L, PcmPlaybackClock.positionMillis(
                asset, asset.frameForPositionMillis(10_000L), head));
        assertEquals(90_250L, PcmPlaybackClock.positionMillis(
                asset, asset.frameForPositionMillis(90_000L), head));
    }

    @Test
    public void repeatedSeekCannotRetainPreviousPlaybackHead() {
        long oldStart = asset.frameForPositionMillis(30_000L);
        assertEquals(31_000L, PcmPlaybackClock.positionMillis(asset, oldStart, 48_000L));

        long newStart = asset.frameForPositionMillis(120_000L);
        assertEquals(120_000L, PcmPlaybackClock.positionMillis(asset, newStart, 0L));
    }

    @Test
    public void playbackHeadIsClampedAtDecodedDuration() {
        assertEquals(180_000L, PcmPlaybackClock.positionMillis(
                asset, asset.frameForPositionMillis(179_000L), Long.MAX_VALUE));
    }
}
