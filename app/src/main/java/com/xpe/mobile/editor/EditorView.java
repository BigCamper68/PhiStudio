package com.xpe.mobile.editor;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import java.util.List;

public final class EditorView extends EditorChromeView {
    final EditorGestureRouter editorGestureRouter = new EditorGestureRouter(this);

    final EditorClipboardController editorClipboardController = new EditorClipboardController(this);
    final EditorSelectionEdits editorSelectionEdits = new EditorSelectionEdits(this);
    final EventGestureController eventGestureController = new EventGestureController(this);
    final NoteGestureController noteGestureController = new NoteGestureController(this);

    private final ScaleGestureDetector scaleDetector;
    boolean storyboardBoxGesture;

    public EditorView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelActiveGesture();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleTimeline(detector.getScaleFactor());
                invalidate();
                return true;
            }
        });
        // Android enables double-tap-and-drag "quick scale" by default. Only pinch should zoom.
        scaleDetector.setQuickScaleEnabled(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (chart != null && editorBottomBarUi.onTouchEvent(event)) return true;
        boolean boxSelection = rectangleSelectionMode == RectangleSelectionMode.ADD;
        if (!boxSelection) scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() > 1) {
            cancelActiveGesture();
            return true;
        }
        if (!boxSelection && scaleDetector.isInProgress()) return true;
        if (chart == null) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                return editorGestureRouter.handleDown(event.getX(), event.getY());
            case MotionEvent.ACTION_MOVE:
                return editorGestureRouter.handleMove(event.getX(), event.getY());
            case MotionEvent.ACTION_UP: {
                boolean handled = editorGestureRouter.handleUp(event.getX(), event.getY());
                if (!movedDuringGesture) performClick();
                return handled;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelActiveGesture();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public boolean onHoverEvent(MotionEvent event) {
        return previewNoteSource.hover(event) || super.onHoverEvent(event);
    }

    @Override
    protected void cancelActiveGesture() {
        previewNoteSource.cancelGesture();
        editorBottomBarUi.cancelTouch();
        editorGestureRouter.cancel();
        if (storyboardTimeline != null) storyboardTimeline.cancel();
        finishPlaybackScrub();
        gestureMode = GestureMode.NONE;
        previewEventType = null;
        clearEventDragState();
        noteGestureController.clearNoteTouchState();
        selectionRectangle = null;
        movedDuringGesture = true;
        invalidate();
    }

    @Override
    protected void clearEventDragState() {
        eventGestureController.clearEventDragState();
    }

    @Override
    protected void deleteSelection() {
        editorSelectionEdits.deleteSelection();
    }

    @Override
    protected void mirrorSelectedNote() {
        editorSelectionEdits.mirrorSelectedNote();
    }

    @Override
    protected void switchSelectedNoteSide() {
        editorSelectionEdits.switchSelectedNoteSide();
    }

    @Override
    protected void toggleSelectedNoteFake() {
        editorSelectionEdits.toggleSelectedNoteFake();
    }

    @Override
    protected void resizeSelectedNote(double delta) {
        editorSelectionEdits.resizeSelectedNote(delta);
    }

    @Override
    protected void adjustSelectedEventValue(boolean startValue, double delta) {
        editorSelectionEdits.adjustSelectedEventValue(startValue, delta);
    }

    @Override
    protected void toggleSelectedEventLink() {
        editorSelectionEdits.toggleSelectedEventLink();
    }

    @Override
    protected void splitSelectedEvent() {
        editorSelectionEdits.splitSelectedEvent();
    }

    @Override
    protected void subdivideSelectedEvents() {
        editorSelectionEdits.subdivideSelectedEvents();
    }

    @Override
    protected void passSelectedEvent() {
        editorSelectionEdits.passSelectedEvent();
    }

    @Override
    protected void randomizeSelectedEvent() {
        editorSelectionEdits.randomizeSelectedEvent();
    }

    @Override
    protected void glueSelectedEvent() {
        editorSelectionEdits.glueSelectedEvent();
    }

    @Override
    protected void undo() {
        history.undo();
        if (settings.skipWhenUndoRedo) focusCurrentSelection();
        invalidate();
    }

    @Override
    protected EditHistory.Command withAutoStick(EventLayer layer, LineEvent target,
                                                LineEvent edited,
                                                EditHistory.Command command) {
        if (!settings.autoStickEvents) return command;
        LineEvent next = null;
        for (LineEvent candidate : layer.events(target.type)) {
            if (candidate == target) continue;
            if (candidate.startTime.compareTo(edited.endTime) >= 0
                    && (next == null
                    || candidate.startTime.compareTo(next.startTime) < 0)) {
                next = candidate;
            }
        }
        if (next == null) return command;
        LineEvent bonded = next;
        double beforeStart = bonded.start;
        double afterStart = edited.end;
        return new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                bonded.start = afterStart;
            }

            @Override
            public void revert() {
                bonded.start = beforeStart;
                command.revert();
            }
        };
    }

    private void focusCurrentSelection() {
        if (selectedNote != null) currentBeat = Math.max(0.0, selectedNote.startTime.toDouble());
        else if (selectedEvent != null) currentBeat = Math.max(0.0, selectedEvent.startTime.toDouble());
    }

    @Override
    protected void redo() {
        history.redo();
        if (settings.skipWhenUndoRedo) focusCurrentSelection();
        invalidate();
    }

    @Override
    protected void clearSelection() {
        selectedNote = null;
        selectedEvent = null;
        selectedNotes.clear();
        selectedEvents.clear();
        if (storyboardTimeline != null) storyboardTimeline.clearSelection();
    }

    @Override
    protected void copySelection() {
        editorClipboardController.copySelection();
    }

    @Override
    protected void cutSelection() {
        editorClipboardController.cutSelection();
    }

    @Override
    protected void pasteClipboard(boolean mirrorNotes) {
        editorClipboardController.pasteClipboard(mirrorNotes);
    }

    @Override
    protected List<Note> selectedNotesForClipboard() {
        return editorClipboardController.selectedNotesForClipboard();
    }

    @Override
    protected List<LineEvent> selectedEventsForClipboard() {
        return editorClipboardController.selectedEventsForClipboard();
    }

    interface NoteMutation {
        void apply(Note note);
    }

    interface EventMutation {
        void apply(LineEvent event);
    }

    static final class EventHandleHit {
        final LineEvent event;
        final boolean startHandle;

        EventHandleHit(LineEvent event, boolean startHandle) {
            this.event = event;
            this.startHandle = startHandle;
        }
    }

    static final class HoldHandleHit {
        final boolean startHandle;

        HoldHandleHit(boolean startHandle) {
            this.startHandle = startHandle;
        }
    }

}
