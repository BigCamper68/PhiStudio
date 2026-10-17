package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class NoteTouchOperationTest {
    @Test
    public void movesNoteGroupInTimeAndXAsOneCommand() {
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        Note first = note(NoteType.TAP, 1, 1, -100.0);
        Note hold = note(NoteType.HOLD, 2, 4, 100.0);
        line.notes.add(first);
        line.notes.add(hold);

        NoteTouchOperation.Result result = NoteTouchOperation.move(line,
                Arrays.asList(first, hold), new BeatTime(0, 1, 2), 50.0);
        assertEquals(NoteTouchOperation.Error.NONE, result.error);
        EditHistory history = new EditHistory(4);
        history.execute(result.command);
        assertEquals(new BeatTime(1, 1, 2), first.startTime);
        assertEquals(new BeatTime(4, 1, 2), hold.endTime);
        assertEquals(150.0, hold.positionX, 0.0);
        history.undo();
        assertEquals(new BeatTime(1, 0, 1), first.startTime);
        assertEquals(new BeatTime(4, 0, 1), hold.endTime);
        history.redo();
        assertEquals(150.0, hold.positionX, 0.0);
    }

    @Test
    public void rejectsWholeMoveWhenAnyNoteLeavesBounds() {
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        Note first = note(NoteType.TAP, 1, 1, 650.0);
        Note second = note(NoteType.TAP, 2, 2, 0.0);
        line.notes.add(first);
        line.notes.add(second);

        NoteTouchOperation.Result result = NoteTouchOperation.move(line,
                Arrays.asList(first, second), BeatTime.zero(), 50.0);
        assertEquals(NoteTouchOperation.Error.X_OUT_OF_RANGE, result.error);
        assertEquals(650.0, first.positionX, 0.0);
        assertEquals(0.0, second.positionX, 0.0);
    }

    @Test
    public void resizesHoldStartAndEndReversibly() {
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        Note hold = note(NoteType.HOLD, 1, 3, 0.0);
        line.notes.add(hold);
        NoteTouchOperation.Result result = NoteTouchOperation.resizeHold(line, hold,
                new BeatTime(1, 1, 2), new BeatTime(4, 0, 1));
        assertEquals(NoteTouchOperation.Error.NONE, result.error);
        result.command.apply();
        assertEquals(new BeatTime(1, 1, 2), hold.startTime);
        assertEquals(new BeatTime(4, 0, 1), hold.endTime);
        result.command.revert();
        assertEquals(new BeatTime(1, 0, 1), hold.startTime);
        assertEquals(new BeatTime(3, 0, 1), hold.endTime);
    }

    private static Note note(NoteType type, int start, int end, double x) {
        Note note = new Note();
        note.type = type;
        note.startTime = new BeatTime(start, 0, 1);
        note.endTime = new BeatTime(end, 0, 1);
        note.positionX = x;
        return note;
    }
}
