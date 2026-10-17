package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VerticalGridTest {
    @Test
    public void changesOnlyAcrossSupportedOddLineCounts() {
        assertEquals(9, VerticalGrid.changeCount(11, -1));
        assertEquals(13, VerticalGrid.changeCount(11, 1));
        assertEquals(3, VerticalGrid.changeCount(3, -1));
        assertEquals(33, VerticalGrid.changeCount(33, 1));
    }

    @Test
    public void snapsToEndpointsAndCenter() {
        assertEquals(-675.0, VerticalGrid.snap(-900.0, -675.0, 675.0, 11), 0.0);
        assertEquals(0.0, VerticalGrid.snap(20.0, -675.0, 675.0, 11), 0.0);
        assertEquals(675.0, VerticalGrid.snap(900.0, -675.0, 675.0, 11), 0.0);
        assertEquals(337.5, VerticalGrid.snap(350.0, -675.0, 675.0, 5), 0.0);
    }

    @Test
    public void calculatesEvenlySpacedScreenLines() {
        assertEquals(10f, VerticalGrid.screenX(0, 5, 10f, 110f), 0f);
        assertEquals(60f, VerticalGrid.screenX(2, 5, 10f, 110f), 0f);
        assertEquals(110f, VerticalGrid.screenX(4, 5, 10f, 110f), 0f);
    }
}
