package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class JudgeLineCompatibilityTest {
    @Test
    public void padsMinimalLineToFiveEventLayerSlots() throws Exception {
        JSONObject source = new JSONObject()
                .put("Name", "Line 1")
                .put("eventLayers", new JSONArray().put(new JSONObject()));

        JSONObject output = JudgeLine.fromJson(source).toJson();
        JSONArray layers = output.getJSONArray("eventLayers");

        assertEquals(5, layers.length());
        for (int index = 0; index < layers.length(); index++) {
            assertTrue(layers.isNull(index));
        }
    }

    @Test
    public void writesStableLineDefaultsWithoutReplacingSourceControls() throws Exception {
        JSONArray sourceAlpha = new JSONArray().put(
                new JSONObject().put("x", 12.0).put("alpha", 0.5).put("easing", 2));
        JSONObject source = new JSONObject()
                .put("rotateWithFather", false)
                .put("alphaControl", sourceAlpha)
                .put("eventLayers", new JSONArray().put(JSONObject.NULL));

        JSONObject output = JudgeLine.fromJson(source).toJson();

        assertFalse(output.getBoolean("rotateWithFather"));
        assertEquals(12.0,
                output.getJSONArray("alphaControl").getJSONObject(0).getDouble("x"), 0.0);
        assertNotNull(output.getJSONArray("anchor"));
        assertTrue(output.has("posControl"));
        assertTrue(output.has("sizeControl"));
        assertTrue(output.has("yControl"));
        assertTrue(output.has("skewControl"));
    }
}
