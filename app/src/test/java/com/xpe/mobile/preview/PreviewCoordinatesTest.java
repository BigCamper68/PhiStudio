package com.xpe.mobile.preview;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PreviewCoordinatesTest {
    @Test public void localTravelMatchesPrprAtEveryRotationAndAspect() {
        for (int[] size : new int[][]{{1920,1080}, {2100,1400}, {2560,1080}, {1080,1920}}) {
            double sx = PreviewCoordinates.scaleX(size[0]);
            double sy = PreviewCoordinates.scaleY(size[1]);
            double aspect = size[0] / (double) size[1];
            for (int rotation = -180; rotation <= 180; rotation += 15) {
                for (double distance : new double[]{-350, 0, 50, 700}) {
                    double radians = Math.toRadians(rotation);
                    // Independent pixel form of prpr Object::now_translation + rotation.
                    double x = 180 / 675.0;
                    double y = distance / 450.0 / aspect;
                    assertEquals((Math.cos(radians)*x - Math.sin(radians)*y)*size[0]/2,
                            PreviewCoordinates.localX(180, distance, rotation, sx, sy), 1e-9);
                    assertEquals((Math.sin(radians)*x + Math.cos(radians)*y)*size[0]/2,
                            PreviewCoordinates.localY(180, distance, rotation, sx, sy), 1e-9);
                }
            }
        }
    }

    @Test public void movingLineAndApproachingNoteKeepTheSameSpeed() {
        for (int[] size : new int[][]{{1920,1080}, {2100,1400}, {2560,1080}}) {
            double sx = PreviewCoordinates.scaleX(size[0]);
            double sy = PreviewCoordinates.scaleY(size[1]);
            for (double time = 0; time < 2; time += .1) {
                double lineY = 300 - time * 100;
                double distance = 300 - time * 100;
                assertEquals(lineY * sy, PreviewCoordinates.localY(0, distance, 0, sx, sy), 1e-9);
            }
        }
    }

    @Test public void pairedHoldsAtDifferentOriginsKeepTheirHeadsAndTailsAligned() {
        for (int[] size : new int[][]{{1920,1080}, {2100,1400}, {2560,1080}}) {
            double sx = PreviewCoordinates.scaleX(size[0]);
            double sy = PreviewCoordinates.scaleY(size[1]);
            assertEquals(100*sy + PreviewCoordinates.localY(0, 200, 0, sx, sy),
                    200*sy + PreviewCoordinates.localY(0, 100, 0, sx, sy), 1e-9);
            assertEquals(100*sy + PreviewCoordinates.localY(0, 400, 0, sx, sy),
                    200*sy + PreviewCoordinates.localY(0, 300, 0, sx, sy), 1e-9);
        }
    }

    @Test
    public void completeAuthoredAreaFitsEveryPlayerAspect() {
        for (int[] size : new int[][]{{1920, 1080}, {2100, 1400}, {2560, 1080}, {1080, 1920}}) {
            double sx = PreviewCoordinates.scaleX(size[0]);
            double sy = PreviewCoordinates.scaleY(size[1]);
            assertEquals(0.0, size[0] / 2.0 - 675 * sx, 1e-9);
            assertEquals(size[0], size[0] / 2.0 + 675 * sx, 1e-9);
            assertEquals(0.0, size[1] / 2.0 - 450 * sy, 1e-9);
            assertEquals(size[1], size[1] / 2.0 + 450 * sy, 1e-9);
        }
    }
}
