package com.xpe.mobile.noiseeditor;

import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.preview.NoiseFieldEvaluator;
import com.xpe.mobile.preview.NoiseFieldTest;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoiseFieldPresentationTest {
    @Test public void statesUseNativeHalfOpenBoundariesAndFloatRounding() throws Exception {
        var field = NoiseField.fromJson(NoiseFieldTest.area(false, 1, 2, 3, 4));
        assertEquals(NoiseFieldPresentation.State.OUTSIDE, NoiseFieldPresentation.state(field, .99));
        assertEquals(NoiseFieldPresentation.State.INACTIVE, NoiseFieldPresentation.state(field, 1));
        assertEquals(NoiseFieldPresentation.State.READY, NoiseFieldPresentation.state(field, 1.5));
        assertEquals(NoiseFieldPresentation.State.ACTIVE, NoiseFieldPresentation.state(field, 2));
        assertEquals(NoiseFieldPresentation.State.INACTIVE, NoiseFieldPresentation.state(field, 3));
        assertEquals(NoiseFieldPresentation.State.OUTSIDE, NoiseFieldPresentation.state(field, 4));
        assertEquals(NoiseFieldPresentation.State.OUTSIDE, NoiseFieldPresentation.state(field, 4 - 1e-9));
    }
    @Test public void editingOutsideLifetimeDoesNotMakeTheNativeRendererVisible() throws Exception {
        var field = NoiseFieldEdits.create(1, 2, false);
        for (float aspect : new float[] {1.5f, 16f / 9}) {
            assertNull(NoiseFieldEvaluator.sample(field, 0, aspect));
            assertNull(NoiseFieldEvaluator.sample(field, 3, aspect));
            assertNotNull(NoiseFieldEvaluator.geometry(field, 0, aspect));
            assertArrayEquals(NoiseFieldEvaluator.sample(field, 2, aspect).corners(),
                    NoiseFieldEvaluator.geometry(field, 2, aspect).corners(), 0);
        }
    }
    @Test public void rotatedAndExtremeOffscreenBoundsNeverReachCanvasUnclipped() throws Exception {
        for (double size : new double[] {.5, 2, 1e4, 1e30}) for (double aspect : new double[] {1.5, 16.0 / 9}) {
            var raw = NoiseFieldTest.area(false, 0, 0, 10, 10);
            raw.getJSONObject("bottomLeftPercentage").put("x", -size).put("y", -size);
            raw.getJSONObject("topRightPercentage").put("x", size).put("y", size);
            raw.put("rotateEvents", new org.json.JSONArray().put(new org.json.JSONObject().put("time", 0).put("rotation", 37)
                    .put("anchor", NoiseFieldTest.point(.5, .5))));
            var edges = NoiseFieldPresentation.outline(NoiseFieldEvaluator.geometry(NoiseField.fromJson(raw), 1, (float) aspect), aspect);
            for (double[] edge : edges) for (int i = 0; i < edge.length; i++) {
                assertTrue(Double.isFinite(edge[i])); assertTrue(Math.abs(edge[i]) <= ((i & 1) == 0 ? 1 : 1 / aspect));
            }
        }
    }
    @Test public void giantEnclosingRectangleDoesNotInventViewportBorders() throws Exception {
        var field = NoiseFieldEdits.bounds(NoiseFieldEdits.create(0, 10, false), -1000, -1000, 1000, 1000);
        assertTrue(NoiseFieldPresentation.outline(NoiseFieldEvaluator.geometry(field, 1, 1.5f), 1.5).isEmpty());
        var ordinary = NoiseFieldEdits.create(0, 10, false);
        assertEquals(4, NoiseFieldPresentation.outline(NoiseFieldEvaluator.geometry(ordinary, 1, 1.5f), 1.5).size());
    }
}
