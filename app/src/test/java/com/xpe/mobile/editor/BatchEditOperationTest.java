package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public final class BatchEditOperationTest {
    @Test
    public void editsNotesChronologicallyAsOneUndoableCommand() {
        JudgeLine line = emptyLine();
        Note later = note(2, 10.0);
        Note earlier = note(1, 10.0);
        line.notes.add(later);
        line.notes.add(earlier);
        BatchValueTransform.Spec profile = profile(0.0, 100.0);

        BatchEditOperation.Result result = BatchEditOperation.notes(line,
                Arrays.asList(later, earlier), BatchEditOperation.NoteField.X,
                profile, BatchValueTransform.Mode.BY);
        assertEquals(BatchEditOperation.Error.NONE, result.error);

        EditHistory history = new EditHistory(4);
        history.execute(result.command);
        assertEquals(10.0, earlier.positionX, 0.0);
        assertEquals(110.0, later.positionX, 0.0);
        history.undo();
        assertEquals(10.0, earlier.positionX, 0.0);
        assertEquals(10.0, later.positionX, 0.0);
        history.redo();
        assertEquals(110.0, later.positionX, 0.0);
    }

    @Test
    public void rejectsEntireNoteBatchWhenOneGeneratedValueIsInvalid() {
        JudgeLine line = emptyLine();
        Note note = note(1, 600.0);
        line.notes.add(note);
        BatchEditOperation.Result result = BatchEditOperation.notes(line,
                Arrays.asList(note), BatchEditOperation.NoteField.X,
                profile(100.0, 100.0), BatchValueTransform.Mode.BY);

        assertEquals(BatchEditOperation.Error.INVALID_RESULT, result.error);
        assertEquals(600.0, note.positionX, 0.0);
    }

    @Test
    public void editsAndSticksSameTypeEvents() {
        EventLayer layer = new EventLayer();
        LineEvent first = event(1, 2, 5.0, 10.0);
        LineEvent second = event(2, 3, 20.0, 30.0);
        layer.events(EventType.MOVE_X).add(first);
        layer.events(EventType.MOVE_X).add(second);

        BatchEditOperation.Result edit = BatchEditOperation.events(layer,
                Arrays.asList(second, first), BatchEditOperation.EventField.END_VALUE,
                profile(1.0, 2.0), BatchValueTransform.Mode.BY);
        assertEquals(BatchEditOperation.Error.NONE, edit.error);
        edit.command.apply();
        assertEquals(11.0, first.end, 0.0);
        assertEquals(32.0, second.end, 0.0);

        BatchEditOperation.Result stick = BatchEditOperation.stick(
                layer, Arrays.asList(second, first));
        assertEquals(BatchEditOperation.Error.NONE, stick.error);
        stick.command.apply();
        assertEquals(first.end, second.start, 0.0);
        stick.command.revert();
        assertEquals(20.0, second.start, 0.0);
    }

    private static JudgeLine emptyLine() {
        JudgeLine line = new JudgeLine();
        line.notes.clear();
        line.eventLayers.clear();
        line.eventLayers.add(new EventLayer());
        return line;
    }

    private static Note note(int beat, double x) {
        Note note = new Note();
        note.startTime = new BeatTime(beat, 0, 1);
        note.endTime = note.startTime;
        note.positionX = x;
        return note;
    }

    private static LineEvent event(int startBeat, int endBeat, double start, double end) {
        LineEvent event = new LineEvent();
        event.type = EventType.MOVE_X;
        event.startTime = new BeatTime(startBeat, 0, 1);
        event.endTime = new BeatTime(endBeat, 0, 1);
        event.start = start;
        event.end = end;
        return event;
    }

    private static BatchValueTransform.Spec profile(double lower, double upper) {
        BatchValueTransform.Spec spec = new BatchValueTransform.Spec();
        spec.lowerBound = lower;
        spec.upperBound = upper;
        spec.easingType = 1;
        spec.periodicSequence = new double[]{1.0};
        return spec;
    }
}
