package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class CurveNoteGeneratorTest {
    @Test
    public void fillsEachActiveGridStepAndKeepsEndpoints() {
        JudgeLine line = new JudgeLine();
        Note start = note(1, -400.0);
        Note end = note(3, 400.0);
        start.above = 0;
        line.notes.add(start);
        line.notes.add(end);

        CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                line, start, end, 1.0, 4, NoteType.DRAG, 1);

        assertEquals(CurveNoteGenerator.Error.NONE, result.error);
        assertEquals(8, result.intervalCount);
        assertEquals(7, result.notes.size());
        assertEquals(new BeatTime(2, 0, 1), result.notes.get(3).startTime);
        assertEquals(0.0, result.notes.get(3).positionX, 1.0e-9);
        assertEquals(NoteType.DRAG, result.notes.get(3).type);
        assertEquals(0, result.notes.get(3).above);
        assertSame(start, line.notes.get(0));
        assertSame(end, line.notes.get(1));
    }

    @Test
    public void easingShapesPositionWithoutChangingExactNoteTimes() {
        JudgeLine line = new JudgeLine();
        Note start = note(0, 0.0);
        Note end = note(2, 400.0);
        line.notes.add(start);
        line.notes.add(end);

        CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                line, start, end, 1.0, 2, NoteType.FLICK, 5);

        assertEquals(CurveNoteGenerator.Error.NONE, result.error);
        assertEquals(new BeatTime(1, 0, 1), result.notes.get(1).startTime);
        assertEquals(100.0, result.notes.get(1).positionX, 1.0e-9);
    }

    @Test
    public void batchCommandIsOneUndoableOperation() {
        JudgeLine line = new JudgeLine();
        Note start = note(0, -100.0);
        Note end = note(1, 100.0);
        line.notes.add(start);
        line.notes.add(end);
        CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                line, start, end, 1.0, 4, NoteType.TAP, 1);
        EditHistory history = new EditHistory(10);

        history.execute(CurveNoteCommand.add(line, result.notes));
        assertEquals(5, line.notes.size());
        history.undo();
        assertEquals(2, line.notes.size());
        assertTrue(line.notes.contains(start));
        assertTrue(line.notes.contains(end));
        history.redo();
        assertEquals(5, line.notes.size());
    }

    @Test
    public void rejectsHoldOutputAndReversedEndpoints() {
        JudgeLine line = new JudgeLine();
        Note start = note(1, 0.0);
        Note end = note(2, 100.0);
        line.notes.add(start);
        line.notes.add(end);
        assertEquals(CurveNoteGenerator.Error.INVALID_NOTE_TYPE,
                CurveNoteGenerator.generate(line, start, end,
                        1.0, 4, NoteType.HOLD, 1).error);
        assertEquals(CurveNoteGenerator.Error.INVALID_TIME_ORDER,
                CurveNoteGenerator.generate(line, end, start,
                        1.0, 4, NoteType.DRAG, 1).error);
    }

    private static Note note(int beat, double x) {
        Note note = new Note();
        note.startTime = new BeatTime(beat, 0, 1);
        note.endTime = note.startTime;
        note.positionX = x;
        return note;
    }
}
