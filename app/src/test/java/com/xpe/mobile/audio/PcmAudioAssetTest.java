package com.xpe.mobile.audio;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

public final class PcmAudioAssetTest {
    private final PcmAudioAsset stereo44 = new PcmAudioAsset(
            new File("track.pcm"), 44_100, 2, 44_100L * 180L);

    @Test
    public void mapsMillisecondsToExactPcmFrame() {
        assertEquals(0L, stereo44.frameForPositionMillis(0L));
        assertEquals(44_100L, stereo44.frameForPositionMillis(1_000L));
        assertEquals(2_646_000L, stereo44.frameForPositionMillis(60_000L));
    }

    @Test
    public void mapsPlaybackHeadFramesBackToMediaTime() {
        long start = stereo44.frameForPositionMillis(60_000L);
        assertEquals(60_250L, stereo44.positionMillisForFrame(start + 11_025L));
    }

    @Test
    public void clampsSeekAndByteOffsetToDecodedAsset() {
        assertEquals(stereo44.totalFrames, stereo44.frameForPositionMillis(Long.MAX_VALUE));
        assertEquals(stereo44.totalFrames * 4L,
                stereo44.byteOffsetForFrame(Long.MAX_VALUE));
        assertEquals(180_000L, stereo44.durationMillis());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedMultichannelAsset() {
        new PcmAudioAsset(new File("track.pcm"), 48_000, 6, 1L);
    }
}
