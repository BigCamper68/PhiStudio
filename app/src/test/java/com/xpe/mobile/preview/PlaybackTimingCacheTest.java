package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackTimingCacheTest {
    private static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange first = new BpmChange(); first.bpm = 120; chart.bpmChanges.add(first);
        BpmChange second = new BpmChange(); second.bpm = 60; second.startTime = new BeatTime(2, 0, 1);
        chart.bpmChanges.add(second);
        chart.judgeLines.add(new JudgeLine());
        return chart;
    }
    private static LineEvent event(ChartDocument chart, int start, int end) {
        LineEvent event = new LineEvent(); event.type = EventType.MOVE_X;
        event.startTime = new BeatTime(start, 0, 1); event.endTime = new BeatTime(end, 0, 1);
        event.start = 10; event.end = 210; event.easingType = 4;
        var events = chart.judgeLines.get(0).layer(0).events(EventType.MOVE_X);
        events.clear(); events.add(event); chart.markEdited(); return event;
    }
    @Test public void cachedTimesMatchUncachedEvaluationAcrossBpmChangesAndBackwardSeeks() {
        ChartDocument chart = chart(); LineEvent event = event(chart, 0, 4);
        var prepared = PreviewChartIndex.prepared(chart, false);
        for (double beat : new double[]{0, .5, 1, 2, 3, 4, 8, 1.5, 0, 3.25}) {
            assertEquals(PrprEvents.value(event, prepared.time, prepared.time.seconds(beat)),
                    prepared.lines[0].valueAt(EventType.MOVE_X, beat), 0);
        }
    }
    @Test public void cacheReadsLiveScalarAndEasingEditsAndReplacedImmutableEndpoints() {
        ChartDocument chart = chart(); LineEvent event = event(chart, 0, 4);
        var prepared = PreviewChartIndex.prepared(chart, false);
        prepared.lines[0].valueAt(EventType.MOVE_X, 2);
        event.start = -300; event.end = 70; event.easingType = 16;
        event.startTime = new BeatTime(1, 0, 1); event.endTime = new BeatTime(6, 0, 1);
        for (double beat : new double[]{1, 2, 3, 4, 6, 0}) {
            assertEquals(PrprEvents.value(event, prepared.time, prepared.time.seconds(beat)),
                    prepared.lines[0].valueAt(EventType.MOVE_X, beat), 0);
        }
    }
    @Test public void switchingEventsAndOverlappingActivationRetainSourceSemantics() {
        ChartDocument chart = chart(); LineEvent first = event(chart, 0, 4);
        LineEvent second = new LineEvent(); second.type = EventType.MOVE_X;
        second.startTime = new BeatTime(2, 0, 1); second.endTime = new BeatTime(6, 0, 1);
        second.start = -400; second.end = -50;
        chart.judgeLines.get(0).layer(0).events(EventType.MOVE_X).add(second);
        var prepared = PreviewChartIndex.prepared(chart, false);
        for (double beat : new double[]{1, 3.9, 4, 5, 6, 2, 5}) {
            LineEvent selected = beat < 4 ? first : second;
            assertEquals(PrprEvents.value(selected, prepared.time, prepared.time.seconds(beat)),
                    prepared.lines[0].valueAt(EventType.MOVE_X, beat), 0);
        }
    }
    @Test public void changedBpmRebuildsTimingForEventsAndNoteVisibility() {
        ChartDocument chart = chart(); event(chart, 0, 4);
        Note note = new Note(); note.type = NoteType.TAP; note.startTime = new BeatTime(4, 0, 1);
        note.endTime = note.startTime; chart.judgeLines.get(0).notes.add(note);
        var before = PreviewChartIndex.prepared(chart, false);
        assertEquals(3, before.lines[0].notes.get(0).startSeconds, 0);
        chart.bpmChanges.get(1).bpm = 120;
        var after = PreviewChartIndex.prepared(chart, false);
        assertNotSame(before, after);
        assertEquals(2, after.lines[0].notes.get(0).startSeconds, 0);
    }
}
