package com.xpe.mobile.packageio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RpePackageCompatTest {
    @Test
    public void padsLayersAndAddsMissingRePhiEditDefaults() throws Exception {
        JSONObject root = new JSONObject().put("judgeLineList", new JSONArray().put(
                new JSONObject().put("eventLayers", new JSONArray()
                        .put(new JSONObject())
                        .put(new JSONObject().put("moveXEvents", new JSONArray())))));

        JSONObject line = new JSONObject(RpePackageCompat.normalize(root.toString()))
                .getJSONArray("judgeLineList").getJSONObject(0);
        JSONArray layers = line.getJSONArray("eventLayers");

        assertEquals(5, layers.length());
        assertTrue(layers.isNull(0));
        assertFalse(layers.isNull(1));
        assertTrue(layers.isNull(2));
        assertTrue(layers.isNull(3));
        assertTrue(layers.isNull(4));
        assertFalse(line.getBoolean("rotateWithFather"));
        assertEquals(0.5, line.getJSONArray("anchor").getDouble(0), 0.0);
        assertFalse(line.getBoolean("isGif"));
        assertEquals(2, line.getJSONArray("alphaControl").length());
        assertEquals(2, line.getJSONArray("posControl").length());
        assertEquals(2, line.getJSONArray("sizeControl").length());
        assertEquals(2, line.getJSONArray("yControl").length());
        assertEquals(2, line.getJSONArray("skewControl").length());
    }

    @Test
    public void preservesExistingRawValuesAndExtraLayers() throws Exception {
        JSONArray alpha = new JSONArray().put(
                new JSONObject().put("x", 12.0).put("alpha", 0.5).put("easing", 2));
        JSONArray layers = new JSONArray();
        for (int index = 0; index < 6; index++) layers.put(JSONObject.NULL);
        JSONObject root = new JSONObject().put("judgeLineList", new JSONArray().put(
                new JSONObject()
                        .put("rotateWithFather", 1)
                        .put("alphaControl", alpha)
                        .put("eventLayers", layers)));

        JSONObject line = new JSONObject(RpePackageCompat.normalize(root.toString()))
                .getJSONArray("judgeLineList").getJSONObject(0);

        assertEquals(1, line.getInt("rotateWithFather"));
        assertEquals(12.0,
                line.getJSONArray("alphaControl").getJSONObject(0).getDouble("x"), 0.0);
        assertEquals(6, line.getJSONArray("eventLayers").length());
    }
}
