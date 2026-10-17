package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PreviewViewportCalculatorTest {
    @Test
    public void pillarboxesWideAvailableArea() {
        PreviewViewportCalculator.Result result = PreviewViewportCalculator.fit(
                0.0, 100.0, 2400.0, 1000.0, 1350, 900);

        assertEquals(525.0, result.left, 1.0e-9);
        assertEquals(1875.0, result.right, 1.0e-9);
        assertEquals(100.0, result.top, 1.0e-9);
        assertEquals(1000.0, result.bottom, 1.0e-9);
    }

    @Test
    public void letterboxesTallAvailableArea() {
        PreviewViewportCalculator.Result result = PreviewViewportCalculator.fit(
                10.0, 20.0, 1010.0, 1020.0, 16, 9);

        assertEquals(10.0, result.left, 1.0e-9);
        assertEquals(1010.0, result.right, 1.0e-9);
        assertEquals(238.75, result.top, 1.0e-9);
        assertEquals(801.25, result.bottom, 1.0e-9);
    }

    @Test
    public void invalidPlayerDimensionsUsePhiraDefaultSixteenByNine() {
        PreviewViewportCalculator.Result result = PreviewViewportCalculator.fit(
                0.0, 0.0, 300.0, 300.0, 0, 0);

        assertEquals(0.0, result.left, 0.0);
        assertEquals(65.625, result.top, 1.0e-9);
        assertEquals(300.0, result.right, 0.0);
        assertEquals(234.375, result.bottom, 1.0e-9);
    }
}
