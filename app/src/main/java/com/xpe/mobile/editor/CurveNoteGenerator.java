package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.Easing;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Curve Notes generator: selected endpoints remain and intermediate notes are added. */
public final class CurveNoteGenerator {
    public static final int MAX_INTERVALS = 4096;

    public enum Error {
        NONE,
        TARGET_NOT_FOUND,
        SAME_NOTE,
        INVALID_TIME_ORDER,
        INVALID_DENSITY,
        INVALID_SUBDIVISION,
        INVALID_NOTE_TYPE,
        INVALID_EASING,
        TOO_MANY_NOTES,
        NO_INTERMEDIATE_NOTES,
        X_OUT_OF_RANGE
    }

    public static final class Point {
        public final double progress;
        public final double x;

        Point(double progress, double x) {
            this.progress = progress;
            this.x = x;
        }
    }

    public static final class Result {
        public final Error error;
        public final List<Note> notes;
        public final List<Point> path;
        public final int intervalCount;

        private Result(Error error, List<Note> notes, List<Point> path, int intervalCount) {
            this.error = error;
            this.notes = Collections.unmodifiableList(notes);
            this.path = Collections.unmodifiableList(path);
            this.intervalCount = intervalCount;
        }

        static Result error(Error error) {
            return new Result(error, Collections.emptyList(), Collections.emptyList(), 0);
        }
    }

    private CurveNoteGenerator() {
    }

    public static Result generate(JudgeLine line, Note startNote, Note endNote,
                                  double density, int subdivision,
                                  NoteType noteType, int easingType) {
        if (line == null || startNote == null || endNote == null
                || !line.notes.contains(startNote) || !line.notes.contains(endNote)) {
            return Result.error(Error.TARGET_NOT_FOUND);
        }
        if (startNote == endNote) return Result.error(Error.SAME_NOTE);
        if (startNote.startTime == null || endNote.startTime == null
                || startNote.startTime.compareTo(endNote.startTime) >= 0) {
            return Result.error(Error.INVALID_TIME_ORDER);
        }
        if (!Double.isFinite(density) || density <= 0.0) {
            return Result.error(Error.INVALID_DENSITY);
        }
        if (subdivision <= 0) return Result.error(Error.INVALID_SUBDIVISION);
        if (noteType != NoteType.TAP && noteType != NoteType.DRAG
                && noteType != NoteType.FLICK) {
            return Result.error(Error.INVALID_NOTE_TYPE);
        }
        if (easingType < Easing.MIN_TYPE || easingType > Easing.MAX_TYPE) {
            return Result.error(Error.INVALID_EASING);
        }

        double duration = endNote.startTime.toDouble() - startNote.startTime.toDouble();
        double requestedIntervals = duration * subdivision * density;
        if (!Double.isFinite(requestedIntervals) || requestedIntervals > MAX_INTERVALS) {
            return Result.error(Error.TOO_MANY_NOTES);
        }
        int intervals = Math.max(1, (int) Math.ceil(requestedIntervals - 1.0e-12));
        if (intervals < 2) return Result.error(Error.NO_INTERMEDIATE_NOTES);

        List<Note> notes = new ArrayList<>(intervals - 1);
        List<Point> path = new ArrayList<>(intervals + 1);
        for (int index = 0; index <= intervals; index++) {
            double progress = index / (double) intervals;
            double eased = Easing.apply(easingType, progress);
            double x = startNote.positionX
                    + (endNote.positionX - startNote.positionX) * eased;
            if (!Double.isFinite(x) || x < -675.0 || x > 675.0) {
                return Result.error(Error.X_OUT_OF_RANGE);
            }
            path.add(new Point(progress, x));
            if (index == 0 || index == intervals) continue;
            Note note = new Note();
            note.type = noteType;
            try {
                note.startTime = BeatTime.interpolate(startNote.startTime,
                        endNote.startTime, index, intervals);
            } catch (ArithmeticException | IllegalArgumentException exception) {
                return Result.error(Error.INVALID_TIME_ORDER);
            }
            note.endTime = note.startTime;
            note.positionX = x;
            note.above = startNote.above;
            notes.add(note);
        }
        return new Result(Error.NONE, notes, path, intervals);
    }
}
