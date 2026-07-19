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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class EventDragCommandTest {
    @Test
    public void completedDragCreatesOneUndoStepAndRestoresTimes() {
        EventLayer layer = new EventLayer();
        LineEvent earlier = event(EventType.MOVE_X, 0, 1);
        LineEvent target = event(EventType.MOVE_X, 2, 3);
        target.start = 15.0;
        target.end = 15.0;
        target.linkGroup = 7;
        layer.events(EventType.MOVE_X).add(earlier);
        layer.events(EventType.MOVE_X).add(target);

        EditHistory history = new EditHistory(10);
        EditHistory.Command command = EventDragCommand.move(layer, target,
                target.startTime, target.endTime,
                new BeatTime(1, 0, 1), new BeatTime(2, 0, 1));

        assertEquals(new BeatTime(2, 0, 1), target.startTime);
        assertFalse(history.canUndo());

        history.execute(command);
        assertEquals(new BeatTime(1, 0, 1), target.startTime);
        assertEquals(new BeatTime(2, 0, 1), target.endTime);
        assertSame(target, layer.events(EventType.MOVE_X).get(1));
        assertEquals(15.0, target.start, 0.0);
        assertEquals(15.0, target.end, 0.0);
        assertEquals(7, target.linkGroup);
        assertTrue(history.canUndo());

        history.undo();
        assertEquals(new BeatTime(2, 0, 1), target.startTime);
        assertEquals(new BeatTime(3, 0, 1), target.endTime);
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());

        history.redo();
        assertEquals(new BeatTime(1, 0, 1), target.startTime);
        assertEquals(new BeatTime(2, 0, 1), target.endTime);
    }

    @Test
    public void startAndEndHandlesChangeOnlyTheirOwnEndpoint() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(EventType.ROTATE, 1, 3);
        layer.events(EventType.ROTATE).add(target);
        EditHistory history = new EditHistory(10);

        history.execute(EventDragCommand.move(layer, target,
                target.startTime, target.endTime,
                new BeatTime(2, 0, 1), target.endTime));
        assertEquals(new BeatTime(2, 0, 1), target.startTime);
        assertEquals(new BeatTime(3, 0, 1), target.endTime);
        history.undo();

        history.execute(EventDragCommand.move(layer, target,
                target.startTime, target.endTime,
                target.startTime, new BeatTime(4, 0, 1)));
        assertEquals(new BeatTime(1, 0, 1), target.startTime);
        assertEquals(new BeatTime(4, 0, 1), target.endTime);
    }

    @Test
    public void dragPreservesUnknownJsonAndSpeedLinearEasing() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[{\"notes\":[],\"eventLayers\":[{"
                + "\"moveXEvents\":[{\"startTime\":[0,0,1],\"endTime\":[1,0,1],"
                + "\"start\":0,\"end\":10,\"easingType\":1,\"futureEventData\":77}],"
                + "\"moveYEvents\":[],\"rotateEvents\":[],\"alphaEvents\":[],"
                + "\"speedEvents\":[{\"startTime\":[2,0,1],\"endTime\":[3,0,1],"
                + "\"start\":10,\"end\":12,\"easingType\":9,\"futureSpeedData\":88}]}]}]}");

        EventLayer layer = chart.judgeLines.get(0).eventLayers.get(0);
        LineEvent move = layer.events(EventType.MOVE_X).get(0);
        LineEvent speed = layer.events(EventType.SPEED).get(0);
        EditHistory history = new EditHistory(10);
        history.execute(EventDragCommand.move(layer, move, move.startTime, move.endTime,
                new BeatTime(1, 0, 1), new BeatTime(2, 0, 1)));
        history.execute(EventDragCommand.move(layer, speed, speed.startTime, speed.endTime,
                new BeatTime(3, 0, 1), new BeatTime(4, 0, 1)));

        assertEquals(1, speed.easingType);
        JSONObject line = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0);
        JSONObject exportedMove = line.getJSONArray("eventLayers").getJSONObject(0)
                .getJSONArray("moveXEvents").getJSONObject(0);
        JSONObject exportedSpeed = line.getJSONArray("eventLayers").getJSONObject(0)
                .getJSONArray("speedEvents").getJSONObject(0);
        assertEquals(77, exportedMove.getInt("futureEventData"));
        assertEquals(88, exportedSpeed.getInt("futureSpeedData"));
        assertEquals(1, exportedMove.getJSONArray("startTime").getInt(0));
        assertEquals(3, exportedSpeed.getJSONArray("startTime").getInt(0));
    }

    private static LineEvent event(EventType type, int start, int end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = new BeatTime(start, 0, 1);
        event.endTime = new BeatTime(end, 0, 1);
        return event;
    }
}
