package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RpeLayerCompatibilityTest {
    @Test
    public void prefixesDelayedAdditiveRotationWithZero() throws Exception {
        JudgeLine line = new JudgeLine();
        LineEvent delayed = new LineEvent();
        delayed.type = EventType.ROTATE;
        delayed.startTime = new BeatTime(17, 1, 4);
        delayed.endTime = new BeatTime(17, 1, 2);
        delayed.start = 0.0;
        delayed.end = -10.0;
        delayed.easingType = 4;
        line.layer(1).events(EventType.ROTATE).add(delayed);

        JSONArray exported = line.toJson().getJSONArray("eventLayers")
                .getJSONObject(1).getJSONArray("rotateEvents");

        assertEquals(2, exported.length());
        JSONObject prefix = exported.getJSONObject(0);
        assertBeat(prefix.getJSONArray("startTime"), 0, 0, 1);
        assertBeat(prefix.getJSONArray("endTime"), 17, 1, 4);
        assertEquals(0.0, prefix.getDouble("start"), 0.0);
        assertEquals(0.0, prefix.getDouble("end"), 0.0);
        assertEquals(1, prefix.getInt("easingType"));
        assertEquals(-10.0, exported.getJSONObject(1).getDouble("end"), 0.0);
    }

    @Test
    public void prefixesDelayedBaseValuesWithRpeDefaults() throws Exception {
        JudgeLine line = new JudgeLine();
        EventLayer base = line.layer(0);
        base.events(EventType.ALPHA).clear();
        base.events(EventType.SPEED).clear();

        LineEvent alpha = delayed(EventType.ALPHA, 4, 64.0, 0.0);
        LineEvent speed = delayed(EventType.SPEED, 4, 8.0, 6.0);
        base.events(EventType.ALPHA).add(alpha);
        base.events(EventType.SPEED).add(speed);

        JSONObject exported = line.toJson().getJSONArray("eventLayers").getJSONObject(0);
        JSONObject alphaPrefix = exported.getJSONArray("alphaEvents").getJSONObject(0);
        JSONObject speedPrefix = exported.getJSONArray("speedEvents").getJSONObject(0);

        assertEquals(255.0, alphaPrefix.getDouble("start"), 0.0);
        assertEquals(255.0, alphaPrefix.getDouble("end"), 0.0);
        assertEquals(10.0, speedPrefix.getDouble("start"), 0.0);
        assertEquals(10.0, speedPrefix.getDouble("end"), 0.0);
    }

    private static LineEvent delayed(EventType type, int startBeat,
                                     double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(startBeat + 1, 0, 1);
        event.start = start;
        event.end = end;
        return event;
    }

    private static void assertBeat(JSONArray value, int beat, int numerator,
                                   int denominator) throws Exception {
        assertEquals(beat, value.getInt(0));
        assertEquals(numerator, value.getInt(1));
        assertEquals(denominator, value.getInt(2));
    }
}
