package com.xpe.mobile.model;

import com.xpe.mobile.editor.EditHistory;
import com.xpe.mobile.editor.EventDragCommand;
import com.xpe.mobile.editor.PropertyEditCommand;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SpeedSourceOrderRegressionTest {
    private EventLayer importedLayer() throws Exception {
        JSONArray events = new JSONArray()
                .put(event(5, 6, 10)).put(event(0, 10, 1));
        return EventLayer.fromJson(new JSONObject().put("speedEvents", events)
                .put("moveXEvents", events));
    }

    private JSONObject event(int start, int end, int speed) throws Exception {
        return new JSONObject().put("startTime", new JSONArray().put(start).put(0).put(1))
                .put("endTime", new JSONArray().put(end).put(0).put(1))
                .put("start", speed).put("end", speed);
    }

    @Test public void untouchedImportAndSaveRetainSpeedOrder() throws Exception {
        EventLayer layer = importedLayer();
        assertEquals(5, layer.events(EventType.SPEED).get(0).startTime.toDouble(), 0);
        assertEquals(0, layer.events(EventType.MOVE_X).get(0).startTime.toDouble(), 0);
        JSONObject saved = layer.toJson();
        assertEquals(5, saved.getJSONArray("speedEvents").getJSONObject(0)
                .getJSONArray("startTime").getInt(0));
        EventLayer reopened = EventLayer.fromJson(saved);
        assertEquals(5, reopened.events(EventType.SPEED).get(0).startTime.toDouble(), 0);
        assertEquals(0, reopened.events(EventType.SPEED).get(1).startTime.toDouble(), 0);
    }

    @Test public void explicitTimeEditsStillSortSpeedEvents() throws Exception {
        EventLayer layer = importedLayer();
        LineEvent target = layer.events(EventType.SPEED).get(0);
        EditHistory.Command drag = EventDragCommand.move(layer, target, target.startTime, target.endTime,
                new BeatTime(3, 0, 1), new BeatTime(4, 0, 1));
        drag.apply();
        assertEquals(0, layer.events(EventType.SPEED).get(0).startTime.toDouble(), 0);
        assertEquals(3, layer.events(EventType.SPEED).get(1).startTime.toDouble(), 0);
        drag.revert();
        assertEquals(5, layer.events(EventType.SPEED).get(1).startTime.toDouble(), 0);

        LineEvent before = target.copy(), after = target.copy();
        after.startTime = new BeatTime(-1, 0, 1);
        after.endTime = BeatTime.zero();
        EditHistory.Command property = PropertyEditCommand.event(layer, target, before, after);
        property.apply();
        assertEquals(-1, layer.events(EventType.SPEED).get(0).startTime.toDouble(), 0);
        property.revert();
        assertEquals(5, layer.events(EventType.SPEED).get(1).startTime.toDouble(), 0);
        assertEquals(0, layer.toJson().getJSONArray("speedEvents").getJSONObject(0)
                .getJSONArray("startTime").getInt(0));
    }
}
