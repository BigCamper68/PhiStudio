package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Atomic validation and commands for touch-moving notes and resizing Hold boundaries. */
public final class NoteTouchOperation {
    public enum Error {
        NONE,
        EMPTY_SELECTION,
        TARGET_NOT_FOUND,
        NEGATIVE_START_TIME,
        END_TIME_NOT_AFTER_START,
        X_OUT_OF_RANGE,
        INVALID_RESULT,
        NOT_HOLD
    }

    public static final class Result {
        public final Error error;
        public final EditHistory.Command command;
        public final List<Note> notes;

        private Result(Error error, EditHistory.Command command, List<Note> notes) {
            this.error = error;
            this.command = command;
            this.notes = Collections.unmodifiableList(notes);
        }

        static Result error(Error error) {
            return new Result(error, null, Collections.emptyList());
        }
    }

    private NoteTouchOperation() {
    }

    public static Result move(JudgeLine line, List<Note> selection,
                              BeatTime beatDelta, double xDelta) {
        List<Note> targets = uniqueNotes(selection);
        if (targets.isEmpty()) return Result.error(Error.EMPTY_SELECTION);
        if (line == null || beatDelta == null || !Double.isFinite(xDelta)) {
            return Result.error(Error.INVALID_RESULT);
        }
        for (Note target : targets) {
            if (!line.notes.contains(target)) return Result.error(Error.TARGET_NOT_FOUND);
        }
        List<Note> before = copies(targets);
        List<Note> after = copies(targets);
        try {
            for (Note edited : after) {
                edited.startTime = edited.startTime.plus(beatDelta);
                edited.endTime = edited.endTime.plus(beatDelta);
                edited.positionX += xDelta;
                Error validation = validate(edited);
                if (validation != Error.NONE) return Result.error(validation);
            }
        } catch (ArithmeticException exception) {
            return Result.error(Error.INVALID_RESULT);
        }
        return new Result(Error.NONE, command(line, targets, before, after), targets);
    }

    public static Result resizeHold(JudgeLine line, Note target,
                                    BeatTime startTime, BeatTime endTime) {
        if (target == null || target.type != NoteType.HOLD) return Result.error(Error.NOT_HOLD);
        if (line == null || !line.notes.contains(target)) return Result.error(Error.TARGET_NOT_FOUND);
        Note before = target.copy();
        Note after = target.copy();
        after.startTime = startTime;
        after.endTime = endTime;
        Error validation = validate(after);
        if (validation != Error.NONE) return Result.error(validation);
        List<Note> targets = Collections.singletonList(target);
        return new Result(Error.NONE, command(line, targets,
                Collections.singletonList(before), Collections.singletonList(after)), targets);
    }

    private static Error validate(Note note) {
        if (note.startTime == null || note.endTime == null
                || !Double.isFinite(note.positionX)) return Error.INVALID_RESULT;
        if (note.startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (note.type == NoteType.HOLD && note.endTime.compareTo(note.startTime) <= 0) {
            return Error.END_TIME_NOT_AFTER_START;
        }
        if (note.positionX < -675.0 || note.positionX > 675.0) return Error.X_OUT_OF_RANGE;
        return PropertyValidator.validate(note) == PropertyValidator.Error.NONE
                ? Error.NONE : Error.INVALID_RESULT;
    }

    private static EditHistory.Command command(JudgeLine line, List<Note> targets,
                                               List<Note> before, List<Note> after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                for (int index = 0; index < targets.size(); index++) {
                    BatchEditOperation.copyNote(after.get(index), targets.get(index));
                }
                line.sortNotes();
            }

            @Override
            public void revert() {
                for (int index = 0; index < targets.size(); index++) {
                    BatchEditOperation.copyNote(before.get(index), targets.get(index));
                }
                line.sortNotes();
            }
        };
    }

    private static List<Note> uniqueNotes(List<Note> values) {
        List<Note> result = new ArrayList<>();
        Set<Note> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (values != null) {
            for (Note value : values) if (value != null && seen.add(value)) result.add(value);
        }
        return result;
    }

    private static List<Note> copies(List<Note> values) {
        List<Note> result = new ArrayList<>(values.size());
        for (Note value : values) result.add(value.copy());
        return result;
    }
}
