package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RpeLayerCompatibilityTest {
    @Test
    public void preservesDelayedAdditiveRotationWithoutSyntheticPrefix() throws Exception {
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

        assertEquals(1, exported.length());
        JSONObject authored = exported.getJSONObject(0);
        assertBeat(authored.getJSONArray("startTime"), 17, 1, 4);
        assertBeat(authored.getJSONArray("endTime"), 17, 1, 2);
        assertEquals(0.0, authored.getDouble("start"), 0.0);
        assertEquals(-10.0, authored.getDouble("end"), 0.0);
        assertEquals(4, authored.getInt("easingType"));
    }

    @Test
    public void preservesDelayedBaseAlphaAndSpeed() throws Exception {
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

        assertEquals(1, exported.getJSONArray("alphaEvents").length());
        assertBeat(alphaPrefix.getJSONArray("startTime"), 4, 0, 1);
        assertEquals(64.0, alphaPrefix.getDouble("start"), 0.0);
        assertEquals(0.0, alphaPrefix.getDouble("end"), 0.0);
        assertEquals(1, exported.getJSONArray("speedEvents").length());
        assertBeat(speedPrefix.getJSONArray("startTime"), 4, 0, 1);
        assertEquals(8.0, speedPrefix.getDouble("start"), 0.0);
        assertEquals(6.0, speedPrefix.getDouble("end"), 0.0);
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
