package com.xpe.mobile.preview;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhiraBackgroundMetricsTest {
    @Test public void defaultDimCombinesThirtyPercentVeilAndSixtyPercentGameplayLayer() {
        double dim = PhiraBackgroundMetrics.gameplayDim(.6);
        assertEquals(.28,(1-PhiraBackgroundMetrics.BACKDROP_DIM)*(1-dim),1e-9);
        // Oblivion's backgroundDim=0.1 intentionally produces a brighter background.
        assertEquals(.63,(1-PhiraBackgroundMetrics.BACKDROP_DIM)
                *(1-PhiraBackgroundMetrics.gameplayDim(.1)),1e-9);
        assertEquals(.8,PhiraBackgroundMetrics.gameplayDim(.8),0);
    }

    @Test public void fullAreaCropUsesFloatingGeometryBeforeClippingGameplayWindow() {
        var place = PhiraBackgroundMetrics.place(1920,1080,0,0,1000,1000);
        assertEquals(-388.8888889,place.left,1e-6);
        assertEquals(1388.8888889,place.right,1e-6);
        assertEquals(0,place.top,1e-6);
        assertEquals(1000,place.bottom,1e-6);
        var tall = PhiraBackgroundMetrics.place(1000,2000,0,0,1600,900);
        assertEquals(0,tall.left,0);
        assertEquals(-1150,tall.top,0);
        assertEquals(2050,tall.bottom,0);
    }

    @Test public void sampleSizeCapsMemoryWithoutChangingSourceSigmaUnits() {
        assertEquals(1,PhiraBackgroundMetrics.sampleSize(1920,1080));
        assertEquals(4,PhiraBackgroundMetrics.sampleSize(4096,4096));
        assertEquals(12.5f,PhiraBackgroundMetrics.BLUR_SIGMA
                / PhiraBackgroundMetrics.sampleSize(4096,4096),0);
        assertEquals(2,PhiraBackgroundMetrics.sampleSize(4001,1000));
    }

    @Test public void malformedMetadataUsesPhiraDefault() {
        for (String value : new String[]{null,"NaN","Infinity","-0.1","1.1","bad"}) {
            assertEquals(.6,PhiraBackgroundMetrics.chartDim(value),0);
        }
        assertEquals(.1,PhiraBackgroundMetrics.chartDim("0.1"),0);
        assertEquals(.1,PhiraBackgroundMetrics.chartDim("0.1 # artist dim"),0);
    }
}
