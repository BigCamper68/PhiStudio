package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class NoteMultiHintResolver {
    private NoteMultiHintResolver() {
    }

    static Set<Note> resolve(ChartDocument chart) {
        if (chart == null || chart.totalNotes() < 2) return Collections.emptySet();

        Map<Key, Note> firstByPosition = new HashMap<>();
        Set<Note> highlighted = Collections.newSetFromMap(new IdentityHashMap<>());
        for (JudgeLine line : chart.judgeLines) {
            collect(line.notes, firstByPosition, highlighted);
        }
        return highlighted;
    }

    static Set<Note> resolve(List<Note> notes) {
        if (notes == null || notes.size() < 2) return Collections.emptySet();
        Map<Key, Note> firstByPosition = new HashMap<>();
        Set<Note> highlighted = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(notes, firstByPosition, highlighted);
        return highlighted;
    }

    private static void collect(List<Note> notes, Map<Key, Note> firstByPosition,
                                Set<Note> highlighted) {
        for (Note note : notes) {
            if (note == null) continue;
            Key key = new Key(note.type, note.startTime);
            Note first = firstByPosition.putIfAbsent(key, note);
            if (first != null) {
                highlighted.add(first);
                highlighted.add(note);
            }
        }
    }

    private static final class Key {
        private final NoteType type;
        private final BeatTime startTime;

        private Key(NoteType type, BeatTime startTime) {
            this.type = type;
            this.startTime = startTime;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Key)) return false;
            Key other = (Key) object;
            return type == other.type && Objects.equals(startTime, other.startTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, startTime);
        }
    }
}
