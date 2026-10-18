package com.xpe.mobile.editor;

import android.graphics.RectF;
import android.view.ViewConfiguration;
import com.xpe.mobile.editor.EditorChromeView.ButtonSpec;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;
import com.xpe.mobile.editor.EditorSurfaceView.RectangleSelectionMode;
import com.xpe.mobile.editor.EditorSurfaceView.Tool;
import com.xpe.mobile.editor.EditorView.EventHandleHit;
import com.xpe.mobile.editor.EditorView.HoldHandleHit;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

/** EditorGestureRouter responsibilities extracted from EditorView. */
final class EditorGestureRouter {
    private final EditorView delegateOwner;
    private final int touchSlop;
    private Runnable pendingTap;

    EditorGestureRouter(EditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
        touchSlop = ViewConfiguration.get(delegateOwner.getContext()).getScaledTouchSlop();
    }

    void cancel() { pendingTap = null; }

    boolean handleDown(float x, float y) {
        cancel();
        delegateOwner.gestureMode = GestureMode.NONE;
        delegateOwner.movedDuringGesture = false;
        if (delegateOwner.playbackSeekBounds.contains(x, y)) {
            return delegateOwner.beginPlaybackSeek(x, y);
        }
        for (int index = delegateOwner.buttons.size() - 1; index >= 0; index--) {
            ButtonSpec button = delegateOwner.buttons.get(index);
            if (button.bounds.contains(x, y)) {
                button.action.run();
                delegateOwner.invalidate();
                return true;
            }
        }

        if (delegateOwner.previewMode) {
            if (y > delegateOwner.toolbarHeight()) {
                delegateOwner.gestureStartX = x;
                delegateOwner.gestureStartY = y;
                delegateOwner.gestureLastY = y;
                delegateOwner.beginPanGesture();
            }
            return true;
        }

        if (!delegateOwner.isInEditor(y)) return true;
        if (x >= delegateOwner.editorSplitX() && delegateOwner.workbenchTab == 2) return true;
        delegateOwner.gestureStartX = x;
        delegateOwner.gestureStartY = y;
        delegateOwner.gestureLastY = y;
        float split = delegateOwner.editorSplitX();
        float bottom = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();

        if (delegateOwner.tool == Tool.SELECT && delegateOwner.rectangleSelectionMode == RectangleSelectionMode.ADD) {
            delegateOwner.storyboardBoxGesture = delegateOwner.workbenchTab == 1 && x >= split;
            if (delegateOwner.storyboardBoxGesture) {
                delegateOwner.selectedNote = null; delegateOwner.selectedNotes.clear();
                delegateOwner.selectedEvent = null; delegateOwner.selectedEvents.clear();
            }
            delegateOwner.gestureMode = GestureMode.RECT_SELECT;
            delegateOwner.selectionRectangle = new RectF(x, y, x, y);
            return true;
        }
        if (x >= split && delegateOwner.workbenchTab == 1) return delegateOwner.storyboardTimeline.down(x, y);

        if (delegateOwner.tool == Tool.HOLD && x < split) {
            delegateOwner.gestureMode = GestureMode.HOLD_PREVIEW;
            delegateOwner.previewStartBeat = delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom)));
            delegateOwner.previewEndBeat = delegateOwner.previewStartBeat + 1.0 / delegateOwner.subdivision;
            return true;
        }

        if (delegateOwner.tool == Tool.EVENT && x >= split) {
            delegateOwner.gestureMode = GestureMode.EVENT_PREVIEW;
            delegateOwner.previewEventType = delegateOwner.eventTypeAtX(x, split);
            delegateOwner.selectedEventType = delegateOwner.previewEventType;
            delegateOwner.previewStartBeat = delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom)));
            delegateOwner.previewEndBeat = delegateOwner.previewStartBeat + 1.0 / delegateOwner.subdivision;
            return true;
        }

        if (delegateOwner.tool == Tool.TAP || delegateOwner.tool == Tool.DRAG || delegateOwner.tool == Tool.FLICK) {
            if (x < split) {
                pendingTap = () -> delegateOwner.noteGestureController.addSimpleNoteAt(x, y, split, bottom);
                delegateOwner.gestureMode = GestureMode.GRID_TAP;
            } else delegateOwner.beginPanGesture();
            return true;
        }

        if (delegateOwner.tool == Tool.SELECT) {
            if (x < split) {
                Note note = delegateOwner.noteGestureController.findNearestNote(x, y, split, bottom);
                if (note != null) {
                    boolean alreadySelected = delegateOwner.selectedNotes.contains(note)
                            || delegateOwner.selectedNote == note;
                    HoldHandleHit holdHandle = alreadySelected
                            && delegateOwner.selectionCount() == 1 && note.type == NoteType.HOLD
                            ? delegateOwner.noteGestureController.findHoldHandleAt(note, x, y, split, bottom) : null;
                    if (holdHandle != null) delegateOwner.noteGestureController.beginHoldResize(note, holdHandle.startHandle);
                    else if (alreadySelected && delegateOwner.noteMoveMode.enabled()) {
                        delegateOwner.selectedNote = note;
                        delegateOwner.noteGestureController.beginNoteDrag(note, x, y, split, bottom);
                    } else {
                        pendingTap = () -> {
                            if (!alreadySelected) delegateOwner.clearSelection();
                            delegateOwner.selectedNote = note;
                            delegateOwner.selectedNotes.add(note);
                            delegateOwner.selectedEvent = null;
                            delegateOwner.activateEditPanel();
                        };
                        delegateOwner.gestureMode = GestureMode.GRID_TAP;
                    }
                } else {
                    delegateOwner.beginPanGesture();
                }
            } else {
                EventHandleHit handle = delegateOwner.eventGestureController.findEventHandleAt(x, y, split, bottom);
                if (handle != null && handle.event == delegateOwner.selectedEvent
                        && delegateOwner.selectionCount() == 1) {
                    delegateOwner.eventGestureController.beginEventDrag(handle.event, handle.startHandle);
                } else {
                    LineEvent event = handle != null ? handle.event
                            : delegateOwner.eventGestureController.findEventAt(x, y, split, bottom);
                    if (event != null) {
                        pendingTap = () -> {
                            delegateOwner.clearSelection();
                            delegateOwner.selectedEvent = event;
                            delegateOwner.selectedEvents.add(event);
                            delegateOwner.selectedNote = null;
                            delegateOwner.selectedEventType = event.type;
                            delegateOwner.activateEditPanel();
                        };
                        delegateOwner.gestureMode = GestureMode.GRID_TAP;
                    } else {
                        delegateOwner.beginPanGesture();
                    }
                }
            }
            delegateOwner.invalidate();
        } else delegateOwner.beginPanGesture();
        return true;
    }

    boolean handleMove(float x, float y) {
        float bottom = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();
        if (Math.abs(y - delegateOwner.gestureStartY) > touchSlop
                || Math.abs(x - delegateOwner.gestureStartX) > touchSlop) delegateOwner.movedDuringGesture = true;
        if (delegateOwner.storyboardTimeline.active()) return delegateOwner.storyboardTimeline.move(x, y);
        if (delegateOwner.gestureMode == GestureMode.GRID_TAP && delegateOwner.movedDuringGesture) {
            cancel();
            delegateOwner.beginPanGesture();
        }
        if (delegateOwner.gestureMode == GestureMode.SEEK_BAR) {
            delegateOwner.updatePlaybackSeek(x);
            delegateOwner.invalidate();
        } else if (delegateOwner.gestureMode == GestureMode.PAN) {
            delegateOwner.currentBeat = Math.max(0.0, delegateOwner.currentBeat + (y - delegateOwner.gestureLastY)
                    / delegateOwner.pixelsPerBeat * delegateOwner.settings.timelineScrollSpeed);
            delegateOwner.gestureLastY = y;
            delegateOwner.invalidate();
        } else if (delegateOwner.gestureMode == GestureMode.HOLD_PREVIEW || delegateOwner.gestureMode == GestureMode.EVENT_PREVIEW) {
            delegateOwner.previewEndBeat = delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom)));
            if (delegateOwner.previewEndBeat <= delegateOwner.previewStartBeat) delegateOwner.previewEndBeat = delegateOwner.previewStartBeat + 1.0 / delegateOwner.subdivision;
            delegateOwner.invalidate();
        } else if (delegateOwner.eventGestureController.isEventDragGesture()) {
            if (Math.abs(y - delegateOwner.gestureStartY) >= 0.5f * delegateOwner.density) {
                delegateOwner.eventDragPointerMoved = true;
                delegateOwner.eventGestureController.updateEventDragCandidate(y, bottom);
                delegateOwner.invalidate();
            }
        } else if (delegateOwner.gestureMode == GestureMode.NOTE_DRAG) {
            if (delegateOwner.movedDuringGesture) {
                delegateOwner.noteGestureController.updateNoteDragCandidate(x, y, delegateOwner.editorSplitX(), bottom);
                delegateOwner.invalidate();
            }
        } else if (delegateOwner.noteGestureController.isHoldResizeGesture()) {
            if (delegateOwner.movedDuringGesture) {
                delegateOwner.noteGestureController.updateHoldResizeCandidate(y, bottom);
                delegateOwner.invalidate();
            }
        } else if (delegateOwner.gestureMode == GestureMode.RECT_SELECT && delegateOwner.selectionRectangle != null) {
            delegateOwner.selectionRectangle.right = delegateOwner.editorClipboardController.rectangleX(x);
            delegateOwner.selectionRectangle.bottom = y;
            delegateOwner.invalidate();
        }
        return true;
    }

    boolean handleUp(float x, float y) {
        if (Math.abs(y - delegateOwner.gestureStartY) > touchSlop
                || Math.abs(x - delegateOwner.gestureStartX) > touchSlop) {
            delegateOwner.movedDuringGesture = true;
            if (delegateOwner.gestureMode == GestureMode.GRID_TAP) handleMove(x, y);
        }
        if (delegateOwner.storyboardTimeline.active()) return delegateOwner.storyboardTimeline.up(x, y);
        float split = delegateOwner.editorSplitX();
        float bottom = delegateOwner.getHeight() - delegateOwner.bottomBarHeight();
        if (delegateOwner.gestureMode == GestureMode.GRID_TAP) {
            Runnable tap = pendingTap;
            cancel();
            if (!delegateOwner.movedDuringGesture && delegateOwner.isInEditor(y) && tap != null) tap.run();
        } else if (delegateOwner.gestureMode == GestureMode.SEEK_BAR) {
            delegateOwner.updatePlaybackSeek(x);
            delegateOwner.finishPlaybackScrub();
        } else if (delegateOwner.gestureMode == GestureMode.HOLD_PREVIEW) {
            delegateOwner.previewEndBeat = delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom)));
            if (delegateOwner.previewEndBeat <= delegateOwner.previewStartBeat) delegateOwner.previewEndBeat = delegateOwner.previewStartBeat + 1.0 / delegateOwner.subdivision;
            delegateOwner.noteGestureController.addHoldAt(delegateOwner.gestureStartX, delegateOwner.previewStartBeat, delegateOwner.previewEndBeat, split);
        } else if (delegateOwner.gestureMode == GestureMode.EVENT_PREVIEW) {
            delegateOwner.previewEndBeat = delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom)));
            if (delegateOwner.previewEndBeat <= delegateOwner.previewStartBeat) delegateOwner.previewEndBeat = delegateOwner.previewStartBeat + 1.0 / delegateOwner.subdivision;
            delegateOwner.eventGestureController.addEvent(delegateOwner.previewEventType, delegateOwner.previewStartBeat, delegateOwner.previewEndBeat);
        } else if (delegateOwner.eventGestureController.isEventDragGesture()) {
            if (delegateOwner.eventDragPointerMoved) {
                delegateOwner.eventGestureController.updateEventDragCandidate(y, bottom);
                delegateOwner.eventGestureController.finishEventDrag();
            } else {
                delegateOwner.clearEventDragState();
            }
        } else if (delegateOwner.gestureMode == GestureMode.NOTE_DRAG) {
            if (delegateOwner.movedDuringGesture) {
                delegateOwner.noteGestureController.updateNoteDragCandidate(x, y, split, bottom);
                delegateOwner.noteGestureController.finishNoteDrag();
            } else {
                delegateOwner.noteGestureController.clearNoteTouchState();
            }
        } else if (delegateOwner.noteGestureController.isHoldResizeGesture()) {
            if (delegateOwner.movedDuringGesture) {
                delegateOwner.noteGestureController.updateHoldResizeCandidate(y, bottom);
                delegateOwner.noteGestureController.finishHoldResize();
            } else {
                delegateOwner.noteGestureController.clearNoteTouchState();
            }
        } else if (delegateOwner.gestureMode == GestureMode.RECT_SELECT) {
            if (delegateOwner.selectionRectangle != null) {
                delegateOwner.selectionRectangle.right = delegateOwner.editorClipboardController.rectangleX(x);
                delegateOwner.selectionRectangle.bottom = y;
                if (delegateOwner.movedDuringGesture) {
                    delegateOwner.editorClipboardController.applyRectangleSelection(delegateOwner.selectionRectangle, split, bottom);
                    delegateOwner.editorClipboardController.maybeAutoCopySelection();
                } else if (delegateOwner.editorClipboardController.addSelectionAt(delegateOwner.editorClipboardController.rectangleX(x), y, split, bottom)) {
                    delegateOwner.editorClipboardController.maybeAutoCopySelection();
                } else {
                    delegateOwner.clearSelection();
                }
            }
            delegateOwner.selectionRectangle = null;
        } else if (delegateOwner.gestureMode == GestureMode.PAN) {
            if (!delegateOwner.movedDuringGesture && !delegateOwner.previewMode) {
                boolean clearing = delegateOwner.clearTimelineSelectionOnTap();
                if (!clearing && delegateOwner.settings.tapToSeek)
                    delegateOwner.currentBeat = Math.max(0.0, delegateOwner.snapBeat(delegateOwner.yToBeat(y, delegateOwner.timelineY(bottom))));
            }
            delegateOwner.finishPlaybackScrub();
        }
        delegateOwner.gestureMode = GestureMode.NONE;
        cancel();
        delegateOwner.invalidate();
        return true;
    }
}
