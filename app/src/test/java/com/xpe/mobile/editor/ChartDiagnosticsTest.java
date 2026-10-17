package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ChartDiagnosticsTest {
    @Test
    public void reportsErrorsWarningsAndCautionsWithoutChangingChart() throws Exception {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange();
        bpm.bpm = -1.0;
        chart.bpmChanges.add(bpm);

        JudgeLine line = new JudgeLine();
        line.notes.clear();
        line.eventLayers.clear();
        EventLayer layer = new EventLayer();
        line.eventLayers.add(layer);
        chart.judgeLines.add(line);

        Note hold = new Note();
        hold.type = NoteType.HOLD;
        hold.startTime = beat(2.0);
        hold.endTime = beat(1.0);
        hold.positionX = 700.0;
        hold.fake = true;
        hold.size = 1.5;
        hold.visibleTime = 2.0;
        line.notes.add(hold);

        LineEvent first = event(EventType.ALPHA, 0.0, 2.0);
        first.start = -1.0;
        first.end = 300.0;
        LineEvent second = event(EventType.ALPHA, 1.0, 3.0);
        layer.events(EventType.ALPHA).add(first);
        layer.events(EventType.ALPHA).add(second);

        String before = chart.toJsonString();
        ChartDiagnostics.Report report = ChartDiagnostics.analyze(chart, 2.5);
        String after = chart.toJsonString();

        assertEquals(before, after);
        assertTrue(report.errorCount >= 4);
        assertTrue(report.warningCount >= 1);
        assertTrue(report.cautionCount >= 4);
        EnumSet<ChartDiagnostic.Code> codes = EnumSet.noneOf(ChartDiagnostic.Code.class);
        for (ChartDiagnostic item : report.items) codes.add(item.code);
        assertTrue(codes.contains(ChartDiagnostic.Code.INVALID_BPM));
        assertTrue(codes.contains(ChartDiagnostic.Code.HOLD_INTERVAL_INVALID));
        assertTrue(codes.contains(ChartDiagnostic.Code.EVENT_OVERLAP));
        assertTrue(codes.contains(ChartDiagnostic.Code.ALPHA_OUT_OF_RANGE));
        assertTrue(codes.contains(ChartDiagnostic.Code.NOTE_X_TOO_LARGE));
    }

    @Test
    public void allowsTouchingEventBoundaries() {
        ChartDocument chart = new ChartDocument();
        chart.bpmChanges.add(new BpmChange());
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        line.eventLayers.clear();
        EventLayer layer = new EventLayer();
        line.eventLayers.add(layer);
        chart.judgeLines.add(line);
        layer.events(EventType.MOVE_X).add(event(EventType.MOVE_X, 0.0, 1.0));
        layer.events(EventType.MOVE_X).add(event(EventType.MOVE_X, 1.0, 2.0));

        ChartDiagnostics.Report report = ChartDiagnostics.analyze(chart, null);

        for (ChartDiagnostic item : report.items) {
            assertTrue(item.code != ChartDiagnostic.Code.EVENT_OVERLAP);
        }
    }

    @Test
    public void capsDisplayedItemsButCountsAllFindings() {
        ChartDocument chart = new ChartDocument();
        chart.bpmChanges.add(new BpmChange());
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        chart.judgeLines.add(line);
        for (int index = 0; index < 250; index++) {
            Note note = new Note();
            note.startTime = beat(index);
            note.endTime = note.startTime;
            note.fake = true;
            line.notes.add(note);
        }

        ChartDiagnostics.Report report = ChartDiagnostics.analyze(chart, null);

        assertEquals(250, report.cautionCount);
        assertEquals(250, report.totalCount);
        assertEquals(100, report.items.size());
        assertTrue(report.isTruncated());
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
