package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public final class ChartClipboardTest {
    @Test
    public void pasteAlignsEarliestBeatMirrorsNotesAndPreservesUnknownJson() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],\"META\":{},"
                + "\"judgeLineList\":[{\"notes\":[{\"type\":1,\"startTime\":[1,1,3],"
                + "\"endTime\":[1,1,3],\"positionX\":120,\"futureNote\":77}],"
                + "\"eventLayers\":[{\"moveXEvents\":[],\"moveYEvents\":[],"
                + "\"rotateEvents\":[{\"startTime\":[2,0,1],\"endTime\":[3,0,1],"
                + "\"start\":0,\"end\":10,\"futureEvent\":88}],"
                + "\"alphaEvents\":[],\"speedEvents\":[]}]}]}");
        JudgeLine sourceLine = chart.judgeLines.get(0);
        Note sourceNote = sourceLine.notes.get(0);
        LineEvent sourceEvent = sourceLine.eventLayers.get(0).events(EventType.ROTATE).get(0);
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(
                Collections.singletonList(sourceNote), Collections.singletonList(sourceEvent));

        JudgeLine targetLine = new JudgeLine();
        targetLine.notes.clear();
        targetLine.eventLayers.clear();
        EventLayer targetLayer = new EventLayer();
        targetLine.eventLayers.add(targetLayer);
        ChartClipboard.Operation operation = ChartClipboard.preparePaste(snapshot,
                targetLine, targetLayer, 0, beat(4), true, false);
        assertEquals(ChartClipboard.Error.NONE, operation.error);

        EditHistory history = new EditHistory(10);
        history.execute(operation.command);
        assertEquals(1, targetLine.notes.size());
        assertEquals(-120.0, targetLine.notes.get(0).positionX, 0.0);
        assertEquals(beat(4), targetLine.notes.get(0).startTime);
        assertEquals(new BeatTime(4, 2, 3),
                targetLayer.events(EventType.ROTATE).get(0).startTime);

        JSONObject noteJson = targetLine.notes.get(0).toJson();
        JSONObject eventJson = targetLayer.events(EventType.ROTATE).get(0).toJson();
        assertEquals(77, noteJson.getInt("futureNote"));
        assertEquals(88, eventJson.getInt("futureEvent"));
        history.undo();
        assertEquals(0, targetLine.notes.size());
        history.redo();
        assertEquals(1, targetLine.notes.size());
    }

    @Test
    public void pasteRejectsExistingAndInternalSameTypeOverlapWithoutMutation() {
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        EventLayer layer = line.eventLayers.get(0);
        clearEvents(layer);
        LineEvent existing = event(EventType.ROTATE, 4, 6);
        layer.events(EventType.ROTATE).add(existing);
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(Collections.emptyList(),
                Collections.singletonList(event(EventType.ROTATE, 0, 2)));

        ChartClipboard.Operation overlap = ChartClipboard.preparePaste(snapshot,
                line, layer, 0, beat(5), false, false);
        assertEquals(ChartClipboard.Error.EVENT_OVERLAP, overlap.error);
        assertEquals(1, layer.events(EventType.ROTATE).size());

        ChartClipboard.Snapshot internal = ChartClipboard.copy(Collections.emptyList(),
                Arrays.asList(event(EventType.ALPHA, 0, 2), event(EventType.ALPHA, 1, 3)));
        assertEquals(ChartClipboard.Error.EVENT_OVERLAP,
                ChartClipboard.preparePaste(internal, line, layer, 0,
                        beat(8), false, false).error);
        assertEquals(0, layer.events(EventType.ALPHA).size());
    }

    @Test
    public void xyBindingRequiresCompletePairsForPasteAndCut() {
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        EventLayer layer = line.eventLayers.get(0);
        clearEvents(layer);
        LineEvent moveX = event(EventType.MOVE_X, 0, 1);
        LineEvent moveY = event(EventType.MOVE_Y, 0, 1);
        layer.events(EventType.MOVE_X).add(moveX);
        layer.events(EventType.MOVE_Y).add(moveY);

        ChartClipboard.Snapshot partial = ChartClipboard.copy(Collections.emptyList(),
                Collections.singletonList(moveX));
        assertEquals(ChartClipboard.Error.XY_PAIR_REQUIRED,
                ChartClipboard.preparePaste(partial, new JudgeLine(), new EventLayer(),
                        0, beat(2), false, true).error);
        assertEquals(ChartClipboard.Error.XY_PAIR_REQUIRED,
                ChartClipboard.prepareCut(line, layer, Collections.emptyList(),
                        Collections.singletonList(moveX), true).error);
        assertSame(moveX, layer.events(EventType.MOVE_X).get(0));

        ChartClipboard.Operation cut = ChartClipboard.prepareCut(line, layer,
                Collections.emptyList(), Arrays.asList(moveY, moveX), true);
        assertEquals(ChartClipboard.Error.NONE, cut.error);
        EditHistory history = new EditHistory(10);
        history.execute(cut.command);
        assertEquals(0, layer.events(EventType.MOVE_X).size());
        assertEquals(0, layer.events(EventType.MOVE_Y).size());
        history.undo();
        assertSame(moveX, layer.events(EventType.MOVE_X).get(0));
        assertSame(moveY, layer.events(EventType.MOVE_Y).get(0));
        history.redo();
        assertFalse(history.canRedo());
        assertEquals(0, layer.events(EventType.MOVE_X).size());
    }

    @Test
    public void eventsCannotPasteToReservedLayerButNoteOnlyClipboardCan() {
        JudgeLine line = new JudgeLine();
        EventLayer layer = new EventLayer();
        ChartClipboard.Snapshot eventSnapshot = ChartClipboard.copy(Collections.emptyList(),
                Collections.singletonList(event(EventType.SPEED, 0, 1)));
        assertEquals(ChartClipboard.Error.RESERVED_LAYER,
                ChartClipboard.preparePaste(eventSnapshot, line, layer, 4,
                        beat(1), false, false).error);

        Note note = new Note();
        ChartClipboard.Snapshot noteSnapshot = ChartClipboard.copy(
                Collections.singletonList(note), Collections.emptyList());
        assertEquals(ChartClipboard.Error.NONE,
                ChartClipboard.preparePaste(noteSnapshot, line, layer, 4,
                        beat(1), false, false).error);
    }

    private static LineEvent event(EventType type, int start, int end) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(start);
        event.endTime = beat(end);
        return event;
    }

    private static BeatTime beat(int value) {
        return new BeatTime(value, 0, 1);
    }

    private static void clearEvents(EventLayer layer) {
        for (EventType type : EventType.values()) layer.events(type).clear();
    }
}
