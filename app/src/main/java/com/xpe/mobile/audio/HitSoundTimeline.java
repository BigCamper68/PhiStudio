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
    private static final int NOTE_TYPE_COUNT = NoteType.values().length;

    private final List<Cue> cues;
    private final int noteCount;

    private HitSoundTimeline(List<Cue> cues, int noteCount) {
        this.cues = cues;
        this.noteCount = noteCount;
    }

    public static HitSoundTimeline empty() {
        return new HitSoundTimeline(Collections.emptyList(), 0);
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
        List<Cue> cues = groupSimultaneousEvents(events);
        return new HitSoundTimeline(Collections.unmodifiableList(cues), events.size());
    }

    /**
     * Returns one atomic cue for each crossed timestamp in
     * {@code (previousTimeMs, currentTimeMs]}.
     *
     * <p>A cue retains the number of notes of every type. Consumers must submit the whole cue as
     * one audio operation: sequential native calls can start nominally simultaneous samples on
     * different mixer frames.
     */
    public List<Cue> cuesBetween(long previousTimeMs, long currentTimeMs) {
        if (cues.isEmpty() || currentTimeMs <= previousTimeMs) {
            return Collections.emptyList();
        }
        int index = cueUpperBound(previousTimeMs);
        if (index >= cues.size() || cues.get(index).timeMs > currentTimeMs) {
            return Collections.emptyList();
        }
        List<Cue> result = new ArrayList<>();
        while (index < cues.size() && cues.get(index).timeMs <= currentTimeMs) {
            result.add(cues.get(index));
            index++;
        }
        return result;
    }

    int size() {
        return noteCount;
    }

    int cueCount() {
        return cues.size();
    }

    private int cueUpperBound(long timeMs) {
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).timeMs <= timeMs) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static List<Cue> groupSimultaneousEvents(List<Event> events) {
        if (events.isEmpty()) return Collections.emptyList();
        List<Cue> result = new ArrayList<>();
        long timeMs = events.get(0).timeMs;
        int[] counts = new int[NOTE_TYPE_COUNT];
        for (Event event : events) {
            if (event.timeMs != timeMs) {
                result.add(new Cue(timeMs, counts));
                timeMs = event.timeMs;
                counts = new int[NOTE_TYPE_COUNT];
            }
            counts[event.type.ordinal()]++;
        }
        result.add(new Cue(timeMs, counts));
        return result;
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

    /** All note hits that share one exact chart timestamp. */
    public static final class Cue {
        private final long timeMs;
        private final int[] counts;
        private final int noteCount;

        private Cue(long timeMs, int[] counts) {
            this.timeMs = timeMs;
            int total = 0;
            for (int count : counts) total += Math.max(0, count);
            this.counts = counts.clone();
            noteCount = total;
        }

        public long timeMs() {
            return timeMs;
        }

        public int count(NoteType type) {
            if (type == null) return 0;
            int ordinal = type.ordinal();
            return ordinal < counts.length ? counts[ordinal] : 0;
        }

        public int noteCount() {
            return noteCount;
        }

    }
}
