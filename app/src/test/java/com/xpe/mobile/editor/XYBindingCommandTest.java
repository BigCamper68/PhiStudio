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

public final class XYBindingCommandTest {
    @Test
    public void pairedPlacementIsOneUndoableHistoryEntry() {
        EventLayer layer = new EventLayer();
        LineEvent moveX = event(EventType.MOVE_X, 1, 2);
        LineEvent moveY = event(EventType.MOVE_Y, 1, 2);
        EditHistory history = new EditHistory(10);

        history.execute(XYBindingCommand.add(layer, moveX, moveY));
        assertEquals(1, layer.events(EventType.MOVE_X).size());
        assertEquals(1, layer.events(EventType.MOVE_Y).size());
        assertTrue(history.canUndo());

        history.undo();
        assertEquals(0, layer.events(EventType.MOVE_X).size());
        assertEquals(0, layer.events(EventType.MOVE_Y).size());
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());

        history.redo();
        assertSame(moveX, layer.events(EventType.MOVE_X).get(0));
        assertSame(moveY, layer.events(EventType.MOVE_Y).get(0));
    }

    @Test
    public void pairedDragMovesBothAndUndoRestoresBoth() {
        EventLayer layer = new EventLayer();
        LineEvent moveX = event(EventType.MOVE_X, 1, 2);
        LineEvent moveY = event(EventType.MOVE_Y, 1, 2);
        layer.events(EventType.MOVE_X).add(moveX);
        layer.events(EventType.MOVE_Y).add(moveY);
        EditHistory history = new EditHistory(10);

        history.execute(XYBindingCommand.move(layer,
                moveX, moveX.startTime, moveX.endTime,
                moveY, moveY.startTime, moveY.endTime,
                beat(2), beat(3)));
        assertEquals(beat(2), moveX.startTime);
        assertEquals(beat(2), moveY.startTime);
        assertEquals(beat(3), moveX.endTime);
        assertEquals(beat(3), moveY.endTime);

        history.undo();
        assertEquals(beat(1), moveX.startTime);
        assertEquals(beat(1), moveY.startTime);
        assertEquals(beat(2), moveX.endTime);
        assertEquals(beat(2), moveY.endTime);

        history.redo();
        assertEquals(beat(2), moveX.startTime);
        assertEquals(beat(2), moveY.startTime);
    }

    @Test
    public void propertyEditChangesTargetFieldsButOnlyPairTimes() {
        EventLayer layer = new EventLayer();
        LineEvent moveX = event(EventType.MOVE_X, 1, 2);
        moveX.start = 10.0;
        moveX.end = 20.0;
        LineEvent moveY = event(EventType.MOVE_Y, 1, 2);
        moveY.start = 30.0;
        moveY.end = 40.0;
        layer.events(EventType.MOVE_X).add(moveX);
        layer.events(EventType.MOVE_Y).add(moveY);

        LineEvent editedX = moveX.copy();
        editedX.startTime = beat(2);
        editedX.endTime = beat(3);
        editedX.start = 100.0;
        editedX.end = 200.0;
        EditHistory history = new EditHistory(10);
        history.execute(XYBindingCommand.editWithPairedTimes(
                layer, moveX, moveX.copy(), editedX,
                moveY, moveY.startTime, moveY.endTime));

        assertEquals(100.0, moveX.start, 0.0);
        assertEquals(200.0, moveX.end, 0.0);
        assertEquals(30.0, moveY.start, 0.0);
        assertEquals(40.0, moveY.end, 0.0);
        assertEquals(beat(2), moveY.startTime);
        assertEquals(beat(3), moveY.endTime);

        history.undo();
        assertEquals(10.0, moveX.start, 0.0);
        assertEquals(beat(1), moveX.startTime);
        assertEquals(beat(1), moveY.startTime);
        history.redo();
        assertEquals(100.0, moveX.start, 0.0);
        assertEquals(beat(2), moveX.startTime);
        assertEquals(beat(2), moveY.startTime);
    }

    @Test
    public void pairedDeleteAndCutAreAtomic() {
        EventLayer layer = new EventLayer();
        LineEvent moveX = event(EventType.MOVE_X, 0, 4);
        moveX.start = 0.0;
        moveX.end = 40.0;
        LineEvent moveY = event(EventType.MOVE_Y, 0, 4);
        moveY.start = 100.0;
        moveY.end = 140.0;
        layer.events(EventType.MOVE_X).add(moveX);
        layer.events(EventType.MOVE_Y).add(moveY);
        EditHistory history = new EditHistory(10);

        history.execute(XYBindingCommand.delete(layer, moveX, moveY));
        assertEquals(0, layer.events(EventType.MOVE_X).size());
        assertEquals(0, layer.events(EventType.MOVE_Y).size());
        history.undo();
        assertEquals(1, layer.events(EventType.MOVE_X).size());
        assertEquals(1, layer.events(EventType.MOVE_Y).size());
        history.redo();
        assertEquals(0, layer.events(EventType.MOVE_X).size());
        assertEquals(0, layer.events(EventType.MOVE_Y).size());
        history.undo();

        EventCutCommand.CutOperation cutX = EventCutCommand.cut(layer, moveX, beat(2));
        EventCutCommand.CutOperation cutY = EventCutCommand.cut(layer, moveY, beat(2));
        history.execute(XYBindingCommand.cut(cutX, cutY));
        assertEquals(2, layer.events(EventType.MOVE_X).size());
        assertEquals(2, layer.events(EventType.MOVE_Y).size());
        assertEquals(beat(2), cutX.rightEvent().startTime);
        assertEquals(beat(2), cutY.rightEvent().startTime);

        history.undo();
        assertEquals(1, layer.events(EventType.MOVE_X).size());
        assertEquals(1, layer.events(EventType.MOVE_Y).size());
        assertEquals(beat(4), moveX.endTime);
        assertEquals(beat(4), moveY.endTime);
        history.redo();
        assertEquals(2, layer.events(EventType.MOVE_X).size());
        assertEquals(2, layer.events(EventType.MOVE_Y).size());
    }

    @Test
    public void pairedMutationsPreserveUnknownJsonFields() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[{\"notes\":[],\"eventLayers\":[{"
                + "\"moveXEvents\":[{\"startTime\":[0,0,1],\"endTime\":[2,0,1],"
                + "\"start\":0,\"end\":20,\"futureX\":77}],"
                + "\"moveYEvents\":[{\"startTime\":[0,0,1],\"endTime\":[2,0,1],"
                + "\"start\":10,\"end\":30,\"futureY\":88}],"
                + "\"rotateEvents\":[],\"alphaEvents\":[],\"speedEvents\":[]}]}]}");
        EventLayer layer = chart.judgeLines.get(0).eventLayers.get(0);
        LineEvent moveX = layer.events(EventType.MOVE_X).get(0);
        LineEvent moveY = layer.events(EventType.MOVE_Y).get(0);
        EditHistory history = new EditHistory(10);
        history.execute(XYBindingCommand.move(layer,
                moveX, moveX.startTime, moveX.endTime,
                moveY, moveY.startTime, moveY.endTime,
                beat(1), beat(3)));

        JSONObject exportedLayer = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0)
                .getJSONArray("eventLayers").getJSONObject(0);
        assertEquals(77, exportedLayer.getJSONArray("moveXEvents")
                .getJSONObject(0).getInt("futureX"));
        assertEquals(88, exportedLayer.getJSONArray("moveYEvents")
                .getJSONObject(0).getInt("futureY"));
    }


    @Test
    public void pairedCutCopiesUnknownJsonToBothNewSegments() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[{\"notes\":[],\"eventLayers\":[{"
                + "\"moveXEvents\":[{\"startTime\":[0,0,1],\"endTime\":[2,0,1],"
                + "\"start\":0,\"end\":20,\"futureX\":77}],"
                + "\"moveYEvents\":[{\"startTime\":[0,0,1],\"endTime\":[2,0,1],"
                + "\"start\":10,\"end\":30,\"futureY\":88}],"
                + "\"rotateEvents\":[],\"alphaEvents\":[],\"speedEvents\":[]}]}]}");
        EventLayer layer = chart.judgeLines.get(0).eventLayers.get(0);
        LineEvent moveX = layer.events(EventType.MOVE_X).get(0);
        LineEvent moveY = layer.events(EventType.MOVE_Y).get(0);
        EditHistory history = new EditHistory(10);
        history.execute(XYBindingCommand.cut(
                EventCutCommand.cut(layer, moveX, beat(1)),
                EventCutCommand.cut(layer, moveY, beat(1))));

        JSONObject exportedLayer = new JSONObject(chart.toJsonString())
                .getJSONArray("judgeLineList").getJSONObject(0)
                .getJSONArray("eventLayers").getJSONObject(0);
        assertEquals(2, exportedLayer.getJSONArray("moveXEvents").length());
        assertEquals(2, exportedLayer.getJSONArray("moveYEvents").length());
        assertEquals(77, exportedLayer.getJSONArray("moveXEvents")
                .getJSONObject(1).getInt("futureX"));
        assertEquals(88, exportedLayer.getJSONArray("moveYEvents")
                .getJSONObject(1).getInt("futureY"));
    }

    private static LineEvent event(EventType type, double start, double end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(start);
        event.endTime = beat(end);
        return event;
    }

    private static BeatTime beat(double value) {
        return BeatTime.fromDouble(value, 4);
    }
}
