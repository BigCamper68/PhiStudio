package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class TimelineGridRangeTest {
    @Test
    public void includesFutureBeatsVisibleAbovePlayheadAtMinimumZoom() {
        double currentBeat = 16.0;
        float centerY = 600f;
        float top = 60f;
        float bottom = 900f;
        float pixelsPerBeat = 38f;
        int subdivision = 4;

        TimelineGridRange.Range range = TimelineGridRange.visible(
                currentBeat, centerY, top, bottom, pixelsPerBeat, subdivision);

        double visibleTopBeat = currentBeat + (centerY - top) / pixelsPerBeat;
        double visibleBottomBeat = currentBeat + (centerY - bottom) / pixelsPerBeat;
        assertTrue(range.lastStep >= Math.ceil(visibleTopBeat * subdivision));
        assertTrue(range.firstStep <= Math.floor(visibleBottomBeat * subdivision));
    }

    @Test
    public void clampsGridToBeatZeroNearChartStart() {
        TimelineGridRange.Range range = TimelineGridRange.visible(
                0.0, 600f, 60f, 900f, 38f, 4);

        assertEquals(0, range.firstStep);
        assertTrue(range.lastStep > 0);
    }
}
