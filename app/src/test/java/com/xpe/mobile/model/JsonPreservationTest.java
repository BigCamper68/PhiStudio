package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class JsonPreservationTest {
    @Test
    public void preservesUnknownFieldsAtEverySupportedObjectLevel() throws Exception {
        JSONObject bpm = new JSONObject()
                .put("bpm", 120.0)
                .put("startTime", new JSONArray().put(0).put(0).put(1))
                .put("futureBpmData", new JSONObject().put("mode", "custom"));
        JSONObject note = new JSONObject()
                .put("above", 1)
                .put("alpha", 255)
                .put("endTime", new JSONArray().put(1).put(0).put(1))
                .put("isFake", 0)
                .put("positionX", 10.0)
                .put("size", 1.0)
                .put("speed", 1.0)
                .put("startTime", new JSONArray().put(1).put(0).put(1))
                .put("type", 1)
                .put("visibleTime", 5.0)
                .put("yOffset", 0.0)
                .put("futureNoteData", new JSONObject().put("color", "cyan"));
        JSONObject event = new JSONObject()
                .put("startTime", new JSONArray().put(0).put(0).put(1))
                .put("endTime", new JSONArray().put(1).put(0).put(1))
                .put("start", 0.0)
                .put("end", 10.0)
                .put("easingType", 1)
                .put("easingLeft", 0.0)
                .put("easingRight", 1.0)
                .put("linkgroup", 0)
                .put("futureEventData", 77);

        JSONArray layers = new JSONArray();
        layers.put(layerWithMoveX(event));
        layers.put(emptyLayer());
        layers.put(emptyLayer());
        layers.put(emptyLayer());
        layers.put(emptyLayer().put("specialEvents",
                new JSONArray().put(new JSONObject().put("storyboardPayload", "keep"))));

        JSONObject line = new JSONObject()
                .put("Name", "Line 0")
                .put("notes", new JSONArray().put(note))
                .put("eventLayers", layers)
                .put("futureLineData", new JSONArray().put(1).put(2));
        JSONObject root = new JSONObject()
                .put("BPMList", new JSONArray().put(bpm))
                .put("META", new JSONObject().put("name", "Test").put("futureMetaData", true))
                .put("judgeLineList", new JSONArray().put(line))
                .put("futureRootData", "keep-root");

        ChartDocument chart = ChartDocument.fromJson(root.toString());
        chart.bpmChanges.get(0).bpm = 150.0;
        chart.judgeLines.get(0).notes.get(0).positionX = 25.0;
        JSONObject exported = new JSONObject(chart.toJsonString());

        assertEquals("keep-root", exported.getString("futureRootData"));
        assertEquals(true, exported.getJSONObject("META").getBoolean("futureMetaData"));
        assertEquals("custom", exported.getJSONArray("BPMList").getJSONObject(0)
                .getJSONObject("futureBpmData").getString("mode"));
        JSONObject exportedLine = exported.getJSONArray("judgeLineList").getJSONObject(0);
        assertEquals(2, exportedLine.getJSONArray("futureLineData").length());
        assertEquals("cyan", exportedLine.getJSONArray("notes").getJSONObject(0)
                .getJSONObject("futureNoteData").getString("color"));
        assertEquals(77, exportedLine.getJSONArray("eventLayers").getJSONObject(0)
                .getJSONArray("moveXEvents").getJSONObject(0).getInt("futureEventData"));
        assertEquals("keep", exportedLine.getJSONArray("eventLayers").getJSONObject(4)
                .getJSONArray("specialEvents").getJSONObject(0).getString("storyboardPayload"));
    }

    @Test
    public void preservesUntouchedNullEventLayersAndMaterializesEditedOnes() throws Exception {
        JSONObject root = new JSONObject()
                .put("BPMList", new JSONArray().put(new JSONObject()
                        .put("bpm", 120.0)
                        .put("startTime", new JSONArray().put(0).put(0).put(1))))
                .put("META", new JSONObject())
                .put("judgeLineList", new JSONArray().put(new JSONObject()
                        .put("notes", new JSONArray())
                        .put("eventLayers", new JSONArray().put(JSONObject.NULL).put(emptyLayer()))));

        ChartDocument chart = ChartDocument.fromJson(root.toString());
        JSONObject untouched = new JSONObject(chart.toJsonString());
        JSONArray untouchedLayers = untouched.getJSONArray("judgeLineList")
                .getJSONObject(0).getJSONArray("eventLayers");
        assertTrue(untouchedLayers.isNull(0));
        assertFalse(untouchedLayers.isNull(1));

        EventLayer nullLayer = chart.judgeLines.get(0).layer(0);
        LineEvent marker = new LineEvent();
        marker.type = EventType.MOVE_X;
        nullLayer.events(EventType.MOVE_X).add(marker);
        nullLayer.events(EventType.MOVE_X).remove(marker);
        JSONObject materialized = new JSONObject(chart.toJsonString());
        assertFalse(materialized.getJSONArray("judgeLineList").getJSONObject(0)
                .getJSONArray("eventLayers").isNull(0));
    }

    @Test
    public void omitsEmptyEventArraysForLegacyPhiraCompatibility() throws Exception {
        JudgeLine line = new JudgeLine();
        line.layer(3);

        JSONArray layers = line.toJson().getJSONArray("eventLayers");
        assertEquals(4, layers.length());
        for (int index = 1; index < layers.length(); index++) {
            assertEquals(0, layers.getJSONObject(index).length());
        }

        EventLayer partial = line.layer(1);
        LineEvent move = new LineEvent();
        move.type = EventType.MOVE_X;
        partial.events(EventType.MOVE_X).add(move);
        JSONObject partialJson = line.toJson().getJSONArray("eventLayers").getJSONObject(1);
        assertTrue(partialJson.has("moveXEvents"));
        assertFalse(partialJson.has("moveYEvents"));
        assertFalse(partialJson.has("rotateEvents"));
        assertFalse(partialJson.has("alphaEvents"));
        assertFalse(partialJson.has("speedEvents"));
    }

    @Test
    public void countsFakeNotesButExcludesHoldsFromRpeNoteCount() throws Exception {
        JudgeLine line = new JudgeLine();
        Note fakeTap = new Note();
        fakeTap.fake = true;
        Note hold = new Note();
        hold.type = NoteType.HOLD;
        line.notes.add(fakeTap);
        line.notes.add(hold);

        JSONObject exported = line.toJson();

        assertEquals(2, exported.getJSONArray("notes").length());
        assertEquals(1, exported.getInt("numOfNotes"));
    }

    @Test
    public void readsRotationInheritanceWithoutNormalizingItsRawRepresentation() throws Exception {
        JSONObject root = new JSONObject()
                .put("BPMList", new JSONArray().put(new JSONObject()
                        .put("bpm", 120.0)
                        .put("startTime", new JSONArray().put(0).put(0).put(1))))
                .put("META", new JSONObject())
                .put("judgeLineList", new JSONArray().put(new JSONObject()
                        .put("rotateWithFather", 1)
                        .put("notes", new JSONArray())
                        .put("eventLayers", new JSONArray().put(emptyLayer()))));

        ChartDocument chart = ChartDocument.fromJson(root.toString());

        assertTrue(chart.judgeLines.get(0).rotateWithFather);
        JSONObject exportedLine = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0);
        assertEquals(1, exportedLine.getInt("rotateWithFather"));
    }

    @Test
    public void preservesTypedExtendedEventsAndNoteTintInTheirOriginalRawForm() throws Exception {
        JSONArray tint = new JSONArray().put(12).put(34).put(56).put(77);
        JSONArray hitTint = new JSONArray().put(90).put(80).put(70);
        JSONObject extended = new JSONObject()
                .put("scaleXEvents", new JSONArray().put(new JSONObject()
                        .put("startTime", new JSONArray().put(0).put(0).put(1))
                        .put("endTime", new JSONArray().put(1).put(0).put(1))
                        .put("start", 1.0).put("end", 2.0)
                        .put("futureExtendedEvent", "keep")))
                .put("futureExtendedContainer", new JSONObject().put("version", 9));
        JSONObject note = new JSONObject()
                .put("above", 1).put("alpha", 255)
                .put("startTime", new JSONArray().put(1).put(0).put(1))
                .put("endTime", new JSONArray().put(1).put(0).put(1))
                .put("isFake", 0).put("positionX", 0.0).put("size", 1.0)
                .put("speed", 1.0).put("type", 1).put("visibleTime", 9.0)
                .put("yOffset", 0.0).put("tint", tint)
                .put("tintHitEffects", hitTint).put("judgeArea", 1.75);
        JSONObject root = new JSONObject()
                .put("BPMList", new JSONArray().put(new JSONObject()
                        .put("bpm", 120.0)
                        .put("startTime", new JSONArray().put(0).put(0).put(1))))
                .put("META", new JSONObject())
                .put("judgeLineList", new JSONArray().put(new JSONObject()
                        .put("notes", new JSONArray().put(note))
                        .put("attachUI", "score")
                        .put("extended", extended)
                        .put("posControl", new JSONArray().put(new JSONObject()
                                .put("x", 0.0).put("easing", 1).put("pos", 1.0)
                                .put("futureControlValue", 42)))
                        .put("eventLayers", new JSONArray().put(emptyLayer()))));

        ChartDocument chart = ChartDocument.fromJson(root.toString());
        Note parsed = chart.judgeLines.get(0).notes.get(0);
        assertTrue(parsed.hasTint);
        assertEquals(0x0C2238, parsed.tintRgb);
        assertTrue(parsed.hasHitEffectTint);
        assertEquals(0x5A5046, parsed.hitEffectTintRgb);
        assertEquals(1.75, parsed.judgeArea, 0.0);
        assertEquals(1, chart.judgeLines.get(0).extended.scaleXEvents.size());
        assertEquals(1, chart.judgeLines.get(0).noteControls.position.size());
        assertEquals(AttachedUiElement.SCORE, chart.judgeLines.get(0).attachUi);
        parsed.positionX = 25.0;

        JSONObject exportedLine = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0);
        JSONObject exportedNote = exportedLine.getJSONArray("notes").getJSONObject(0);
        assertEquals(4, exportedNote.getJSONArray("tint").length());
        assertEquals(77, exportedNote.getJSONArray("tint").getInt(3));
        assertEquals(1.75, exportedNote.getDouble("judgeArea"), 0.0);
        assertEquals("keep", exportedLine.getJSONObject("extended")
                .getJSONArray("scaleXEvents").getJSONObject(0)
                .getString("futureExtendedEvent"));
        assertEquals(9, exportedLine.getJSONObject("extended")
                .getJSONObject("futureExtendedContainer").getInt("version"));
        assertEquals(42, exportedLine.getJSONArray("posControl")
                .getJSONObject(0).getInt("futureControlValue"));
        assertEquals("score", exportedLine.getString("attachUI"));
    }

    private static JSONObject layerWithMoveX(JSONObject event) throws Exception {
        return emptyLayer().put("moveXEvents", new JSONArray().put(event));
    }

    private static JSONObject emptyLayer() throws Exception {
        return new JSONObject()
                .put("moveXEvents", new JSONArray())
                .put("moveYEvents", new JSONArray())
                .put("rotateEvents", new JSONArray())
                .put("alphaEvents", new JSONArray())
                .put("speedEvents", new JSONArray());
    }
}
