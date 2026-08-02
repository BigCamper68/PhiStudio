package com.xpe.mobile.audio;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

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
        HitSoundTimeline.Cue drag = singleCue(timeline, 469L, 470L);
        assertEquals(1, drag.count(NoteType.DRAG));
        HitSoundTimeline.Cue tapAndFlick = singleCue(timeline, 470L, 550L);
        assertEquals(2, tapAndFlick.noteCount());
        assertEquals(1, tapAndFlick.count(NoteType.TAP));
        assertEquals(1, tapAndFlick.count(NoteType.FLICK));
        HitSoundTimeline.Cue hold = singleCue(timeline, 550L, 1050L);
        assertEquals(1, hold.count(NoteType.HOLD));
        assertEquals(Collections.emptyList(), timeline.cuesBetween(1050L, 1050L));
    }

    @Test
    public void groupsOneTimestampAndRetainsEveryTypeCount() {
        ChartDocument chart = chart();
        JudgeLine first = new JudgeLine();
        first.notes.add(note(NoteType.TAP, 1.0, false));
        first.notes.add(note(NoteType.TAP, 1.0, false));
        first.notes.add(note(NoteType.FLICK, 1.0, false));
        first.notes.add(note(NoteType.HOLD, 1.0, false));
        chart.judgeLines.add(first);
        JudgeLine second = new JudgeLine();
        second.notes.add(note(NoteType.TAP, 1.0, false));
        chart.judgeLines.add(second);

        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);

        assertEquals(5, timeline.size());
        assertEquals(1, timeline.cueCount());
        List<HitSoundTimeline.Cue> cues = timeline.cuesBetween(499L, 500L);
        assertEquals(1, cues.size());
        HitSoundTimeline.Cue cue = cues.get(0);
        assertEquals(500L, cue.timeMs());
        assertEquals(5, cue.noteCount());
        assertEquals(3, cue.count(NoteType.TAP));
        assertEquals(1, cue.count(NoteType.HOLD));
        assertEquals(1, cue.count(NoteType.FLICK));
        assertEquals(0, cue.count(NoteType.DRAG));
    }

    @Test
    public void backwardSeekDoesNotEmitAHitSoundBurst() {
        ChartDocument chart = chart();
        JudgeLine line = new JudgeLine();
        line.notes.add(note(NoteType.TAP, 1.0, false));
        chart.judgeLines.add(line);

        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);

        assertEquals(Collections.emptyList(), timeline.cuesBetween(900L, 400L));
    }

    private static HitSoundTimeline.Cue singleCue(HitSoundTimeline timeline,
                                                   long previousTimeMs,
                                                   long currentTimeMs) {
        List<HitSoundTimeline.Cue> cues = timeline.cuesBetween(
                previousTimeMs, currentTimeMs);
        assertEquals(1, cues.size());
        return cues.get(0);
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
