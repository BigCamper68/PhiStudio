package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class EventCutCommandTest {
    @Test
    public void cutIsOneUndoableCommandAndDoesNotMutateBeforeExecution() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(EventType.MOVE_X, 0, 4, 0.0, 100.0);
        target.linkGroup = 7;
        target.easingType = 4;
        layer.events(EventType.MOVE_X).add(target);

        double expectedCutValue = target.valueAt(2.0);
        EventCutCommand.CutOperation operation = EventCutCommand.cut(
                layer, target, new BeatTime(2, 0, 1));
        EditHistory history = new EditHistory(10);

        assertEquals(new BeatTime(4, 0, 1), target.endTime);
        assertEquals(1, layer.events(EventType.MOVE_X).size());
        assertFalse(history.canUndo());

        history.execute(operation);
        LineEvent right = operation.rightEvent();
        assertEquals(2, layer.events(EventType.MOVE_X).size());
        assertSame(target, layer.events(EventType.MOVE_X).get(0));
        assertSame(right, layer.events(EventType.MOVE_X).get(1));
        assertEquals(new BeatTime(2, 0, 1), target.endTime);
        assertEquals(new BeatTime(2, 0, 1), right.startTime);
        assertEquals(expectedCutValue, target.end, 0.0001);
        assertEquals(expectedCutValue, right.start, 0.0001);
        assertEquals(7, target.linkGroup);
        assertEquals(7, right.linkGroup);
        assertEquals(4, target.easingType);
        assertEquals(4, right.easingType);
        assertTrue(history.canUndo());

        history.undo();
        assertEquals(1, layer.events(EventType.MOVE_X).size());
        assertSame(target, layer.events(EventType.MOVE_X).get(0));
        assertEquals(new BeatTime(4, 0, 1), target.endTime);
        assertEquals(100.0, target.end, 0.0);
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());

        history.redo();
        assertEquals(2, layer.events(EventType.MOVE_X).size());
        assertEquals(new BeatTime(2, 0, 1), target.endTime);
        assertEquals(new BeatTime(2, 0, 1), right.startTime);
    }

    @Test
    public void cutPreservesUnknownJsonOnBothSegments() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[{\"notes\":[],\"eventLayers\":[{"
                + "\"moveXEvents\":[{\"startTime\":[0,0,1],\"endTime\":[2,0,1],"
                + "\"start\":0,\"end\":20,\"easingType\":1,\"futureEventData\":77}],"
                + "\"moveYEvents\":[],\"rotateEvents\":[],\"alphaEvents\":[],"
                + "\"speedEvents\":[]}]}]}");
        EventLayer layer = chart.judgeLines.get(0).eventLayers.get(0);
        LineEvent target = layer.events(EventType.MOVE_X).get(0);
        EditHistory history = new EditHistory(10);
        history.execute(EventCutCommand.cut(layer, target, new BeatTime(1, 0, 1)));

        JSONObject exportedLayer = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0)
                .getJSONArray("eventLayers").getJSONObject(0);
        JSONArray events = exportedLayer.getJSONArray("moveXEvents");
        assertEquals(2, events.length());
        assertEquals(77, events.getJSONObject(0).getInt("futureEventData"));
        assertEquals(77, events.getJSONObject(1).getInt("futureEventData"));
        assertEquals(10.0, events.getJSONObject(0).getDouble("end"), 0.0001);
        assertEquals(10.0, events.getJSONObject(1).getDouble("start"), 0.0001);
    }

    @Test
    public void cutKeepsLockedValuesEqualAndSpeedLinear() {
        EventLayer layer = new EventLayer();
        LineEvent speed = event(EventType.SPEED, 0, 2, 12.0, 12.0);
        speed.easingType = 9;
        layer.events(EventType.SPEED).add(speed);

        EventCutCommand.CutOperation operation = EventCutCommand.cut(
                layer, speed, new BeatTime(1, 0, 1));
        operation.apply();

        assertEquals(12.0, speed.start, 0.0);
        assertEquals(12.0, speed.end, 0.0);
        assertEquals(12.0, operation.rightEvent().start, 0.0);
        assertEquals(12.0, operation.rightEvent().end, 0.0);
        assertEquals(1, speed.easingType);
        assertEquals(1, operation.rightEvent().easingType);
    }

    @Test
    public void speedEvaluationIsLinearEvenForMalformedImportedEasing() {
        LineEvent speed = event(EventType.SPEED, 0, 4, 10.0, 18.0);
        speed.easingType = 9;

        assertEquals(12.0, speed.valueAt(1.0), 0.0001);
        assertEquals(14.0, speed.valueAt(2.0), 0.0001);
        assertEquals(16.0, speed.valueAt(3.0), 0.0001);
    }

    @Test
    public void nonlinearCutPreservesOriginalCurveOnBothSegments() {
        EventLayer layer = new EventLayer();
        LineEvent target = event(EventType.MOVE_X, 0, 4, -50.0, 150.0);
        target.easingType = 8;
        target.easingLeft = 0.1;
        target.easingRight = 0.9;
        layer.events(EventType.MOVE_X).add(target);

        LineEvent original = target.copy();
        EventCutCommand.CutOperation operation = EventCutCommand.cut(
                layer, target, new BeatTime(2, 0, 1));
        operation.apply();
        LineEvent right = operation.rightEvent();

        assertEquals(0.5, target.easingRight, 1.0e-9);
        assertEquals(0.5, right.easingLeft, 1.0e-9);
        assertEquals(original.valueAt(0.5), target.valueAt(0.5), 1.0e-8);
        assertEquals(original.valueAt(1.5), target.valueAt(1.5), 1.0e-8);
        assertEquals(original.valueAt(2.5), right.valueAt(2.5), 1.0e-8);
        assertEquals(original.valueAt(3.5), right.valueAt(3.5), 1.0e-8);
    }

    private static LineEvent event(EventType type, int startBeat, int endBeat,
                                   double startValue, double endValue) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(endBeat, 0, 1);
        event.start = startValue;
        event.end = endValue;
        return event;
    }
}
