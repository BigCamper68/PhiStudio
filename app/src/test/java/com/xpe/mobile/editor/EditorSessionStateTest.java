package com.xpe.mobile.editor;

import com.xpe.mobile.model.*;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,33})
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class EditorSessionStateTest {
    @Test public void roundTripKeepsViewportSelectionToolsAndClipboardWithoutChangingTheChart() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{}"); chart.judgeLines.add(new JudgeLine());
        while (chart.judgeLines.get(1).eventLayers.size() < 3) chart.judgeLines.get(1).eventLayers.add(new EventLayer());
        Note note = new Note(); note.startTime = new BeatTime(421,3,4); note.endTime = note.startTime;
        chart.judgeLines.get(1).notes.add(note);
        LineEvent event = new LineEvent(); event.type = EventType.ROTATE;
        chart.judgeLines.get(1).eventLayers.get(2).events(event.type).add(event);
        EditorView first = new EditorView(RuntimeEnvironment.getApplication()); first.setChart(chart);
        first.lineIndex=1; first.eventLayerIndex=2; first.currentBeat=421.747;
        first.pixelsPerBeat=167.5f*first.density; first.subdivision=12; first.verticalGridLines=33;
        first.workbenchTab=2; first.bottomPanel=EditorChromeView.BottomPanel.EDIT;
        first.selectedNote=note; first.selectedNotes.add(note); first.selectedEvent=event; first.selectedEvents.add(event);
        first.tool=EditorSurfaceView.Tool.FLICK; first.noteMoveMode=NoteMoveMode.FREE;
        first.clipboard=ChartClipboard.copy(Arrays.asList(note),Arrays.asList(event));
        first.playing=true;
        String original=chart.toJsonString(); JSONObject saved=first.saveEditorSession();
        assertEquals(original,chart.toJsonString());
        EditorView second = new EditorView(RuntimeEnvironment.getApplication()); second.setChart(ChartDocument.fromJson(original));
        String beforeRestore=second.getChart().toJsonString();
        second.restoreEditorSession(new JSONObject(saved.toString()));
        assertEquals(421.747,second.getCurrentBeat(),0); assertEquals(1,second.getLineIndex());
        assertEquals(2,second.getEventLayerIndex()); assertEquals(12,second.getSubdivision());
        assertEquals(167.5f,second.timelineScale()/second.density,.001f); assertEquals(33,second.verticalGridLines);
        assertEquals(2,second.getWorkbenchTab()); assertEquals(EditorChromeView.BottomPanel.EDIT,second.bottomPanel);
        assertEquals(EditorSurfaceView.Tool.FLICK,second.tool); assertEquals(NoteMoveMode.FREE,second.noteMoveMode);
        assertSame(second.currentLine().notes.get(0),second.selectedNote); assertEquals(1,second.selectedNotes.size());
        assertSame(second.currentLayer().events(EventType.ROTATE).get(0),second.selectedEvent);
        assertEquals(1,second.clipboard.noteCount()); assertEquals(1,second.clipboard.eventCount());
        assertFalse(second.isPlaying()); assertEquals(beforeRestore,second.getChart().toJsonString());
    }

    @Test public void staleSelectionAndOutOfRangeViewportAreClamped() throws Exception {
        EditorView view = new EditorView(RuntimeEnvironment.getApplication()); view.setChart(ChartDocument.fromJson("{}"));
        view.restoreEditorSession(new JSONObject("{\"beat\":-50,\"line\":999,\"layer\":999,\"zoom\":99999,\"note\":900,\"notes\":[900],\"tool\":\"unknown\"}"));
        assertEquals(0,view.getCurrentBeat(),0); assertEquals(0,view.getLineIndex());
        assertNull(view.selectedNote); assertTrue(view.selectedNotes.isEmpty());
        assertEquals(EditorSurfaceView.Tool.SELECT,view.tool); assertEquals(280f,view.timelineScale()/view.density,.001f);
    }
}
