package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EventDragCommandTest {
    @Test
    public void movesOneEndpointAndSupportsUndoRedo() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(EventType.MOVE_X, 1, 3);
        layer.events(EventType.MOVE_X).add(target);
        EditHistory history = new EditHistory(10);

        history.execute(EventDragCommand.move(layer, target,
                target.startTime, target.endTime,
                new BeatTime(2, 0, 1), target.endTime));

        assertEquals(new BeatTime(2, 0, 1), target.startTime);
        assertEquals(new BeatTime(3, 0, 1), target.endTime);
        history.undo();
        assertEquals(new BeatTime(1, 0, 1), target.startTime);
        history.redo();
        assertEquals(new BeatTime(2, 0, 1), target.startTime);
    }

    @Test
    public void movesBothEndpointsAndKeepsSortedOrder() {
        EventLayer layer = new EventLayer();
        LineEvent first = event(EventType.MOVE_Y, 0, 1);
        LineEvent target = event(EventType.MOVE_Y, 2, 3);
        LineEvent last = event(EventType.MOVE_Y, 4, 5);
        layer.events(EventType.MOVE_Y).add(first);
        layer.events(EventType.MOVE_Y).add(target);
        layer.events(EventType.MOVE_Y).add(last);
        EditHistory history = new EditHistory(10);

        history.execute(EventDragCommand.move(layer, target,
                target.startTime, target.endTime,
                new BeatTime(5, 0, 1), new BeatTime(6, 0, 1)));

        assertEquals(last, layer.events(EventType.MOVE_Y).get(1));
        assertEquals(target, layer.events(EventType.MOVE_Y).get(2));
        history.undo();
        assertEquals(target, layer.events(EventType.MOVE_Y).get(1));
        history.redo();
        assertEquals(target, layer.events(EventType.MOVE_Y).get(2));
    }

    @Test
    public void preservesOtherFieldsDuringMove() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(EventType.ROTATE, 1, 2);
        target.start = 15.0;
        target.end = 45.0;
        target.easingType = 9;
        target.easingLeft = 0.2;
        target.easingRight = 0.8;
        target.bezier = true;
        target.bezierPoints[0] = 0.1;
        target.bezierPoints[1] = 0.3;
        target.bezierPoints[2] = 0.7;
        target.bezierPoints[3] = 0.9;
        target.linkGroup = 5;
        layer.events(EventType.ROTATE).add(target);
        EditHistory history = new EditHistory(10);

        history.execute(EventDragCommand.move(layer, target,
                target.startTime, target.endTime,
                new BeatTime(3, 0, 1), new BeatTime(4, 0, 1)));

        assertEquals(15.0, target.start, 0.0);
        assertEquals(45.0, target.end, 0.0);
        assertEquals(9, target.easingType);
        assertEquals(0.2, target.easingLeft, 0.0);
        assertEquals(0.8, target.easingRight, 0.0);
        assertEquals(true, target.bezier);
        assertEquals(0.1, target.bezierPoints[0], 0.0);
        assertEquals(0.3, target.bezierPoints[1], 0.0);
        assertEquals(0.7, target.bezierPoints[2], 0.0);
        assertEquals(0.9, target.bezierPoints[3], 0.0);
        assertEquals(5, target.linkGroup);
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
        // Index 0 is now the neutral compatibility interval inserted before a delayed
        // first event; the edited source event follows it and must retain opaque data.
        JSONObject exportedMove = line.getJSONArray("eventLayers").getJSONObject(0)
                .getJSONArray("moveXEvents").getJSONObject(1);
        JSONObject exportedSpeed = line.getJSONArray("eventLayers").getJSONObject(0)
                .getJSONArray("speedEvents").getJSONObject(1);
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
