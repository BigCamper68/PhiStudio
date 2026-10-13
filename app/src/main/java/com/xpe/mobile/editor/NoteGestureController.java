package com.xpe.mobile.editor;

import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;
import com.xpe.mobile.editor.EditorView.HoldHandleHit;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

/** NoteGestureController responsibilities extracted from EditorView. */
final class NoteGestureController {
    private final EditorView delegateOwner;

    NoteGestureController(EditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void addSimpleNoteAt(float x, float y, float right, float bottom) {
        Note note = new Note();
        note.startTime = BeatTime.fromDouble(Math.max(0.0, delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom)))), delegateOwner.subdivision);
        note.endTime = note.startTime;
        note.positionX = delegateOwner.snapChartX(EditorView.screenToChartX(x, 0f, right));
        switch (delegateOwner.tool) {
            case TAP: note.type = NoteType.TAP; break;
            case DRAG: note.type = NoteType.DRAG; break;
            case FLICK: note.type = NoteType.FLICK; break;
            default: return;
        }
        delegateOwner.noteGestureController.addNoteCommand(note);
    }

    void addHoldAt(float x, double startBeat, double endBeat, float right) {
        Note note = new Note();
        note.type = NoteType.HOLD;
        note.startTime = BeatTime.fromDouble(Math.max(0.0, startBeat), delegateOwner.subdivision);
        note.endTime = BeatTime.fromDouble(Math.max(startBeat + 1.0 / delegateOwner.subdivision, endBeat), delegateOwner.subdivision);
        note.positionX = delegateOwner.snapChartX(EditorView.screenToChartX(x, 0f, right));
        delegateOwner.noteGestureController.addNoteCommand(note);
    }

    void addNoteCommand(Note note) {
        JudgeLine line = delegateOwner.currentLine();
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                if (!line.notes.contains(note)) line.notes.add(note);
                line.sortNotes();
                delegateOwner.clearSelection();
                delegateOwner.selectedNote = note;
                delegateOwner.selectedNotes.add(note);
                delegateOwner.activateEditPanel();
            }

            @Override
            public void revert() {
                line.notes.remove(note);
                delegateOwner.selectedNotes.remove(note);
                if (delegateOwner.selectedNote == note) delegateOwner.selectedNote = null;
            }
        });
        delegateOwner.invalidate();
    }

    Note findNearestNote(float x, float y, float right, float bottom) {
        float centerY = delegateOwner.timelineY(bottom);
        Note nearest = null;
        float nearestDistance = 42f * delegateOwner.density;
        for (Note note : delegateOwner.currentLine().notes) {
            float noteX = EditorView.chartXToScreen(note.positionX, 0f, right);
            float startY = delegateOwner.beatToY(note.startTime.toDouble(), centerY);
            float noteY = startY;
            if (note.type == NoteType.HOLD) {
                float endY = delegateOwner.beatToY(note.endTime.toDouble(), centerY);
                noteY = EditorView.clamp(y, Math.min(startY, endY), Math.max(startY, endY));
            }
            float dx = noteX - x;
            float dy = noteY - y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = note;
            }
        }
        return nearest;
    }

    HoldHandleHit findHoldHandleAt(Note note, float x, float y,
                                           float right, float bottom) {
        if (note == null || note.type != NoteType.HOLD) return null;
        float centerY = delegateOwner.timelineY(bottom);
        float handleX = EditorView.chartXToScreen(note.positionX, 0f, right);
        float startY = delegateOwner.beatToY(note.startTime.toDouble(), centerY);
        float endY = delegateOwner.beatToY(note.endTime.toDouble(), centerY);
        float radius = 22f * delegateOwner.density;
        float startDistance = NoteGestureController.distance(x, y, handleX, startY);
        float endDistance = NoteGestureController.distance(x, y, handleX, endY);
        float best = Math.min(startDistance, endDistance);
        return best <= radius ? new HoldHandleHit(startDistance <= endDistance) : null;
    }

    void beginNoteDrag(Note primary, float x, float y,
                               float right, float bottom) {
        delegateOwner.noteGestureController.clearNoteTouchState();
        delegateOwner.draggedNotes.addAll(delegateOwner.selectedNotesForClipboard());
        if (delegateOwner.draggedNotes.isEmpty() && primary != null) delegateOwner.draggedNotes.add(primary);
        for (Note note : delegateOwner.draggedNotes) delegateOwner.noteDragBefore.add(note.copy());
        delegateOwner.noteDragAnchorBeat = BeatTime.fromDouble(
                delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom))), delegateOwner.subdivision);
        delegateOwner.noteDragAnchorX = EditorView.screenToChartX(x, 0f, right);
        delegateOwner.noteDragDeltaBeat = BeatTime.zero();
        delegateOwner.noteDragDeltaX = 0.0;
        delegateOwner.noteDragValidation = NoteTouchOperation.Error.NONE;
        delegateOwner.gestureMode = GestureMode.NOTE_DRAG;
    }

    void updateNoteDragCandidate(float x, float y, float right, float bottom) {
        if (delegateOwner.draggedNotes.isEmpty() || delegateOwner.noteDragAnchorBeat == null) return;
        BeatTime pointerBeat = BeatTime.fromDouble(
                delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom))), delegateOwner.subdivision);
        delegateOwner.noteDragDeltaBeat = delegateOwner.noteMoveMode.constrainBeatDelta(
                pointerBeat.minus(delegateOwner.noteDragAnchorBeat));
        int primaryIndex = Math.max(0, delegateOwner.draggedNotes.indexOf(delegateOwner.selectedNote));
        Note primaryBefore = delegateOwner.noteDragBefore.get(primaryIndex);
        double rawDelta = EditorView.screenToChartX(x, 0f, right) - delegateOwner.noteDragAnchorX;
        double targetX = delegateOwner.snapChartX(primaryBefore.positionX + rawDelta);
        delegateOwner.noteDragDeltaX = delegateOwner.noteMoveMode.constrainXDelta(
                targetX - primaryBefore.positionX);
        delegateOwner.noteDragValidation = NoteTouchOperation.move(delegateOwner.currentLine(), delegateOwner.draggedNotes,
                delegateOwner.noteDragDeltaBeat, delegateOwner.noteDragDeltaX).error;
    }

    void finishNoteDrag() {
        NoteTouchOperation.Result result = NoteTouchOperation.move(
                delegateOwner.currentLine(), delegateOwner.draggedNotes, delegateOwner.noteDragDeltaBeat, delegateOwner.noteDragDeltaX);
        delegateOwner.noteDragValidation = result.error;
        if (result.error == NoteTouchOperation.Error.NONE) {
            delegateOwner.history.execute(result.command);
            delegateOwner.clearSelection();
            delegateOwner.selectedNotes.addAll(result.notes);
            delegateOwner.selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
            delegateOwner.activateEditPanel();
        } else if (delegateOwner.callback != null) {
            delegateOwner.callback.showMessage(delegateOwner.noteGestureController.noteTouchErrorMessage(result.error));
        }
        delegateOwner.noteGestureController.clearNoteTouchState();
    }

    void beginHoldResize(Note note, boolean startHandle) {
        delegateOwner.noteGestureController.clearNoteTouchState();
        delegateOwner.resizedHold = note;
        delegateOwner.holdBeforeStart = note.startTime;
        delegateOwner.holdBeforeEnd = note.endTime;
        delegateOwner.holdCandidateStart = note.startTime;
        delegateOwner.holdCandidateEnd = note.endTime;
        delegateOwner.noteDragValidation = NoteTouchOperation.Error.NONE;
        delegateOwner.gestureMode = startHandle ? GestureMode.HOLD_DRAG_START : GestureMode.HOLD_DRAG_END;
    }

    void updateHoldResizeCandidate(float y, float bottom) {
        if (delegateOwner.resizedHold == null) return;
        BeatTime candidate = BeatTime.fromDouble(
                delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom))), delegateOwner.subdivision);
        if (delegateOwner.gestureMode == GestureMode.HOLD_DRAG_START) delegateOwner.holdCandidateStart = candidate;
        else delegateOwner.holdCandidateEnd = candidate;
        delegateOwner.noteDragValidation = NoteTouchOperation.resizeHold(delegateOwner.currentLine(), delegateOwner.resizedHold,
                delegateOwner.holdCandidateStart, delegateOwner.holdCandidateEnd).error;
    }

    void finishHoldResize() {
        NoteTouchOperation.Result result = NoteTouchOperation.resizeHold(
                delegateOwner.currentLine(), delegateOwner.resizedHold, delegateOwner.holdCandidateStart, delegateOwner.holdCandidateEnd);
        delegateOwner.noteDragValidation = result.error;
        if (result.error == NoteTouchOperation.Error.NONE) {
            delegateOwner.history.execute(result.command);
            delegateOwner.clearSelection();
            delegateOwner.selectedNotes.addAll(result.notes);
            delegateOwner.selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
            delegateOwner.activateEditPanel();
        } else if (delegateOwner.callback != null) {
            delegateOwner.callback.showMessage(delegateOwner.noteGestureController.noteTouchErrorMessage(result.error));
        }
        delegateOwner.noteGestureController.clearNoteTouchState();
    }

    boolean isHoldResizeGesture() {
        return delegateOwner.gestureMode == GestureMode.HOLD_DRAG_START
                || delegateOwner.gestureMode == GestureMode.HOLD_DRAG_END;
    }

    void clearNoteTouchState() {
        delegateOwner.draggedNotes.clear();
        delegateOwner.noteDragBefore.clear();
        delegateOwner.noteDragAnchorBeat = null;
        delegateOwner.noteDragAnchorX = 0.0;
        delegateOwner.noteDragDeltaBeat = BeatTime.zero();
        delegateOwner.noteDragDeltaX = 0.0;
        delegateOwner.noteDragValidation = NoteTouchOperation.Error.NONE;
        delegateOwner.resizedHold = null;
        delegateOwner.holdBeforeStart = null;
        delegateOwner.holdBeforeEnd = null;
        delegateOwner.holdCandidateStart = null;
        delegateOwner.holdCandidateEnd = null;
    }

    String noteTouchErrorMessage(NoteTouchOperation.Error error) {
        switch (error) {
            case NEGATIVE_START_TIME:
                return delegateOwner.getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return delegateOwner.getResources().getString(R.string.validation_end_after_start);
            case X_OUT_OF_RANGE:
                return delegateOwner.getResources().getString(R.string.validation_note_x);
            case TARGET_NOT_FOUND:
                return delegateOwner.getResources().getString(R.string.validation_target_changed);
            default:
                return delegateOwner.getResources().getString(R.string.note_touch_invalid);
        }
    }

    static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
