package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EventGlueCommandTest {
    @Test
    public void glueChangesOnlyStartValueAndSupportsUndoRedo() {
        LineEvent previous = event(0, 1, 10.0, 42.0);
        LineEvent target = event(2, 3, -5.0, 80.0);
        target.linkGroup = 9;
        target.easingType = 6;
        EditHistory history = new EditHistory(10);
        EditHistory.Command command = EventGlueCommand.glue(previous, target);

        assertEquals(-5.0, target.start, 0.0);
        assertFalse(history.canUndo());

        history.execute(command);
        assertEquals(42.0, target.start, 0.0);
        assertEquals(80.0, target.end, 0.0);
        assertEquals(new BeatTime(2, 0, 1), target.startTime);
        assertEquals(new BeatTime(3, 0, 1), target.endTime);
        assertEquals(9, target.linkGroup);
        assertEquals(6, target.easingType);
        assertTrue(history.canUndo());

        history.undo();
        assertEquals(-5.0, target.start, 0.0);
        assertTrue(history.canRedo());
        history.redo();
        assertEquals(42.0, target.start, 0.0);
    }

    @Test
    public void gluePreservesUnknownJsonAndInvalidImportedTimes() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[{\"notes\":[],\"eventLayers\":[{"
                + "\"moveXEvents\":["
                + "{\"startTime\":[0,0,1],\"endTime\":[1,0,1],\"start\":0,\"end\":30},"
                + "{\"startTime\":[2,0,1],\"endTime\":[1,0,1],\"start\":5,\"end\":9,"
                + "\"futureEventData\":88}],"
                + "\"moveYEvents\":[],\"rotateEvents\":[],\"alphaEvents\":[],"
                + "\"speedEvents\":[]}]}]}");
        EventLayer layer = chart.judgeLines.get(0).eventLayers.get(0);
        LineEvent previous = layer.events(EventType.MOVE_X).get(0);
        LineEvent importedInvalid = layer.events(EventType.MOVE_X).get(1);
        EventGlueCommand.glue(previous, importedInvalid).apply();

        assertEquals(new BeatTime(2, 0, 1), importedInvalid.startTime);
        assertEquals(new BeatTime(1, 0, 1), importedInvalid.endTime);
        JSONObject exported = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0)
                .getJSONArray("eventLayers").getJSONObject(0)
                .getJSONArray("moveXEvents").getJSONObject(1);
        assertEquals(88, exported.getInt("futureEventData"));
        assertEquals(30.0, exported.getDouble("start"), 0.0);
        assertEquals(2, exported.getJSONArray("startTime").getInt(0));
        assertEquals(1, exported.getJSONArray("endTime").getInt(0));
    }

    private static LineEvent event(int startBeat, int endBeat,
                                   double startValue, double endValue) {
        LineEvent event = new LineEvent();
        event.type = EventType.MOVE_X;
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(endBeat, 0, 1);
        event.start = startValue;
        event.end = endValue;
        return event;
    }
}
