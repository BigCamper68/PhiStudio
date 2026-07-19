package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PlaybackScrubMapperTest {
    @Test
    public void mapsPositionsAndCoordinatesAcrossTheWholeTrack() {
        assertEquals(0.0, PlaybackScrubMapper.fractionForPosition(-50L, 1000L), 0.0);
        assertEquals(0.5, PlaybackScrubMapper.fractionForPosition(500L, 1000L), 0.0);
        assertEquals(1.0, PlaybackScrubMapper.fractionForPosition(1500L, 1000L), 0.0);

        assertEquals(0L, PlaybackScrubMapper.positionForFraction(-1.0, 9000L));
        assertEquals(2250L, PlaybackScrubMapper.positionForFraction(0.25, 9000L));
        assertEquals(9000L, PlaybackScrubMapper.positionForFraction(2.0, 9000L));

        assertEquals(0.0, PlaybackScrubMapper.fractionForX(5f, 10f, 110f), 0.0);
        assertEquals(0.5, PlaybackScrubMapper.fractionForX(60f, 10f, 110f), 0.0);
        assertEquals(1.0, PlaybackScrubMapper.fractionForX(120f, 10f, 110f), 0.0);
    }

    @Test
    public void handlesMissingOrDegenerateRanges() {
        assertEquals(0.0, PlaybackScrubMapper.fractionForPosition(10L, 0L), 0.0);
        assertEquals(0L, PlaybackScrubMapper.positionForFraction(0.5, 0L));
        assertEquals(0.0, PlaybackScrubMapper.fractionForX(20f, 10f, 10f), 0.0);
    }
}
