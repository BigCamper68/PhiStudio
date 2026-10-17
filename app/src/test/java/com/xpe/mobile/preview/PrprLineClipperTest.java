package com.xpe.mobile.preview;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PrprLineClipperTest {
    @Test public void ordinaryScalesKeepTheNormalCanvasPath() {
        for (double scale : new double[]{0, 1, -1, .1245, -20050.85})
            assertFalse(PrprLineClipper.needsClipping(scale, 1));
        assertTrue(PrprLineClipper.needsClipping(-1308507520, 1));
    }
    @Test public void extremeHorizontalQuadKeepsTheRightHalfReportedInMobilePhira() {
        for (float scale : new float[]{-1308507520f, 1308507520f}) {
            float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
            for (float[] p : PrprLineClipper.polygons(0, -250, 0, scale, 1, 16.0 / 9)) {
                assertTrue(p.length >= 6);
                for (int i = 0; i < p.length; i += 2) {
                    assertTrue(Float.isFinite(p[i])); assertTrue(Float.isFinite(p[i + 1]));
                    assertTrue(p[i] >= -1 && p[i] <= 1);
                    assertTrue(p[i + 1] >= -1 && p[i + 1] <= 1);
                    left = Math.min(left, p[i]); right = Math.max(right, p[i]);
                }
            }
            assertEquals(0, left, 0); assertEquals(1, right, 0);
        }
    }
}
