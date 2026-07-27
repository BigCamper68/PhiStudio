package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Validated, deterministic and reversible advanced batch editing operations. */
public final class BatchEditOperation {
    public enum Error {
        NONE,
        EMPTY_SELECTION,
        TARGET_NOT_FOUND,
        MIXED_EVENT_TYPES,
        INVALID_PROFILE,
        INVALID_RESULT,
        UNSUPPORTED_FIELD
    }

    public enum NoteField {
        X,
        SPEED,
        SIZE,
        Y_OFFSET,
        VISIBLE_TIME
    }

    public enum EventField {
        START_VALUE,
        END_VALUE,
        EASING_TYPE
    }

    public static final class Result {
        public final Error error;
        public final EditHistory.Command command;
        public final List<Note> notes;
        public final List<LineEvent> events;

        private Result(Error error, EditHistory.Command command,
                       List<Note> notes, List<LineEvent> events) {
            this.error = error;
            this.command = command;
            this.notes = Collections.unmodifiableList(notes);
            this.events = Collections.unmodifiableList(events);
        }

        static Result error(Error error) {
            return new Result(error, null, Collections.emptyList(), Collections.emptyList());
        }
    }

    private BatchEditOperation() {
    }

    public static Result notes(JudgeLine line, List<Note> selection, NoteField field,
                               BatchValueTransform.Spec profile,
                               BatchValueTransform.Mode mode) {
        List<Note> targets = uniqueNotes(selection);
        if (targets.isEmpty()) return Result.error(Error.EMPTY_SELECTION);
        if (line == null || field == null || mode == null) return Result.error(Error.TARGET_NOT_FOUND);
        for (Note target : targets) {
            if (!line.notes.contains(target)) return Result.error(Error.TARGET_NOT_FOUND);
        }
        if (!BatchValueTransform.isValid(profile)) return Result.error(Error.INVALID_PROFILE);
        targets.sort(Comparator.comparing(note -> note.startTime));
        double[] values;
        try {
            values = BatchValueTransform.values(profile, targets.size());
        } catch (IllegalArgumentException exception) {
            return Result.error(Error.INVALID_PROFILE);
        }
        List<Note> before = copiesOfNotes(targets);
        List<Note> after = copiesOfNotes(targets);
        try {
            for (int index = 0; index < after.size(); index++) {
                Note edited = after.get(index);
                double generated = values[index];
                switch (field) {
                    case X:
                        edited.positionX = BatchValueTransform.apply(mode, edited.positionX, generated);
                        break;
                    case SPEED:
                        edited.speed = BatchValueTransform.apply(mode, edited.speed, generated);
                        break;
                    case SIZE:
                        edited.size = BatchValueTransform.apply(mode, edited.size, generated);
                        break;
                    case Y_OFFSET:
                        edited.yOffset = BatchValueTransform.apply(mode, edited.yOffset, generated);
                        break;
                    case VISIBLE_TIME:
                        edited.visibleTime = BatchValueTransform.apply(mode, edited.visibleTime, generated);
                        break;
                }
                if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
                    return Result.error(Error.INVALID_RESULT);
                }
            }
        } catch (IllegalArgumentException exception) {
            return Result.error(Error.INVALID_RESULT);
        }
        EditHistory.Command command = noteCommand(line, targets, before, after);
        return new Result(Error.NONE, command, targets, Collections.emptyList());
    }

    public static Result events(EventLayer layer, List<LineEvent> selection, EventField field,
                                BatchValueTransform.Spec profile,
                                BatchValueTransform.Mode mode) {
        List<LineEvent> targets = uniqueEvents(selection);
        if (targets.isEmpty()) return Result.error(Error.EMPTY_SELECTION);
        if (layer == null || field == null || mode == null) return Result.error(Error.TARGET_NOT_FOUND);
        EventType type = targets.get(0).type;
        for (LineEvent target : targets) {
            if (target.type != type) return Result.error(Error.MIXED_EVENT_TYPES);
            if (!layer.events(target.type).contains(target)) return Result.error(Error.TARGET_NOT_FOUND);
        }
        if (field == EventField.EASING_TYPE && type == EventType.SPEED) {
            return Result.error(Error.UNSUPPORTED_FIELD);
        }
        if (!BatchValueTransform.isValid(profile)) return Result.error(Error.INVALID_PROFILE);
        targets.sort(Comparator.comparing(event -> event.startTime));
        double[] values;
        try {
            values = BatchValueTransform.values(profile, targets.size());
        } catch (IllegalArgumentException exception) {
            return Result.error(Error.INVALID_PROFILE);
        }
        List<LineEvent> before = copiesOfEvents(targets);
        List<LineEvent> after = copiesOfEvents(targets);
        try {
            for (int index = 0; index < after.size(); index++) {
                LineEvent edited = after.get(index);
                double generated = values[index];
                switch (field) {
                    case START_VALUE:
                        edited.start = BatchValueTransform.apply(mode, edited.start, generated);
                        break;
                    case END_VALUE:
                        edited.end = BatchValueTransform.apply(mode, edited.end, generated);
                        break;
                    case EASING_TYPE:
                        edited.easingType = (int) Math.round(BatchValueTransform.apply(
                                mode, edited.easingType, generated));
                        break;
                }
                if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
                    return Result.error(Error.INVALID_RESULT);
                }
            }
        } catch (IllegalArgumentException exception) {
            return Result.error(Error.INVALID_RESULT);
        }
        EditHistory.Command command = eventCommand(layer, targets, before, after);
        return new Result(Error.NONE, command, Collections.emptyList(), targets);
    }

    /** Glues selected same-type events front-to-back without changing their times. */
    public static Result stick(EventLayer layer, List<LineEvent> selection) {
        List<LineEvent> targets = uniqueEvents(selection);
        if (targets.isEmpty()) return Result.error(Error.EMPTY_SELECTION);
        if (layer == null) return Result.error(Error.TARGET_NOT_FOUND);
        EventType type = targets.get(0).type;
        for (LineEvent target : targets) {
            if (target.type != type) return Result.error(Error.MIXED_EVENT_TYPES);
            if (!layer.events(type).contains(target)) return Result.error(Error.TARGET_NOT_FOUND);
        }
        targets.sort(Comparator.comparing(event -> event.startTime));
        List<LineEvent> before = copiesOfEvents(targets);
        List<LineEvent> after = copiesOfEvents(targets);
        for (int index = 1; index < after.size(); index++) {
            after.get(index).start = after.get(index - 1).end;
            if (PropertyValidator.validate(after.get(index)) != PropertyValidator.Error.NONE) {
                return Result.error(Error.INVALID_RESULT);
            }
        }
        EditHistory.Command command = eventCommand(layer, targets, before, after);
        return new Result(Error.NONE, command, Collections.emptyList(), targets);
    }

    private static EditHistory.Command noteCommand(JudgeLine line, List<Note> targets,
                                                   List<Note> before, List<Note> after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                for (int index = 0; index < targets.size(); index++) {
                    copyNote(after.get(index), targets.get(index));
                }
                line.sortNotes();
            }

            @Override
            public void revert() {
                for (int index = 0; index < targets.size(); index++) {
                    copyNote(before.get(index), targets.get(index));
                }
                line.sortNotes();
            }
        };
    }

    private static EditHistory.Command eventCommand(EventLayer layer, List<LineEvent> targets,
                                                     List<LineEvent> before,
                                                     List<LineEvent> after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                for (int index = 0; index < targets.size(); index++) {
                    copyEvent(after.get(index), targets.get(index));
                }
                sortEvents(layer, targets);
            }

            @Override
            public void revert() {
                for (int index = 0; index < targets.size(); index++) {
                    copyEvent(before.get(index), targets.get(index));
                }
                sortEvents(layer, targets);
            }
        };
    }

    private static void sortEvents(EventLayer layer, List<LineEvent> targets) {
        Set<EventType> types = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LineEvent target : targets) types.add(target.type);
        for (EventType type : types) {
            layer.events(type).sort(Comparator.comparing(event -> event.startTime));
        }
    }

    private static List<Note> uniqueNotes(List<Note> values) {
        List<Note> result = new ArrayList<>();
        Set<Note> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (values != null) {
            for (Note value : values) if (value != null && seen.add(value)) result.add(value);
        }
        return result;
    }

    private static List<LineEvent> uniqueEvents(List<LineEvent> values) {
        List<LineEvent> result = new ArrayList<>();
        Set<LineEvent> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (values != null) {
            for (LineEvent value : values) if (value != null && seen.add(value)) result.add(value);
        }
        return result;
    }

    private static List<Note> copiesOfNotes(List<Note> values) {
        List<Note> result = new ArrayList<>(values.size());
        for (Note value : values) result.add(value.copy());
        return result;
    }

    private static List<LineEvent> copiesOfEvents(List<LineEvent> values) {
        List<LineEvent> result = new ArrayList<>(values.size());
        for (LineEvent value : values) result.add(value.copy());
        return result;
    }

    static void copyNote(Note source, Note target) {
        target.above = source.above;
        target.alpha = source.alpha;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.fake = source.fake;
        target.positionX = source.positionX;
        target.size = source.size;
        target.speed = source.speed;
        target.type = source.type;
        target.visibleTime = source.visibleTime;
        target.yOffset = source.yOffset;
    }

    static void copyEvent(LineEvent source, LineEvent target) {
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.start = source.start;
        target.end = source.end;
        target.easingType = source.easingType;
        target.easingLeft = source.easingLeft;
        target.easingRight = source.easingRight;
        target.linkGroup = source.linkGroup;
        target.bezier = source.bezier;
        System.arraycopy(source.bezierPoints, 0, target.bezierPoints, 0,
                source.bezierPoints.length);
    }
}
