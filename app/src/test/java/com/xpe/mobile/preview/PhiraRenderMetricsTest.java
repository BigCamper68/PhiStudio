package com.xpe.mobile.preview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhiraRenderMetricsTest {
    @Test
    public void storyboardTextUsesExactlyFourPercentOfViewportWidth() {
        assertEquals(40.96f, PhiraRenderMetrics.textSize(1024f, 1f), 1.0e-4f);
        assertEquals(16.384f, PhiraRenderMetrics.textSize(1024f, 0.4f), 1.0e-4f);
    }

    @Test
    public void eitherZeroScaleAxisHidesLineVisual() {
        assertTrue(PhiraRenderMetrics.hasVisibleLineScale(1.0, 1.0));
        assertFalse(PhiraRenderMetrics.hasVisibleLineScale(0.0, 1.0));
        assertFalse(PhiraRenderMetrics.hasVisibleLineScale(1.0, 0.0));
        assertFalse(PhiraRenderMetrics.hasVisibleLineScale(0.0, 0.0));
        assertFalse(PhiraRenderMetrics.hasVisibleLineScale(Double.NaN, 1.0));
    }

    @Test
    public void hudMatchesPhiraAtReferenceResolution() {
        PhiraRenderMetrics.Hud hud = PhiraRenderMetrics.hud(1024f, 576f);

        assertEquals(15.36f, hud.marginX, 1.0e-4f);
        assertEquals(7.68f, hud.pauseBarWidth, 1.0e-4f);
        assertEquals(20.16f, hud.pauseTop, 1.0e-4f);
        assertEquals(12.672f, hud.scoreTop, 1.0e-4f);
        assertEquals(5.76f, hud.progressHeight, 1.0e-4f);
        assertEquals(32.768f, hud.scoreTextSize, 1.0e-4f);
        assertEquals(40.96f, hud.comboTextSize, 1.0e-4f);
        assertEquals(20.48f, hud.bottomTextSize, 1.0e-4f);
    }
}
