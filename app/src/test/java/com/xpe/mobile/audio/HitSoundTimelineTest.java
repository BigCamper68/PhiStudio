package com.xpe.mobile.audio;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class HitSoundTimelineTest {
    @Test
    public void appliesOffsetsOrdersCrossingsAndPreservesSimultaneousNotes() {
        ChartDocument chart = chart();
        JudgeLine first = new JudgeLine();
        first.notes.add(note(NoteType.TAP, 1.0, false));
        first.notes.add(note(NoteType.DRAG, 1.0, false));
        first.notes.add(note(NoteType.FLICK, 1.0, false));
        first.notes.add(note(NoteType.HOLD, 2.0, false));
        first.notes.add(note(NoteType.TAP, 1.0, true));
        chart.judgeLines.add(first);

        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 50, -30);

        assertEquals(4, timeline.size());
        assertEquals(Collections.singletonList(NoteType.DRAG), timeline.between(469L, 470L));
        assertEquals(Arrays.asList(NoteType.TAP, NoteType.FLICK),
                timeline.between(470L, 550L));
        assertEquals(Collections.singletonList(NoteType.HOLD),
                timeline.between(550L, 1050L));
        assertEquals(Collections.emptyList(), timeline.between(1050L, 1050L));
    }

    @Test
    public void backwardSeekDoesNotEmitAHitSoundBurst() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.notes.add(note(NoteType.TAP, 1.0, false));
        chart.judgeLines.add(line);

        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);

        assertEquals(Collections.emptyList(), timeline.between(900L, 400L));
    }

    private static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange();
        bpm.bpm = 120.0;
        chart.bpmChanges.add(bpm);
        return chart;
    }

    private static Note note(NoteType type, double beat, boolean fake) {
        Note note = new Note();
        note.type = type;
        note.startTime = BeatTime.fromDouble(beat, 4);
        note.endTime = note.startTime;
        note.fake = fake;
        return note;
    }
}
