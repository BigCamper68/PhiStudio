package com.xpe.mobile.preview;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class CanvasLineClipperTest {
    @Test public void ordinaryLinesKeepTheRectPathAndMicrologyScalesAreClipped() {
        assertFalse(CanvasLineClipper.needsClipping(0, 0, 1, 1, 1.5));
        assertFalse(CanvasLineClipper.needsClipping(337.5, -300, .5, 2, 16.0 / 9));
        assertTrue(CanvasLineClipper.needsClipping(337.5, -300, -1771.749755859375, 1, 1.5));
        assertTrue(CanvasLineClipper.needsClipping(337.5, -300, -230.48971557617188, 1, 16.0 / 9));
        assertTrue(CanvasLineClipper.needsClipping(1e9, 0, 1, 1, 1.5));
        assertTrue(CanvasLineClipper.needsClipping(0, 0, 1, 1e6, 1.5));
    }

    @Test public void horizontalThinLineRetainsItsThicknessAndFullVisibleWidth() {
        for (double aspect : new double[]{1.5, 16.0 / 9, 21.0 / 9}) {
            for (double scale : new double[]{-1771.75, 1771.75, -230.49, 230.49}) {
                double[] p = CanvasLineClipper.polygon(337.5, -300, 0, scale, 1, aspect);
                assertEquals(8, p.length);
                assertEquals(.02, area(p), 1e-12);
                assertCoverage(p, 337.5, -300, 0, scale, 1, aspect);
            }
        }
    }

    @Test public void mirroredRotatedAndTranslatedQuadsHaveTheSameVisibleCoverage() {
        Random random = new Random(73174);
        for (int sample = 0; sample < 200; sample++) {
            double aspect = new double[]{1.5, 16.0 / 9, 21.0 / 9, .75}[sample % 4];
            double x = random.nextDouble() * 4000 - 2000;
            double y = random.nextDouble() * 2000 - 1000;
            double rotation = random.nextDouble() * 720 - 360;
            double sx = (random.nextBoolean() ? 1 : -1) * (2 + random.nextDouble() * 2000);
            double sy = (random.nextBoolean() ? 1 : -1) * (1 + random.nextDouble() * 100);
            double[] polygon = CanvasLineClipper.polygon(x, y, rotation, sx, sy, aspect);
            assertCoverage(polygon, x, y, rotation, sx, sy, aspect);
        }
    }

    @Test public void enclosingLineReallyFillsTheViewportAndOutsideLineStaysInvisible() {
        for (double aspect : new double[]{1.5, 16.0 / 9}) {
            assertEquals(4 / aspect,
                    area(CanvasLineClipper.polygon(0, 0, 45, 1e6, 1e6, aspect)), 1e-12);
            assertEquals(0, CanvasLineClipper.polygon(0, 1e9, 0, 1e6, 1, aspect).length);
            assertEquals(0, CanvasLineClipper.polygon(1e9, 0, 90, 1e6, 1, aspect).length);
            assertEquals(0, CanvasLineClipper.polygon(0, 0, 0, 0, 1, aspect).length);
            assertEquals(0, CanvasLineClipper.polygon(0, 0, 0, 1, 0, aspect).length);
        }
    }

    @Test public void clippingThresholdDoesNotChangeTheVisibleShape() {
        for (double rotation : new double[]{0, 3.817, 45, 90, -72.4454}) {
            double[] a = CanvasLineClipper.polygon(337.5, -300, rotation, 8.0 / 6 - 1e-9, 1, 1.5);
            double[] b = CanvasLineClipper.polygon(337.5, -300, rotation, 8.0 / 6 + 1e-9, 1, 1.5);
            assertArrayEquals(a, b, 1e-12);
        }
    }

    @Test public void invalidGeometryIsNeverSentToCanvas() {
        for (double bad : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertEquals(0, CanvasLineClipper.polygon(bad, 0, 0, 1, 1, 1.5).length);
            assertEquals(0, CanvasLineClipper.polygon(0, bad, 0, 1, 1, 1.5).length);
            assertEquals(0, CanvasLineClipper.polygon(0, 0, bad, 1, 1, 1.5).length);
            assertEquals(0, CanvasLineClipper.polygon(0, 0, 0, bad, 1, 1.5).length);
            assertEquals(0, CanvasLineClipper.polygon(0, 0, 0, 1, bad, 1.5).length);
            assertEquals(0, CanvasLineClipper.polygon(0, 0, 0, 1, 1, bad).length);
        }
        assertEquals(0, CanvasLineClipper.polygon(0, 0, 0, 1, 1, 0).length);
    }

    private static void assertCoverage(double[] polygon, double x, double y, double rotation,
                                       double sx, double sy, double aspect) {
        for (int i = 0; i < polygon.length; i += 2) {
            assertTrue(Double.isFinite(polygon[i])); assertTrue(Double.isFinite(polygon[i + 1]));
            assertTrue(Math.abs(polygon[i]) <= 1 + 1e-12);
            assertTrue(Math.abs(polygon[i + 1]) <= 1 / aspect + 1e-12);
        }
        // Independent point-in-quad oracle: inverse-transform viewport samples
        // into the authored rectangle, then compare with point-in-polygon.
        double radians = Math.toRadians(-PrprRotation.screenAngle(rotation));
        double cos = Math.cos(radians), sin = Math.sin(radians);
        double halfThickness = (Math.abs(sy - 1) > 1e-4 ? .0076 : .01) * Math.abs(sy) / 2;
        for (int row = 0; row < 40; row++) for (int col = 0; col < 40; col++) {
            double px = -1 + (col + .37) / 20;
            double py = (-1 + (row + .61) / 20) / aspect;
            double dx = px - x / 675, dy = py - y / 450 / aspect;
            boolean expected = Math.abs(dx * cos + dy * sin) < 6 * Math.abs(sx)
                    && Math.abs(-dx * sin + dy * cos) < halfThickness;
            assertEquals("point " + px + "," + py, expected, contains(polygon, px, py));
        }
    }

    private static boolean contains(double[] polygon, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = polygon.length - 2; i < polygon.length; j = i, i += 2) {
            if ((polygon[i + 1] > y) != (polygon[j + 1] > y)
                    && x < (polygon[j] - polygon[i]) * (y - polygon[i + 1])
                    / (polygon[j + 1] - polygon[i + 1]) + polygon[i]) inside = !inside;
        }
        return inside;
    }

    private static double area(double[] polygon) {
        double area = 0;
        for (int i = 0, j = polygon.length - 2; i < polygon.length; j = i, i += 2)
            area += polygon[j] * polygon[i + 1] - polygon[i] * polygon[j + 1];
        return Math.abs(area) / 2;
    }
}
