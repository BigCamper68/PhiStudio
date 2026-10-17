package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RawJsonMemoryTest {
    @Test
    public void parsingCopyingAndExportDoNotStringifyRawSubtrees() throws Exception {
        FailOnStringJson bpm = new FailOnStringJson()
                .putValue("bpm", 120.0)
                .putValue("startTime", beat(0));
        FailOnStringJson event = new FailOnStringJson()
                .putValue("startTime", beat(0))
                .putValue("endTime", beat(1))
                .putValue("start", 0.0)
                .putValue("end", 10.0)
                .putValue("unknownEvent", 77);
        FailOnStringJson layer = emptyLayer()
                .putValue("moveXEvents", new JSONArray().put(event));
        FailOnStringJson note = new FailOnStringJson()
                .putValue("startTime", beat(1))
                .putValue("endTime", beat(1))
                .putValue("type", 1)
                .putValue("unknownNote", "keep");
        FailOnStringJson line = new FailOnStringJson()
                .putValue("Name", "Source line")
                .putValue("notes", new JSONArray().put(note))
                .putValue("eventLayers", new JSONArray().put(layer))
                .putValue("unknownLine", true);
        FailOnStringJson meta = new FailOnStringJson().putValue("name", "Source chart");
        FailOnStringJson root = new FailOnStringJson()
                .putValue("META", meta)
                .putValue("BPMList", new JSONArray().put(bpm))
                .putValue("judgeLineList", new JSONArray().put(line))
                .putValue("unknownRoot", "keep");

        ChartDocument chart = ChartDocument.fromParsedJson(root);
        chart.name = "Edited chart";
        chart.judgeLines.get(0).name = "Edited line";
        chart.bpmChanges.get(0).copy();
        chart.judgeLines.get(0).copyProperties();
        chart.judgeLines.get(0).notes.get(0).copy();
        chart.judgeLines.get(0).eventLayers.get(0)
                .events(EventType.MOVE_X).get(0).copy();

        JSONObject exported = new JSONObject(chart.toJsonString());
        assertEquals("Edited chart", exported.getJSONObject("META").getString("name"));
        assertEquals("Edited line", exported.getJSONArray("judgeLineList")
                .getJSONObject(0).getString("Name"));
        assertEquals("keep", exported.getString("unknownRoot"));
        assertEquals("Source chart", meta.getString("name"));
        assertEquals("Source line", line.getString("Name"));
    }

    private static JSONArray beat(int whole) {
        return new JSONArray().put(whole).put(0).put(1);
    }

    private static FailOnStringJson emptyLayer() throws Exception {
        return new FailOnStringJson()
                .putValue("moveXEvents", new JSONArray())
                .putValue("moveYEvents", new JSONArray())
                .putValue("rotateEvents", new JSONArray())
                .putValue("alphaEvents", new JSONArray())
                .putValue("speedEvents", new JSONArray());
    }

    private static final class FailOnStringJson extends JSONObject {
        FailOnStringJson putValue(String key, Object value) throws Exception {
            put(key, value);
            return this;
        }

        @Override
        public String toString() {
            throw new AssertionError("Raw JSON was serialized while building the typed model");
        }
    }
}
