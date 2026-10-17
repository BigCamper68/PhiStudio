package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class NoteControlEventsTest {
    @Test
    public void intervalUsesTheEasingDeclaredByItsEndingControlPoint() throws Exception {
        JSONObject line = new JSONObject().put("posControl", new JSONArray()
                .put(control("pos", 2.0, 0.0, 1))
                .put(control("pos", 4.0, 100.0, 5)));
        NoteControlEvents controls = NoteControlEvents.fromJson(line);

        assertEquals(2.0, NoteControlEvents.valueAt(
                controls.position, -10.0, 1.0), 0.0);
        assertEquals(2.5, NoteControlEvents.valueAt(
                controls.position, 50.0, 1.0), 1.0e-9);
        assertEquals(4.0, NoteControlEvents.valueAt(
                controls.position, 100.0, 1.0), 0.0);
    }

    @Test
    public void parsesAllFamiliesAndRecognizesRpeIdentitySentinel() throws Exception {
        JSONObject line = new JSONObject()
                .put("posControl", identity("pos", 8.0))
                .put("sizeControl", new JSONArray().put(control("size", 0.5, 0.0, 1)))
                .put("alphaControl", new JSONArray().put(control("alpha", 0.25, 0.0, 1)))
                .put("yControl", new JSONArray().put(control("y", 2.0, 0.0, 1)));
        NoteControlEvents controls = NoteControlEvents.fromJson(line);

        assertEquals(5, controls.count());
        assertEquals(1.0, NoteControlEvents.valueAt(
                controls.position, 8.0, 1.0), 0.0);
        assertEquals(0.5, NoteControlEvents.valueAt(controls.size, 8.0, 1.0), 0.0);
        assertEquals(0.25, NoteControlEvents.valueAt(controls.alpha, 8.0, 1.0), 0.0);
        assertEquals(2.0, NoteControlEvents.valueAt(controls.y, 8.0, 1.0), 0.0);
    }

    private static JSONArray identity(String key, double x) throws Exception {
        return new JSONArray()
                .put(control(key, 1.0, 0.0, 1))
                .put(control(key, 9.0, x, 29));
    }

    private static JSONObject control(String key, double value, double x, int easing)
            throws Exception {
        return new JSONObject().put("x", x).put("easing", easing).put(key, value);
    }
}
