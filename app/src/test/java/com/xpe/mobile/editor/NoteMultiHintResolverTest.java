package com.xpe.mobile.editor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

public final class NoteMultiHintResolverTest {
    @Test
    public void highlightsSameTypeAtSameBeat() {
        Note first = note(NoteType.TAP, 3, 1, 2);
        Note second = note(NoteType.TAP, 3, 2, 4);

        Set<Note> highlighted = NoteMultiHintResolver.resolve(Arrays.asList(first, second));

        assertTrue(highlighted.contains(first));
        assertTrue(highlighted.contains(second));
    }


    @Test
    public void highlightsMatchingNotesAcrossJudgeLines() {
        Note first = note(NoteType.HOLD, 8, 0, 1);
        Note second = note(NoteType.HOLD, 8, 0, 1);
        ChartDocument chart = new ChartDocument();
        JudgeLine firstLine = new JudgeLine();
        JudgeLine secondLine = new JudgeLine();
        firstLine.notes.add(first);
        secondLine.notes.add(second);
        chart.judgeLines.add(firstLine);
        chart.judgeLines.add(secondLine);

        Set<Note> highlighted = NoteMultiHintResolver.resolve(chart);

        assertTrue(highlighted.contains(first));
        assertTrue(highlighted.contains(second));
    }

    @Test
    public void doesNotHighlightDifferentTypesAtSameBeat() {
        Note tap = note(NoteType.TAP, 4, 0, 1);
        Note flick = note(NoteType.FLICK, 4, 0, 1);

        Set<Note> highlighted = NoteMultiHintResolver.resolve(Arrays.asList(tap, flick));

        assertFalse(highlighted.contains(tap));
        assertFalse(highlighted.contains(flick));
    }

    @Test
    public void doesNotHighlightSameTypeAtDifferentBeats() {
        Note first = note(NoteType.DRAG, 1, 0, 1);
        Note second = note(NoteType.DRAG, 1, 1, 4);

        Set<Note> highlighted = NoteMultiHintResolver.resolve(Arrays.asList(first, second));

        assertFalse(highlighted.contains(first));
        assertFalse(highlighted.contains(second));
    }

    private static Note note(NoteType type, int whole, int numerator, int denominator) {
        Note note = new Note();
        note.type = type;
        note.startTime = new BeatTime(whole, numerator, denominator);
        return note;
    }
}
