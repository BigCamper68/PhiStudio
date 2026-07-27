package com.xpe.mobile.audio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable, time-sorted hit-sound schedule for one playback run. */
public final class HitSoundTimeline {
    private final List<Event> events;

    private HitSoundTimeline(List<Event> events) {
        this.events = events;
    }

    public static HitSoundTimeline empty() {
        return new HitSoundTimeline(Collections.emptyList());
    }

    public static HitSoundTimeline build(ChartDocument chart,
                                         int tapFlickOffsetMs,
                                         int dragOffsetMs) {
        if (chart == null) return empty();
        List<Event> events = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < chart.judgeLines.size(); lineIndex++) {
            JudgeLine line = chart.judgeLines.get(lineIndex);
            for (int noteIndex = 0; noteIndex < line.notes.size(); noteIndex++) {
                Note note = line.notes.get(noteIndex);
                if (note == null || note.fake || note.type == null
                        || note.startTime == null) continue;
                int offsetMs = note.type == NoteType.DRAG
                        ? dragOffsetMs : tapFlickOffsetMs;
                long timeMs = saturatingAdd(
                        chart.beatToMillis(Math.max(0.0, note.startTime.toDouble())),
                        offsetMs);
                events.add(new Event(timeMs, lineIndex, noteIndex, note.type));
            }
        }
        events.sort(Comparator.comparingLong((Event event) -> event.timeMs)
                .thenComparingInt(event -> event.lineIndex)
                .thenComparingInt(event -> event.noteIndex));
        return new HitSoundTimeline(Collections.unmodifiableList(events));
    }

    /** Returns every note crossed in {@code (previousTimeMs, currentTimeMs]}. */
    public List<NoteType> between(long previousTimeMs, long currentTimeMs) {
        if (events.isEmpty() || currentTimeMs <= previousTimeMs) {
            return Collections.emptyList();
        }
        int index = upperBound(previousTimeMs);
        if (index >= events.size() || events.get(index).timeMs > currentTimeMs) {
            return Collections.emptyList();
        }
        List<NoteType> result = new ArrayList<>();
        while (index < events.size() && events.get(index).timeMs <= currentTimeMs) {
            result.add(events.get(index).type);
            index++;
        }
        return result;
    }

    int size() {
        return events.size();
    }

    private int upperBound(long timeMs) {
        int low = 0;
        int high = events.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (events.get(middle).timeMs <= timeMs) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static long saturatingAdd(long value, int offset) {
        if (offset > 0 && value > Long.MAX_VALUE - offset) return Long.MAX_VALUE;
        if (offset < 0 && value < Long.MIN_VALUE - offset) return Long.MIN_VALUE;
        return value + offset;
    }

    private static final class Event {
        final long timeMs;
        final int lineIndex;
        final int noteIndex;
        final NoteType type;

        Event(long timeMs, int lineIndex, int noteIndex, NoteType type) {
            this.timeMs = timeMs;
            this.lineIndex = lineIndex;
            this.noteIndex = noteIndex;
            this.type = type;
        }
    }
}
