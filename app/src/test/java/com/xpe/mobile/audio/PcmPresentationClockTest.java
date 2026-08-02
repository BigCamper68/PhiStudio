package com.xpe.mobile.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PcmPresentationClockTest {
    @Test
    public void advancesTimestampAtPlaybackSpeed() {
        long frames = PcmPresentationClock.timestampFrames(
                4_800L, 1_000_000_000L, 1_500_000_000L,
                48_000, 1.0f, 100_000L);
        assertEquals(28_800L, frames);
    }

    @Test
    public void scalesElapsedFramesForSlowPlayback() {
        long frames = PcmPresentationClock.wallClockFrames(
                2_000_000_000L, 3_000_000_000L,
                48_000, 0.5f, 100_000L);
        assertEquals(24_000L, frames);
    }

    @Test
    public void treatsAudioTimestampFrameAsUnsigned32Bit() {
        long frames = PcmPresentationClock.timestampFrames(
                -1L, 1_000L, 1_000L,
                48_000, 1.0f, 5_000_000_000L);
        assertEquals(4_294_967_295L, frames);
    }

    @Test
    public void clampsToRemainingPcmFrames() {
        long frames = PcmPresentationClock.timestampFrames(
                900L, 0L, 2_000_000_000L,
                48_000, 2.0f, 1_000L);
        assertEquals(1_000L, frames);
    }
}
