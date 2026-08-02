package com.xpe.mobile.preview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RpeProjectionTest {
    @Test
    public void usesWidthAsTheUniformScaleForWidePlayerViewport() {
        assertEquals(1920.0 / 1350.0,
                RpeProjection.uniformScale(1920.0, 1080.0), 1.0e-9);
    }

    @Test
    public void keepsHorizontalRpeEdgesOnViewportEdges() {
        double scale = RpeProjection.uniformScale(1920.0, 1080.0);
        assertEquals(0.0, 960.0 - 675.0 * scale, 1.0e-9);
        assertEquals(1920.0, 960.0 + 675.0 * scale, 1.0e-9);
    }

    @Test
    public void preservesChartAngles() {
        assertEquals(-45.0f, RpeProjection.screenAngle(45.0), 0.0f);
        assertEquals(30.0f, RpeProjection.screenAngle(-30.0), 0.0f);
    }

    @Test
    public void rejectsInvalidViewportDimensions() {
        assertEquals(0.0, RpeProjection.uniformScale(0.0, 1080.0), 0.0);
        assertEquals(0.0, RpeProjection.uniformScale(1920.0, Double.NaN), 0.0);
    }
}
