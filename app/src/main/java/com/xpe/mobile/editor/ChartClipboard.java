package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Immutable clipboard snapshots and validated compound cut/paste operations. */
public final class ChartClipboard {
    public enum Error {
        NONE,
        EMPTY,
        INVALID_TARGET,
        RESERVED_LAYER,
        INVALID_OBJECT,
        EVENT_OVERLAP,
        XY_PAIR_REQUIRED
    }

    public static final class Snapshot {
        private final List<Note> notes;
        private final List<LineEvent> events;
        private final BeatTime earliestTime;

        private Snapshot(List<Note> notes, List<LineEvent> events, BeatTime earliestTime) {
            this.notes = notes;
            this.events = events;
            this.earliestTime = earliestTime;
        }

        public boolean isEmpty() {
            return notes.isEmpty() && events.isEmpty();
        }

        public int noteCount() {
            return notes.size();
        }

        public int eventCount() {
            return events.size();
        }
    }

    public static final class Operation {
        public final Error error;
        public final EditHistory.Command command;
        public final List<Note> notes;
        public final List<LineEvent> events;

        private Operation(Error error, EditHistory.Command command,
                          List<Note> notes, List<LineEvent> events) {
            this.error = error;
            this.command = command;
            this.notes = notes;
            this.events = events;
        }

        private static Operation error(Error error) {
            return new Operation(error, null, Collections.emptyList(), Collections.emptyList());
        }
    }

    private ChartClipboard() {
    }

    public static Snapshot copy(Collection<Note> sourceNotes,
                                Collection<LineEvent> sourceEvents) {
        List<Note> notes = new ArrayList<>();
        List<LineEvent> events = new ArrayList<>();
        BeatTime earliest = null;
        if (sourceNotes != null) {
            for (Note source : sourceNotes) {
                if (source == null || source.startTime == null) continue;
                notes.add(source.copy());
                earliest = earlier(earliest, source.startTime);
            }
        }
        if (sourceEvents != null) {
            for (LineEvent source : sourceEvents) {
                if (source == null || source.startTime == null) continue;
                events.add(source.copy());
                earliest = earlier(earliest, source.startTime);
            }
        }
        return new Snapshot(Collections.unmodifiableList(notes),
                Collections.unmodifiableList(events), earliest);
    }

    public static Operation preparePaste(Snapshot snapshot, JudgeLine targetLine,
                                         EventLayer targetLayer, int layerIndex,
                                         BeatTime anchor, boolean mirrorNotes,
                                         boolean xyBindingEnabled) {
        if (snapshot == null || snapshot.isEmpty()) return Operation.error(Error.EMPTY);
        if (targetLine == null || targetLayer == null || anchor == null
                || snapshot.earliestTime == null) {
            return Operation.error(Error.INVALID_TARGET);
        }
        if (!snapshot.events.isEmpty() && (layerIndex < 0 || layerIndex > 3)) {
            return Operation.error(Error.RESERVED_LAYER);
        }
        if (xyBindingEnabled && !hasCompleteXySelection(snapshot.events)) {
            return Operation.error(Error.XY_PAIR_REQUIRED);
        }

        List<Note> pastedNotes = new ArrayList<>();
        for (Note source : snapshot.notes) {
            Note note = source.copy();
            note.startTime = shift(source.startTime, snapshot.earliestTime, anchor);
            note.endTime = shift(source.endTime, snapshot.earliestTime, anchor);
            if (mirrorNotes) note.positionX = -note.positionX;
            if (PropertyValidator.validate(note) != PropertyValidator.Error.NONE) {
                return Operation.error(Error.INVALID_OBJECT);
            }
            pastedNotes.add(note);
        }

        List<LineEvent> pastedEvents = new ArrayList<>();
        for (LineEvent source : snapshot.events) {
            LineEvent event = source.copy();
            event.startTime = shift(source.startTime, snapshot.earliestTime, anchor);
            event.endTime = shift(source.endTime, snapshot.earliestTime, anchor);
            if (PropertyValidator.validate(event) != PropertyValidator.Error.NONE) {
                return Operation.error(Error.INVALID_OBJECT);
            }
            if (targetLayer.overlaps(event, null) || overlapsAny(pastedEvents, event)) {
                return Operation.error(Error.EVENT_OVERLAP);
            }
            pastedEvents.add(event);
        }

        EditHistory.Command command = new EditHistory.Command() {
            @Override
            public void apply() {
                for (Note note : pastedNotes) if (!targetLine.notes.contains(note)) targetLine.notes.add(note);
                for (LineEvent event : pastedEvents) {
                    List<LineEvent> values = targetLayer.events(event.type);
                    if (!values.contains(event)) values.add(event);
                }
                sort(targetLine, targetLayer);
            }

            @Override
            public void revert() {
                targetLine.notes.removeAll(pastedNotes);
                for (LineEvent event : pastedEvents) targetLayer.events(event.type).remove(event);
            }
        };
        return new Operation(Error.NONE, command,
                Collections.unmodifiableList(pastedNotes), Collections.unmodifiableList(pastedEvents));
    }

    public static Operation prepareCut(JudgeLine line, EventLayer layer,
                                       Collection<Note> selectedNotes,
                                       Collection<LineEvent> selectedEvents,
                                       boolean xyBindingEnabled) {
        if (line == null || layer == null) return Operation.error(Error.INVALID_TARGET);
        List<Note> notes = uniqueExistingNotes(line, selectedNotes);
        List<LineEvent> events = uniqueExistingEvents(layer, selectedEvents);
        if (notes.isEmpty() && events.isEmpty()) return Operation.error(Error.EMPTY);
        if (xyBindingEnabled && !hasCompleteXySelection(events)) {
            return Operation.error(Error.XY_PAIR_REQUIRED);
        }
        EditHistory.Command command = new EditHistory.Command() {
            @Override
            public void apply() {
                line.notes.removeAll(notes);
                for (LineEvent event : events) layer.events(event.type).remove(event);
            }

            @Override
            public void revert() {
                for (Note note : notes) if (!line.notes.contains(note)) line.notes.add(note);
                for (LineEvent event : events) {
                    List<LineEvent> values = layer.events(event.type);
                    if (!values.contains(event)) values.add(event);
                }
                sort(line, layer);
            }
        };
        return new Operation(Error.NONE, command,
                Collections.unmodifiableList(notes), Collections.unmodifiableList(events));
    }

    private static boolean hasCompleteXySelection(Collection<LineEvent> events) {
        EventLayer selected = new EventLayer();
        boolean hasMove = false;
        for (LineEvent event : events) {
            if (!XYBindingValidator.isMoveType(event.type)) continue;
            selected.events(event.type).add(event);
            hasMove = true;
        }
        selected.events(EventType.MOVE_X).sort((first, second) ->
                first.startTime.compareTo(second.startTime));
        selected.events(EventType.MOVE_Y).sort((first, second) ->
                first.startTime.compareTo(second.startTime));
        return !hasMove || XYBindingValidator.isLayerSynchronized(selected);
    }

    private static List<Note> uniqueExistingNotes(JudgeLine line, Collection<Note> selected) {
        List<Note> result = new ArrayList<>();
        if (selected == null) return result;
        for (Note note : selected) {
            if (line.notes.contains(note) && !result.contains(note)) result.add(note);
        }
        return result;
    }

    private static List<LineEvent> uniqueExistingEvents(EventLayer layer,
                                                        Collection<LineEvent> selected) {
        List<LineEvent> result = new ArrayList<>();
        if (selected == null) return result;
        for (LineEvent event : selected) {
            if (event != null && event.type != null && layer.events(event.type).contains(event)
                    && !result.contains(event)) result.add(event);
        }
        return result;
    }

    private static boolean overlapsAny(List<LineEvent> events, LineEvent candidate) {
        for (LineEvent event : events) {
            if (event.type != candidate.type) continue;
            if (candidate.startTime.compareTo(event.endTime) < 0
                    && candidate.endTime.compareTo(event.startTime) > 0) return true;
        }
        return false;
    }

    private static void sort(JudgeLine line, EventLayer layer) {
        line.sortNotes();
        for (EventType type : EventType.values()) {
            layer.events(type).sort((first, second) -> first.startTime.compareTo(second.startTime));
        }
    }

    private static BeatTime shift(BeatTime source, BeatTime earliest, BeatTime anchor) {
        return source.minus(earliest).plus(anchor);
    }

    private static BeatTime earlier(BeatTime current, BeatTime candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }
}
