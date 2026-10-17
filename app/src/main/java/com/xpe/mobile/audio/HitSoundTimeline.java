package com.xpe.mobile.audio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.HitSoundReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;

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
                events.add(new Event(timeMs, lineIndex, noteIndex,
                        HitSoundReference.soundType(note.type, note.hitSound),
                        HitSoundReference.isCustom(note.hitSound) ? note.hitSound : null));
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

    List<Cue> allCues() {
        return cues;
    }

    public Set<String> customPaths() {
        Set<String> paths = new TreeSet<>();
        for (Cue cue : cues) for (CustomSound sound : cue.customSounds()) paths.add(sound.path());
        return Collections.unmodifiableSet(paths);
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
        Map<String,int[]> custom = Collections.emptyMap();
        for (Event event : events) {
            if (event.timeMs != timeMs) {
                result.add(new Cue(timeMs, counts, custom));
                timeMs = event.timeMs;
                counts = new int[NOTE_TYPE_COUNT];
                custom = Collections.emptyMap();
            }
            if (event.customPath == null) counts[event.type.ordinal()]++;
            else {
                if (custom.isEmpty()) custom = new LinkedHashMap<>();
                custom.computeIfAbsent(event.customPath, ignored -> new int[NOTE_TYPE_COUNT])
                        [event.type.ordinal()]++;
            }
        }
        result.add(new Cue(timeMs, counts, custom));
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
        final String customPath;

        Event(long timeMs, int lineIndex, int noteIndex, NoteType type, String customPath) {
            this.timeMs = timeMs;
            this.lineIndex = lineIndex;
            this.noteIndex = noteIndex;
            this.type = type;
            this.customPath = customPath;
        }
    }

    /** All note hits that share one exact chart timestamp. */
    public static final class Cue {
        private final long timeMs;
        private final int[] counts;
        private final int noteCount;
        private final List<CustomSound> customSounds;

        private Cue(long timeMs, int[] counts, Map<String,int[]> custom) {
            this.timeMs = timeMs;
            int total = 0;
            for (int count : counts) total += Math.max(0, count);
            List<CustomSound> sounds = new ArrayList<>(custom.size());
            for (Map.Entry<String,int[]> entry : custom.entrySet()) {
                CustomSound sound = new CustomSound(entry.getKey(), entry.getValue());
                sounds.add(sound);
                total += sound.count();
            }
            customSounds = Collections.unmodifiableList(sounds);
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

        public List<CustomSound> customSounds() { return customSounds; }

    }

    /** A custom file can be used by multiple note types at the same timestamp. */
    public static final class CustomSound {
        private final String path;
        private final int[] fallbackCounts;
        private final int count;

        private CustomSound(String path, int[] counts) {
            this.path = path;
            fallbackCounts = counts.clone();
            int total = 0;
            for (int value : counts) total += value;
            count = total;
        }

        public String path() { return path; }
        public int count() { return count; }
        public int fallbackCount(NoteType type) { return fallbackCounts[type.ordinal()]; }
    }
}
