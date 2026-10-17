package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ExtendedLineEventsTest {
    private static final double EPSILON = 1.0e-8;

    @Test
    public void parsesAndEvaluatesEveryExtendedEventFamily() throws Exception {
        JSONObject extendedJson = new JSONObject()
                .put("scaleXEvents", new JSONArray().put(numeric(0.0, 10.0)))
                .put("scaleYEvents", new JSONArray().put(numeric(1.0, 3.0)))
                .put("inclineEvents", new JSONArray().put(numeric(0.0, 30.0)))
                .put("paintEvents", new JSONArray().put(numeric(-1.0, 12.0)))
                .put("gifEvents", new JSONArray().put(numeric(0.0, 1.0)))
                .put("colorEvents", new JSONArray().put(baseEvent()
                        .put("start", rgb(0, 10, 20)).put("end", rgb(100, 110, 120))))
                .put("textEvents", new JSONArray().put(baseEvent()
                        .put("start", "0%P%").put("end", "10%P%")));

        ExtendedLineEvents extended = ExtendedLineEvents.fromJson(extendedJson);

        assertEquals(7, extended.count());
        assertEquals(5.0, ExtendedLineEvents.numericValueAt(
                extended.scaleXEvents, 1.0, -1.0), EPSILON);
        assertEquals(0x323C46, ExtendedLineEvents.colorValueAt(
                extended.colorEvents, 1.0, 0), 0);
        assertEquals("5", ExtendedLineEvents.textValueAt(
                extended.textEvents, 1.0, ""));
    }

    @Test
    public void textTweenMatchesRpeNumericAndUnicodePrefixRules() throws Exception {
        ExtendedLineEvents numeric = ExtendedLineEvents.fromJson(new JSONObject()
                .put("textEvents", new JSONArray().put(baseEvent()
                        .put("start", "1.5%P%").put("end", "2.5%P%"))));
        ExtendedLineEvents unicode = ExtendedLineEvents.fromJson(new JSONObject()
                .put("textEvents", new JSONArray().put(baseEvent()
                        .put("start", "").put("end", "🙂a"))));
        ExtendedLineEvents replacement = ExtendedLineEvents.fromJson(new JSONObject()
                .put("textEvents", new JSONArray().put(baseEvent()
                        .put("start", "old").put("end", "new"))));

        assertEquals("2.000", ExtendedLineEvents.textValueAt(
                numeric.textEvents, 1.0, ""));
        assertEquals("🙂", ExtendedLineEvents.textValueAt(
                unicode.textEvents, 1.0, ""));
        assertEquals("old", ExtendedLineEvents.textValueAt(
                replacement.textEvents, 1.5, ""));
        assertEquals("new", ExtendedLineEvents.textValueAt(
                replacement.textEvents, 2.0, ""));
    }

    @Test
    public void defaultsBeforeFirstEventAndPersistsLatestEndValue() throws Exception {
        JSONObject late = baseEvent(2, 4).put("start", 3.0).put("end", 7.0);
        ExtendedLineEvents extended = ExtendedLineEvents.fromJson(new JSONObject()
                .put("scaleXEvents", new JSONArray().put(late)));

        assertEquals(1.0, ExtendedLineEvents.numericValueAt(
                extended.scaleXEvents, 1.0, 1.0), 0.0);
        assertEquals(7.0, ExtendedLineEvents.numericValueAt(
                extended.scaleXEvents, 9.0, 1.0), 0.0);
    }

    @Test
    public void editedFamilyPreservesUnknownContainerFieldsSlotsAndUntouchedRawEvents()
            throws Exception {
        JSONObject first = numeric(1.0, 2.0).put("futureEventField", "keep");
        JSONObject second = numeric(3.0, 4.0).put("easingType", 777);
        JSONObject source = new JSONObject()
                .put("scaleXEvents", new JSONArray()
                        .put(first).put(JSONObject.NULL).put(second))
                .put("futureContainer", new JSONObject().put("version", 9));
        ExtendedLineEvents extended = ExtendedLineEvents.fromJson(source);
        ExtendedLineEvents.NumericEvent target = extended.scaleXEvents.get(0);
        ExtendedLineEvents.NumericEvent edited = target.copy();
        edited.end = 9.0;

        extended.copyEvent(StoryboardEventType.SCALE_X, edited, target);
        JSONObject exported = extended.toJsonValue();
        JSONArray events = exported.getJSONArray("scaleXEvents");

        assertEquals(3, events.length());
        assertEquals(JSONObject.NULL, events.get(1));
        assertEquals("keep", events.getJSONObject(0).getString("futureEventField"));
        assertEquals(9.0, events.getJSONObject(0).getDouble("end"), EPSILON);
        assertEquals(777, events.getJSONObject(2).getInt("easingType"));
        assertEquals(9, exported.getJSONObject("futureContainer").getInt("version"));
    }

    @Test
    public void serializesEveryEditableFamilyAndCopiesLinkGroup() throws Exception {
        ExtendedLineEvents extended = new ExtendedLineEvents();
        for (StoryboardEventType type : StoryboardEventType.values()) {
            ExtendedLineEvents.TimedEvent event;
            if (type == StoryboardEventType.COLOR) {
                event = new ExtendedLineEvents.ColorEvent();
            } else if (type == StoryboardEventType.TEXT) {
                event = new ExtendedLineEvents.TextEvent();
            } else {
                event = new ExtendedLineEvents.NumericEvent();
            }
            event.linkGroup = 12;
            extended.add(type, event);
        }

        JSONObject exported = extended.toJsonValue();

        for (StoryboardEventType type : StoryboardEventType.values()) {
            assertTrue(exported.has(type.jsonKey));
            assertEquals(12, exported.getJSONArray(type.jsonKey)
                    .getJSONObject(0).getInt("linkgroup"));
        }
    }

    @Test
    public void judgeLineExportWritesNewStoryboardEditsWithoutDroppingOtherLineData()
            throws Exception {
        JudgeLine line = JudgeLine.fromJson(new JSONObject()
                .put("Name", "Storyboard")
                .put("futureLineField", new JSONArray().put(4).put(5))
                .put("eventLayers", new JSONArray()));
        ExtendedLineEvents.ColorEvent color = new ExtendedLineEvents.ColorEvent();
        color.startRgb = 0x102030;
        color.endRgb = 0xA0B0C0;

        line.extended.add(StoryboardEventType.COLOR, color);
        JSONObject exported = line.toJson();

        assertEquals(2, exported.getJSONArray("futureLineField").length());
        JSONArray values = exported.getJSONObject("extended")
                .getJSONArray("colorEvents").getJSONObject(0)
                .getJSONArray("end");
        assertEquals(160, values.getInt(0));
        assertEquals(176, values.getInt(1));
        assertEquals(192, values.getInt(2));
    }

    private static JSONObject numeric(double start, double end) throws Exception {
        return baseEvent().put("start", start).put("end", end);
    }

    private static JSONObject baseEvent() throws Exception {
        return baseEvent(0, 2);
    }

    private static JSONObject baseEvent(int start, int end) throws Exception {
        return new JSONObject()
                .put("startTime", beat(start))
                .put("endTime", beat(end))
                .put("easingType", 1)
                .put("easingLeft", 0.0)
                .put("easingRight", 1.0)
                .put("bezier", 0)
                .put("bezierPoints", new JSONArray().put(0).put(0).put(0).put(0));
    }

    private static JSONArray beat(int whole) {
        return new JSONArray().put(whole).put(0).put(1);
    }

    private static JSONArray rgb(int red, int green, int blue) {
        return new JSONArray().put(red).put(green).put(blue);
    }
}
