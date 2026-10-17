package com.xpe.mobile.editor;

import android.graphics.RectF;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import java.util.ArrayList;
import java.util.List;

/** EditorClipboardController responsibilities extracted from EditorView. */
final class EditorClipboardController {
    private final EditorView delegateOwner;

    EditorClipboardController(EditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    float rectangleX(float x) {
        if (delegateOwner.workbenchTab != 1) return x;
        return delegateOwner.storyboardBoxGesture ? Math.max(delegateOwner.editorSplitX(), x)
                : Math.min(delegateOwner.editorSplitX() - .001f, x);
    }

    boolean addSelectionAt(float x, float y, float split, float bottom) {
        if (x < split) {
            Note note = delegateOwner.noteGestureController.findNearestNote(x, y, split, bottom);
            if (note == null) return false;
            delegateOwner.selectedNotes.add(note);
        } else {
            if (delegateOwner.workbenchTab == 1) return delegateOwner.storyboardTimeline.addSelectionAt(x, y);
            LineEvent event = delegateOwner.eventGestureController.findEventAt(x, y, split, bottom);
            if (event == null) return false;
            delegateOwner.editorClipboardController.updateRectangleEventSelection(event, true);
        }
        delegateOwner.selectedNote = delegateOwner.selectedNotes.isEmpty() ? null : delegateOwner.selectedNotes.iterator().next();
        delegateOwner.selectedEvent = delegateOwner.selectedEvents.isEmpty() ? null : delegateOwner.selectedEvents.iterator().next();
        if (delegateOwner.selectedNote != null) delegateOwner.selectedEvent = null;
        delegateOwner.activateEditPanel();
        return true;
    }

    void applyRectangleSelection(RectF rawBounds, float split, float bottom) {
        RectF bounds = EditorView.normalized(rawBounds);
        float centerY = delegateOwner.timelineY(bottom);
        boolean add = delegateOwner.rectangleSelectionMode == RectangleSelectionMode.ADD;
        if (bounds.left < split) {
            for (Note note : delegateOwner.currentLine().notes) {
                float x = EditorView.chartXToScreen(note.positionX, 0f, split);
                float startY = delegateOwner.beatToY(note.startTime.toDouble(), centerY);
                float endY = note.type == NoteType.HOLD
                        ? delegateOwner.beatToY(note.endTime.toDouble(), centerY) : startY;
                if (x >= bounds.left && x <= bounds.right
                        && Math.max(Math.min(startY, endY), bounds.top)
                        <= Math.min(Math.max(startY, endY), bounds.bottom)) {
                    if (add) delegateOwner.selectedNotes.add(note); else delegateOwner.selectedNotes.remove(note);
                }
            }
        }
        if (bounds.right >= split && delegateOwner.workbenchTab == 1) {
            delegateOwner.storyboardTimeline.selectRectangle(bounds, add);
        } else if (bounds.right >= split && delegateOwner.workbenchTab == 0) {
            float columnWidth = Math.max(1f, (delegateOwner.getWidth() - split) / EventType.values().length);
            for (EventType type : EventType.values()) {
                float left = split + type.ordinal() * columnWidth;
                float right = left + columnWidth;
                if (right < bounds.left || left > bounds.right) continue;
                for (LineEvent event : delegateOwner.currentLayer().events(type)) {
                    float startY = delegateOwner.beatToY(event.startTime.toDouble(), centerY);
                    float endY = delegateOwner.beatToY(event.endTime.toDouble(), centerY);
                    if (Math.max(Math.min(startY, endY), bounds.top)
                            <= Math.min(Math.max(startY, endY), bounds.bottom)) {
                        delegateOwner.editorClipboardController.updateRectangleEventSelection(event, add);
                    }
                }
            }
        }
        delegateOwner.selectedNote = delegateOwner.selectedNotes.isEmpty() ? null : delegateOwner.selectedNotes.iterator().next();
        delegateOwner.selectedEvent = delegateOwner.selectedEvents.isEmpty() ? null : delegateOwner.selectedEvents.iterator().next();
        if (delegateOwner.selectedNote != null) delegateOwner.selectedEvent = null;
    }

    void updateRectangleEventSelection(LineEvent event, boolean add) {
        if (add) delegateOwner.selectedEvents.add(event); else delegateOwner.selectedEvents.remove(event);
        if (!delegateOwner.xyBindingEnabled || !XYBindingValidator.isMoveType(event.type)) return;
        XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(delegateOwner.currentLayer(), event);
        if (pair.error == XYBindingValidator.Error.NONE && pair.event != null) {
            if (add) delegateOwner.selectedEvents.add(pair.event); else delegateOwner.selectedEvents.remove(pair.event);
        }
    }

    void copySelection() {
        if (delegateOwner.workbenchTab == 1 && delegateOwner.storyboardTimeline.selected() != null) {
            delegateOwner.storyboardTimeline.copy(); delegateOwner.invalidate(); return;
        }
        delegateOwner.clipboard = ChartClipboard.copy(delegateOwner.selectedNotesForClipboard(), delegateOwner.selectedEventsForClipboard());
        delegateOwner.storyboardTimeline.deactivateClipboard();
        if (delegateOwner.clipboard.isEmpty()) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(R.string.clipboard_empty_selection));
            return;
        }
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(
                R.string.clipboard_copied, delegateOwner.clipboard.noteCount(), delegateOwner.clipboard.eventCount()));
        delegateOwner.invalidate();
    }

    void maybeAutoCopySelection() {
        if (delegateOwner.settings.autoMoveToClipboard && delegateOwner.workbenchTab == 1
                && delegateOwner.storyboardTimeline.selectionCount() > 1) {
            delegateOwner.storyboardTimeline.copy(); return;
        }
        if (!delegateOwner.settings.autoMoveToClipboard || delegateOwner.selectionCount() <= 1) return;
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(
                delegateOwner.selectedNotesForClipboard(), delegateOwner.selectedEventsForClipboard());
        if (!snapshot.isEmpty()) delegateOwner.clipboard = snapshot;
    }

    void cutSelection() {
        if (delegateOwner.workbenchTab == 1 && delegateOwner.storyboardTimeline.selected() != null) {
            delegateOwner.storyboardTimeline.cut(); return;
        }
        delegateOwner.storyboardTimeline.deactivateClipboard();
        List<Note> notes = delegateOwner.selectedNotesForClipboard();
        List<LineEvent> events = delegateOwner.selectedEventsForClipboard();
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(notes, events);
        ChartClipboard.Operation operation = ChartClipboard.prepareCut(
                delegateOwner.currentLine(), delegateOwner.currentLayer(), notes, events, delegateOwner.xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            delegateOwner.editorClipboardController.showClipboardError(operation.error);
            return;
        }
        delegateOwner.clipboard = snapshot;
        delegateOwner.editorClipboardController.executeRemovalOperation(operation);
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(R.string.clipboard_cut));
    }

    void deleteMultipleSelection() {
        ChartClipboard.Operation operation = ChartClipboard.prepareCut(delegateOwner.currentLine(), delegateOwner.currentLayer(),
                delegateOwner.selectedNotesForClipboard(), delegateOwner.selectedEventsForClipboard(), delegateOwner.xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            delegateOwner.editorClipboardController.showClipboardError(operation.error);
            return;
        }
        delegateOwner.editorClipboardController.executeRemovalOperation(operation);
    }

    void executeRemovalOperation(ChartClipboard.Operation operation) {
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                operation.command.apply();
                delegateOwner.clearSelection();
            }

            @Override
            public void revert() {
                operation.command.revert();
                delegateOwner.selectedNotes.addAll(operation.notes);
                delegateOwner.selectedEvents.addAll(operation.events);
                delegateOwner.selectedNote = delegateOwner.selectedNotes.isEmpty() ? null : delegateOwner.selectedNotes.iterator().next();
                delegateOwner.selectedEvent = delegateOwner.selectedNote == null && !delegateOwner.selectedEvents.isEmpty()
                        ? delegateOwner.selectedEvents.iterator().next() : null;
            }
        });
        delegateOwner.invalidate();
    }

    void pasteClipboard(boolean mirrorNotes) {
        if (delegateOwner.workbenchTab == 1 && delegateOwner.storyboardTimeline.hasClipboard()) {
            delegateOwner.storyboardTimeline.paste(); return;
        }
        BeatTime anchor = BeatTime.fromDouble(Math.max(0.0, delegateOwner.snapBeat(delegateOwner.currentBeat)), delegateOwner.subdivision);
        ChartClipboard.Operation operation = ChartClipboard.preparePaste(delegateOwner.clipboard,
                delegateOwner.currentLine(), delegateOwner.currentLayer(), delegateOwner.eventLayerIndex, anchor, mirrorNotes, delegateOwner.xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            delegateOwner.editorClipboardController.showClipboardError(operation.error);
            return;
        }
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                operation.command.apply();
                delegateOwner.clearSelection();
                delegateOwner.selectedNotes.addAll(operation.notes);
                delegateOwner.selectedEvents.addAll(operation.events);
                delegateOwner.selectedNote = delegateOwner.selectedNotes.isEmpty() ? null : delegateOwner.selectedNotes.iterator().next();
                delegateOwner.selectedEvent = delegateOwner.selectedNote == null && !delegateOwner.selectedEvents.isEmpty()
                        ? delegateOwner.selectedEvents.iterator().next() : null;
            }

            @Override
            public void revert() {
                operation.command.revert();
                delegateOwner.clearSelection();
            }
        });
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(R.string.clipboard_pasted));
        delegateOwner.invalidate();
    }

    List<Note> selectedNotesForClipboard() {
        List<Note> result = new ArrayList<>();
        for (Note note : delegateOwner.selectedNotes) if (delegateOwner.currentLine().notes.contains(note)) result.add(note);
        if (delegateOwner.selectedNote != null && delegateOwner.currentLine().notes.contains(delegateOwner.selectedNote)
                && !result.contains(delegateOwner.selectedNote)) result.add(delegateOwner.selectedNote);
        return result;
    }

    List<LineEvent> selectedEventsForClipboard() {
        List<LineEvent> result = new ArrayList<>();
        for (LineEvent event : delegateOwner.selectedEvents) {
            if (delegateOwner.currentLayer().events(event.type).contains(event)) result.add(event);
        }
        if (delegateOwner.selectedEvent != null && delegateOwner.currentLayer().events(delegateOwner.selectedEvent.type).contains(delegateOwner.selectedEvent)
                && !result.contains(delegateOwner.selectedEvent)) result.add(delegateOwner.selectedEvent);
        if (delegateOwner.xyBindingEnabled) {
            List<LineEvent> initiallySelected = new ArrayList<>(result);
            for (LineEvent event : initiallySelected) {
                if (!XYBindingValidator.isMoveType(event.type)) continue;
                XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(delegateOwner.currentLayer(), event);
                if (pair.error == XYBindingValidator.Error.NONE && pair.event != null
                        && !result.contains(pair.event)) result.add(pair.event);
            }
        }
        return result;
    }

    void showClipboardError(ChartClipboard.Error error) {
        int message;
        switch (error) {
            case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
            case RESERVED_LAYER: message = R.string.validation_event_reserved_layer; break;
            case XY_PAIR_REQUIRED: message = R.string.clipboard_xy_pair_required; break;
            case EMPTY: message = R.string.clipboard_empty_selection; break;
            default: message = R.string.clipboard_invalid; break;
        }
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(message));
    }
}
