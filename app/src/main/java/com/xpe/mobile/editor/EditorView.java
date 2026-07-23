package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import com.xpe.mobile.R;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.List;

public final class EditorView extends EditorChromeView {
    private final ScaleGestureDetector scaleDetector;

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
                pixelsPerBeat = clamp(pixelsPerBeat * detector.getScaleFactor(), 38f * density, 280f * density);
                invalidate();
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
                return handleDown(event.getX(), event.getY());
            case MotionEvent.ACTION_MOVE:
                return handleMove(event.getX(), event.getY());
            case MotionEvent.ACTION_UP: {
                boolean click = !movedDuringGesture;
                boolean handled = handleUp(event.getX(), event.getY());
                if (click) performClick();
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

    private boolean handleDown(float x, float y) {
        movedDuringGesture = false;
        if (playbackSeekBounds.contains(x, y)) {
            return beginPlaybackSeek(x, y);
        }
        for (int index = buttons.size() - 1; index >= 0; index--) {
            ButtonSpec button = buttons.get(index);
            if (button.bounds.contains(x, y)) {
                button.action.run();
                invalidate();
                return true;
            }
        }

        if (previewMode) {
            if (y > toolbarHeight()) {
                gestureStartX = x;
                gestureStartY = y;
                gestureLastY = y;
                beginPanGesture();
            }
            return true;
        }

        if (!isInEditor(y)) return true;
        gestureStartX = x;
        gestureStartY = y;
        gestureLastY = y;
        float split = editorSplitX();
        float bottom = getHeight() - bottomBarHeight();

        if (tool == Tool.SELECT && rectangleSelectionMode == RectangleSelectionMode.ADD) {
            gestureMode = GestureMode.RECT_SELECT;
            selectionRectangle = new RectF(x, y, x, y);
            return true;
        }

        if (tool == Tool.HOLD && x < split) {
            gestureMode = GestureMode.HOLD_PREVIEW;
            previewStartBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            previewEndBeat = previewStartBeat + 1.0 / subdivision;
            return true;
        }

        if (tool == Tool.EVENT && x >= split) {
            gestureMode = GestureMode.EVENT_PREVIEW;
            previewEventType = eventTypeAtX(x, split);
            selectedEventType = previewEventType;
            previewStartBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            previewEndBeat = previewStartBeat + 1.0 / subdivision;
            return true;
        }

        if (tool == Tool.TAP || tool == Tool.DRAG || tool == Tool.FLICK) {
            if (x < split) addSimpleNoteAt(x, y, split, bottom);
            return true;
        }

        if (tool == Tool.SELECT) {
            if (x < split) {
                Note note = findNearestNote(x, y, split, bottom);
                if (note != null) {
                    boolean alreadySelected = selectedNotes.contains(note)
                            || selectedNote == note;
                    HoldHandleHit holdHandle = alreadySelected
                            && selectionCount() == 1 && note.type == NoteType.HOLD
                            ? findHoldHandleAt(note, x, y, split, bottom) : null;
                    if (!alreadySelected) clearSelection();
                    selectedNote = note;
                    selectedNotes.add(note);
                    selectedEvent = null;
                    activateEditPanel();
                    if (holdHandle != null) beginHoldResize(note, holdHandle.startHandle);
                    else if (noteMoveMode.enabled()) beginNoteDrag(note, x, y, split, bottom);
                    else gestureMode = GestureMode.NONE;
                } else {
                    beginPanGesture();
                }
            } else {
                EventHandleHit handle = findEventHandleAt(x, y, split, bottom);
                if (handle != null) {
                    clearSelection();
                    selectedEvent = handle.event;
                    selectedEvents.add(handle.event);
                    selectedNote = null;
                    selectedEventType = handle.event.type;
                    activateEditPanel();
                    beginEventDrag(handle.event, handle.startHandle);
                } else {
                    LineEvent event = findEventAt(x, y, split, bottom);
                    if (event != null) {
                        clearSelection();
                        selectedEvent = event;
                        selectedEvents.add(event);
                        selectedNote = null;
                        selectedEventType = event.type;
                        gestureMode = GestureMode.NONE;
                        activateEditPanel();
                    } else {
                        beginPanGesture();
                    }
                }
            }
            invalidate();
        }
        return true;
    }

    @Override
    protected void cancelActiveGesture() {
        finishPlaybackScrub();
        gestureMode = GestureMode.NONE;
        previewEventType = null;
        clearEventDragState();
        clearNoteTouchState();
        selectionRectangle = null;
        movedDuringGesture = true;
        invalidate();
    }

    private boolean handleMove(float x, float y) {
        float bottom = getHeight() - bottomBarHeight();
        if (Math.abs(y - gestureStartY) > 3f * density || Math.abs(x - gestureStartX) > 3f * density) movedDuringGesture = true;
        if (gestureMode == GestureMode.SEEK_BAR) {
            updatePlaybackSeek(x);
            invalidate();
        } else if (gestureMode == GestureMode.PAN) {
            currentBeat = Math.max(0.0, currentBeat + (y - gestureLastY)
                    / pixelsPerBeat * settings.timelineScrollSpeed);
            gestureLastY = y;
            invalidate();
        } else if (gestureMode == GestureMode.HOLD_PREVIEW || gestureMode == GestureMode.EVENT_PREVIEW) {
            previewEndBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            if (previewEndBeat <= previewStartBeat) previewEndBeat = previewStartBeat + 1.0 / subdivision;
            invalidate();
        } else if (isEventDragGesture()) {
            if (Math.abs(y - gestureStartY) >= 0.5f * density) {
                eventDragPointerMoved = true;
                updateEventDragCandidate(y, bottom);
                invalidate();
            }
        } else if (gestureMode == GestureMode.NOTE_DRAG) {
            if (movedDuringGesture) {
                updateNoteDragCandidate(x, y, editorSplitX(), bottom);
                invalidate();
            }
        } else if (isHoldResizeGesture()) {
            if (movedDuringGesture) {
                updateHoldResizeCandidate(y, bottom);
                invalidate();
            }
        } else if (gestureMode == GestureMode.RECT_SELECT && selectionRectangle != null) {
            selectionRectangle.right = x;
            selectionRectangle.bottom = y;
            invalidate();
        }
        return true;
    }

    private boolean handleUp(float x, float y) {
        float split = editorSplitX();
        float bottom = getHeight() - bottomBarHeight();
        if (gestureMode == GestureMode.SEEK_BAR) {
            updatePlaybackSeek(x);
            finishPlaybackScrub();
        } else if (gestureMode == GestureMode.HOLD_PREVIEW) {
            previewEndBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            if (previewEndBeat <= previewStartBeat) previewEndBeat = previewStartBeat + 1.0 / subdivision;
            addHoldAt(gestureStartX, previewStartBeat, previewEndBeat, split);
        } else if (gestureMode == GestureMode.EVENT_PREVIEW) {
            previewEndBeat = snapBeat(yToBeat(y, timelineY(bottom)));
            if (previewEndBeat <= previewStartBeat) previewEndBeat = previewStartBeat + 1.0 / subdivision;
            addEvent(previewEventType, previewStartBeat, previewEndBeat);
        } else if (isEventDragGesture()) {
            if (eventDragPointerMoved) {
                updateEventDragCandidate(y, bottom);
                finishEventDrag();
            } else {
                clearEventDragState();
            }
        } else if (gestureMode == GestureMode.NOTE_DRAG) {
            if (movedDuringGesture) {
                updateNoteDragCandidate(x, y, split, bottom);
                finishNoteDrag();
            } else {
                clearNoteTouchState();
            }
        } else if (isHoldResizeGesture()) {
            if (movedDuringGesture) {
                updateHoldResizeCandidate(y, bottom);
                finishHoldResize();
            } else {
                clearNoteTouchState();
            }
        } else if (gestureMode == GestureMode.RECT_SELECT) {
            if (selectionRectangle != null) {
                selectionRectangle.right = x;
                selectionRectangle.bottom = y;
                if (movedDuringGesture) {
                    applyRectangleSelection(selectionRectangle, split, bottom);
                    maybeAutoCopySelection();
                } else if (addSelectionAt(x, y, split, bottom)) {
                    maybeAutoCopySelection();
                } else {
                    clearSelection();
                }
            }
            selectionRectangle = null;
        } else if (gestureMode == GestureMode.PAN) {
            if (!movedDuringGesture && !previewMode) {
                currentBeat = Math.max(0.0, snapBeat(yToBeat(y, timelineY(bottom))));
                clearSelection();
            }
            finishPlaybackScrub();
        }
        gestureMode = GestureMode.NONE;
        invalidate();
        return true;
    }

    private void addSimpleNoteAt(float x, float y, float right, float bottom) {
        Note note = new Note();
        note.startTime = BeatTime.fromDouble(Math.max(0.0, snapBeat(yToBeat(y, timelineY(bottom)))), subdivision);
        note.endTime = note.startTime;
        note.positionX = snapChartX(screenToChartX(x, 0f, right));
        switch (tool) {
            case TAP: note.type = NoteType.TAP; break;
            case DRAG: note.type = NoteType.DRAG; break;
            case FLICK: note.type = NoteType.FLICK; break;
            default: return;
        }
        addNoteCommand(note);
    }

    private void addHoldAt(float x, double startBeat, double endBeat, float right) {
        Note note = new Note();
        note.type = NoteType.HOLD;
        note.startTime = BeatTime.fromDouble(Math.max(0.0, startBeat), subdivision);
        note.endTime = BeatTime.fromDouble(Math.max(startBeat + 1.0 / subdivision, endBeat), subdivision);
        note.positionX = snapChartX(screenToChartX(x, 0f, right));
        addNoteCommand(note);
    }

    private void addNoteCommand(Note note) {
        JudgeLine line = currentLine();
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                if (!line.notes.contains(note)) line.notes.add(note);
                line.sortNotes();
                clearSelection();
                selectedNote = note;
                selectedNotes.add(note);
                activateEditPanel();
            }

            @Override
            public void revert() {
                line.notes.remove(note);
                selectedNotes.remove(note);
                if (selectedNote == note) selectedNote = null;
            }
        });
        invalidate();
    }

    private void addEvent(EventType type, double startBeat, double endBeat) {
        if (type == null) return;
        if (!isEditableEventLayer(eventLayerIndex)) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.validation_event_reserved_layer));
            return;
        }
        EventLayer layer = currentLayer();
        LineEvent event = createEvent(layer, type, startBeat, endBeat);

        if (xyBindingEnabled && XYBindingValidator.isMoveType(type)) {
            LineEvent pair = createEvent(layer, XYBindingValidator.pairedType(type),
                    startBeat, endBeat);
            XYBindingValidator.Error validation = XYBindingValidator.validatePlacement(
                    layer, eventLayerIndex, event, pair);
            if (validation != XYBindingValidator.Error.NONE) {
                if (callback != null) callback.showMessage(xyBindingErrorMessage(validation));
                return;
            }
            history.execute(new EditHistory.Command() {
                private final EditHistory.Command command = XYBindingCommand.add(layer, event, pair);

                @Override
                public void apply() {
                    command.apply();
                    clearSelection();
                    selectedEvent = event;
                    selectedEvents.add(event);
                    activateEditPanel();
                }

                @Override
                public void revert() {
                    command.revert();
                    selectedEvents.remove(event);
                    selectedEvents.remove(pair);
                    if (selectedEvent == event || selectedEvent == pair) selectedEvent = null;
                }
            });
        } else {
            if (layer.overlaps(event, null)) {
                if (callback != null) callback.showMessage(
                        getResources().getString(R.string.validation_event_overlap));
                return;
            }
            history.execute(new EditHistory.Command() {
                @Override
                public void apply() {
                    if (!layer.events(type).contains(event)) layer.events(type).add(event);
                    layer.events(type).sort((first, second) ->
                            first.startTime.compareTo(second.startTime));
                    clearSelection();
                    selectedEvent = event;
                    selectedEvents.add(event);
                    activateEditPanel();
                }

                @Override
                public void revert() {
                    layer.events(type).remove(event);
                    selectedEvents.remove(event);
                    if (selectedEvent == event) selectedEvent = null;
                }
            });
        }
        invalidate();
    }

    private LineEvent createEvent(EventLayer layer, EventType type,
                                  double startBeat, double endBeat) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = BeatTime.fromDouble(Math.max(0.0, startBeat), subdivision);
        event.endTime = BeatTime.fromDouble(
                Math.max(startBeat + 1.0 / subdivision, endBeat), subdivision);
        LineEvent previous = previousEvent(layer, type, event.startTime.toDouble());
        event.start = previous == null ? LineEvent.defaultValue(type) : previous.end;
        event.end = event.start;
        event.easingType = type == EventType.SPEED || previous == null ? 1 : previous.easingType;
        return event;
    }

    private static LineEvent previousEvent(EventLayer layer, EventType type, double beat) {
        LineEvent previous = null;
        for (LineEvent event : layer.events(type)) {
            if (event.endTime.toDouble() <= beat) previous = event;
            else break;
        }
        return previous;
    }

    private Note findNearestNote(float x, float y, float right, float bottom) {
        float centerY = timelineY(bottom);
        Note nearest = null;
        float nearestDistance = 42f * density;
        for (Note note : currentLine().notes) {
            float noteX = chartXToScreen(note.positionX, 0f, right);
            float startY = beatToY(note.startTime.toDouble(), centerY);
            float noteY = startY;
            if (note.type == NoteType.HOLD) {
                float endY = beatToY(note.endTime.toDouble(), centerY);
                noteY = clamp(y, Math.min(startY, endY), Math.max(startY, endY));
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

    private HoldHandleHit findHoldHandleAt(Note note, float x, float y,
                                           float right, float bottom) {
        if (note == null || note.type != NoteType.HOLD) return null;
        float centerY = timelineY(bottom);
        float handleX = chartXToScreen(note.positionX, 0f, right);
        float startY = beatToY(note.startTime.toDouble(), centerY);
        float endY = beatToY(note.endTime.toDouble(), centerY);
        float radius = 22f * density;
        float startDistance = distance(x, y, handleX, startY);
        float endDistance = distance(x, y, handleX, endY);
        float best = Math.min(startDistance, endDistance);
        return best <= radius ? new HoldHandleHit(startDistance <= endDistance) : null;
    }

    private void beginNoteDrag(Note primary, float x, float y,
                               float right, float bottom) {
        clearNoteTouchState();
        draggedNotes.addAll(selectedNotesForClipboard());
        if (draggedNotes.isEmpty() && primary != null) draggedNotes.add(primary);
        for (Note note : draggedNotes) noteDragBefore.add(note.copy());
        noteDragAnchorBeat = BeatTime.fromDouble(
                snapBeat(yToBeat(y, timelineY(bottom))), subdivision);
        noteDragAnchorX = screenToChartX(x, 0f, right);
        noteDragDeltaBeat = BeatTime.zero();
        noteDragDeltaX = 0.0;
        noteDragValidation = NoteTouchOperation.Error.NONE;
        gestureMode = GestureMode.NOTE_DRAG;
    }

    private void updateNoteDragCandidate(float x, float y, float right, float bottom) {
        if (draggedNotes.isEmpty() || noteDragAnchorBeat == null) return;
        BeatTime pointerBeat = BeatTime.fromDouble(
                snapBeat(yToBeat(y, timelineY(bottom))), subdivision);
        noteDragDeltaBeat = noteMoveMode.constrainBeatDelta(
                pointerBeat.minus(noteDragAnchorBeat));
        int primaryIndex = Math.max(0, draggedNotes.indexOf(selectedNote));
        Note primaryBefore = noteDragBefore.get(primaryIndex);
        double rawDelta = screenToChartX(x, 0f, right) - noteDragAnchorX;
        double targetX = snapChartX(primaryBefore.positionX + rawDelta);
        noteDragDeltaX = noteMoveMode.constrainXDelta(
                targetX - primaryBefore.positionX);
        noteDragValidation = NoteTouchOperation.move(currentLine(), draggedNotes,
                noteDragDeltaBeat, noteDragDeltaX).error;
    }

    private void finishNoteDrag() {
        NoteTouchOperation.Result result = NoteTouchOperation.move(
                currentLine(), draggedNotes, noteDragDeltaBeat, noteDragDeltaX);
        noteDragValidation = result.error;
        if (result.error == NoteTouchOperation.Error.NONE) {
            history.execute(result.command);
            clearSelection();
            selectedNotes.addAll(result.notes);
            selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
            activateEditPanel();
        } else if (callback != null) {
            callback.showMessage(noteTouchErrorMessage(result.error));
        }
        clearNoteTouchState();
    }

    private void beginHoldResize(Note note, boolean startHandle) {
        clearNoteTouchState();
        resizedHold = note;
        holdBeforeStart = note.startTime;
        holdBeforeEnd = note.endTime;
        holdCandidateStart = note.startTime;
        holdCandidateEnd = note.endTime;
        noteDragValidation = NoteTouchOperation.Error.NONE;
        gestureMode = startHandle ? GestureMode.HOLD_DRAG_START : GestureMode.HOLD_DRAG_END;
    }

    private void updateHoldResizeCandidate(float y, float bottom) {
        if (resizedHold == null) return;
        BeatTime candidate = BeatTime.fromDouble(
                snapBeat(yToBeat(y, timelineY(bottom))), subdivision);
        if (gestureMode == GestureMode.HOLD_DRAG_START) holdCandidateStart = candidate;
        else holdCandidateEnd = candidate;
        noteDragValidation = NoteTouchOperation.resizeHold(currentLine(), resizedHold,
                holdCandidateStart, holdCandidateEnd).error;
    }

    private void finishHoldResize() {
        NoteTouchOperation.Result result = NoteTouchOperation.resizeHold(
                currentLine(), resizedHold, holdCandidateStart, holdCandidateEnd);
        noteDragValidation = result.error;
        if (result.error == NoteTouchOperation.Error.NONE) {
            history.execute(result.command);
            clearSelection();
            selectedNotes.addAll(result.notes);
            selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
            activateEditPanel();
        } else if (callback != null) {
            callback.showMessage(noteTouchErrorMessage(result.error));
        }
        clearNoteTouchState();
    }

    private boolean isHoldResizeGesture() {
        return gestureMode == GestureMode.HOLD_DRAG_START
                || gestureMode == GestureMode.HOLD_DRAG_END;
    }

    private void clearNoteTouchState() {
        draggedNotes.clear();
        noteDragBefore.clear();
        noteDragAnchorBeat = null;
        noteDragAnchorX = 0.0;
        noteDragDeltaBeat = BeatTime.zero();
        noteDragDeltaX = 0.0;
        noteDragValidation = NoteTouchOperation.Error.NONE;
        resizedHold = null;
        holdBeforeStart = null;
        holdBeforeEnd = null;
        holdCandidateStart = null;
        holdCandidateEnd = null;
    }

    private String noteTouchErrorMessage(NoteTouchOperation.Error error) {
        switch (error) {
            case NEGATIVE_START_TIME:
                return getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return getResources().getString(R.string.validation_end_after_start);
            case X_OUT_OF_RANGE:
                return getResources().getString(R.string.validation_note_x);
            case TARGET_NOT_FOUND:
                return getResources().getString(R.string.validation_target_changed);
            default:
                return getResources().getString(R.string.note_touch_invalid);
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private LineEvent findEventAt(float x, float y, float split, float bottom) {
        EventType type = eventTypeAtX(x, split);
        float centerY = timelineY(bottom);
        float beat = (float) yToBeat(y, centerY);
        LineEvent nearest = null;
        double bestDistance = 0.18;
        for (LineEvent event : currentLayer().events(type)) {
            double start = event.startTime.toDouble();
            double end = event.endTime.toDouble();
            if (beat >= start && beat <= end) return event;
            double distance = Math.min(Math.abs(beat - start), Math.abs(beat - end));
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = event;
            }
        }
        return nearest;
    }

    private EventHandleHit findEventHandleAt(float x, float y, float split, float bottom) {
        if (!isEditableEventLayer(eventLayerIndex)) return null;
        EventType type = eventTypeAtX(x, split);
        float centerY = timelineY(bottom);
        float columnWidth = Math.max(1f, (getWidth() - split) / EventType.values().length);
        float handleX = split + (type.ordinal() + 0.5f) * columnWidth;
        float touchRadius = Math.min(26f * density, columnWidth * 0.48f);
        EventHandleHit nearest = null;
        float bestDistance = touchRadius;
        for (LineEvent event : currentLayer().events(type)) {
            float startY = beatToY(event.startTime.toDouble(), centerY);
            float endY = beatToY(event.endTime.toDouble(), centerY);
            float dx = x - handleX;
            float startDy = y - startY;
            float endDy = y - endY;
            float startDistance = (float) Math.sqrt(dx * dx + startDy * startDy);
            float endDistance = (float) Math.sqrt(dx * dx + endDy * endDy);
            boolean startHandle = startDistance <= endDistance;
            float distance = startHandle ? startDistance : endDistance;
            if (event == selectedEvent) distance -= 0.5f * density;
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = new EventHandleHit(event, startHandle);
            }
        }
        return nearest;
    }

    private void beginEventDrag(LineEvent event, boolean startHandle) {
        EventLayer layer = currentLayer();
        if (!isEditableEventLayer(eventLayerIndex)
                || event == null || !layer.events(event.type).contains(event)) {
            if (callback != null) callback.showMessage(getResources().getString(R.string.validation_target_changed));
            gestureMode = GestureMode.NONE;
            return;
        }
        LineEvent pair = null;
        if (xyBindingEnabled && XYBindingValidator.isMoveType(event.type)) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, event);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                if (callback != null) callback.showMessage(xyBindingErrorMessage(lookup.error));
                gestureMode = GestureMode.NONE;
                return;
            }
            pair = lookup.event;
        }
        draggedEvent = event;
        draggedEventPair = pair;
        draggedEventLayer = layer;
        dragBeforeStart = event.startTime;
        dragBeforeEnd = event.endTime;
        dragPairBeforeStart = pair == null ? null : pair.startTime;
        dragPairBeforeEnd = pair == null ? null : pair.endTime;
        dragCandidateStart = event.startTime;
        dragCandidateEnd = event.endTime;
        dragValidation = EventDragValidator.Error.NONE;
        xyDragValidation = XYBindingValidator.Error.NONE;
        eventDragPointerMoved = false;
        gestureMode = startHandle ? GestureMode.EVENT_DRAG_START : GestureMode.EVENT_DRAG_END;
    }

    private void updateEventDragCandidate(float y, float bottom) {
        if (draggedEvent == null || draggedEventLayer == null) return;
        double baseBeat = gestureMode == GestureMode.EVENT_DRAG_START
                ? dragBeforeStart.toDouble() : dragBeforeEnd.toDouble();
        double adjustedBeat = baseBeat - (y - gestureStartY) / pixelsPerBeat
                * settings.eventScrollSpeed;
        BeatTime snapped = BeatTime.fromDouble(snapBeat(adjustedBeat), subdivision);
        if (gestureMode == GestureMode.EVENT_DRAG_START) dragCandidateStart = snapped;
        else if (gestureMode == GestureMode.EVENT_DRAG_END) dragCandidateEnd = snapped;
        if (draggedEventPair == null) {
            dragValidation = EventDragValidator.validate(draggedEventLayer, eventLayerIndex,
                    draggedEvent, dragCandidateStart, dragCandidateEnd);
            xyDragValidation = XYBindingValidator.Error.NONE;
        } else {
            dragValidation = EventDragValidator.Error.NONE;
            xyDragValidation = XYBindingValidator.validatePairedTimes(
                    draggedEventLayer, eventLayerIndex, draggedEvent, draggedEventPair,
                    dragCandidateStart, dragCandidateEnd);
        }
    }

    private void finishEventDrag() {
        if (draggedEvent == null || draggedEventLayer == null
                || dragCandidateStart == null || dragCandidateEnd == null) {
            clearEventDragState();
            return;
        }
        boolean changed = !dragBeforeStart.equals(dragCandidateStart)
                || !dragBeforeEnd.equals(dragCandidateEnd);
        if (draggedEventPair == null) {
            EventDragValidator.Error validation = EventDragValidator.validate(
                    draggedEventLayer, eventLayerIndex, draggedEvent,
                    dragCandidateStart, dragCandidateEnd);
            dragValidation = validation;
            if (validation == EventDragValidator.Error.NONE && changed) {
                history.execute(EventDragCommand.move(draggedEventLayer, draggedEvent,
                        dragBeforeStart, dragBeforeEnd,
                        dragCandidateStart, dragCandidateEnd));
                selectedEvent = draggedEvent;
                selectedNote = null;
            } else if (validation != EventDragValidator.Error.NONE && callback != null) {
                callback.showMessage(eventDragErrorMessage(validation));
            }
        } else {
            XYBindingValidator.Error validation = XYBindingValidator.validatePairedTimes(
                    draggedEventLayer, eventLayerIndex, draggedEvent, draggedEventPair,
                    dragCandidateStart, dragCandidateEnd);
            xyDragValidation = validation;
            if (validation == XYBindingValidator.Error.NONE && changed) {
                history.execute(XYBindingCommand.move(
                        draggedEventLayer,
                        draggedEvent, dragBeforeStart, dragBeforeEnd,
                        draggedEventPair, dragPairBeforeStart, dragPairBeforeEnd,
                        dragCandidateStart, dragCandidateEnd));
                selectedEvent = draggedEvent;
                selectedNote = null;
            } else if (validation != XYBindingValidator.Error.NONE && callback != null) {
                callback.showMessage(xyBindingErrorMessage(validation));
            }
        }
        clearEventDragState();
    }

    private String eventDragErrorMessage(EventDragValidator.Error error) {
        switch (error) {
            case NEGATIVE_START_TIME:
                return getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return getResources().getString(R.string.validation_end_after_start);
            case EVENT_OVERLAP:
                return getResources().getString(R.string.validation_event_overlap);
            case RESERVED_LAYER:
                return getResources().getString(R.string.validation_event_reserved_layer);
            case TARGET_NOT_FOUND:
            default:
                return getResources().getString(R.string.validation_target_changed);
        }
    }

    private boolean isEventDragGesture() {
        return gestureMode == GestureMode.EVENT_DRAG_START
                || gestureMode == GestureMode.EVENT_DRAG_END;
    }

    @Override
    protected void clearEventDragState() {
        draggedEvent = null;
        draggedEventPair = null;
        draggedEventLayer = null;
        dragBeforeStart = null;
        dragBeforeEnd = null;
        dragPairBeforeStart = null;
        dragPairBeforeEnd = null;
        dragCandidateStart = null;
        dragCandidateEnd = null;
        dragValidation = EventDragValidator.Error.NONE;
        xyDragValidation = XYBindingValidator.Error.NONE;
        eventDragPointerMoved = false;
    }

    @Override
    protected void deleteSelection() {
        if (selectionCount() > 1) {
            deleteMultipleSelection();
            return;
        }
        if (selectedNote != null) {
            JudgeLine line = currentLine();
            Note note = selectedNote;
            int originalIndex = line.notes.indexOf(note);
            history.execute(new EditHistory.Command() {
                @Override
                public void apply() {
                    line.notes.remove(note);
                    selectedNotes.remove(note);
                    if (selectedNote == note) selectedNote = null;
                }

                @Override
                public void revert() {
                    int index = Math.max(0, Math.min(originalIndex, line.notes.size()));
                    line.notes.add(index, note);
                    selectedNote = note;
                    selectedNotes.add(note);
                    selectedEvent = null;
                }
            });
        } else if (selectedEvent != null) {
            EventLayer layer = currentLayer();
            LineEvent event = selectedEvent;
            if (!isEditableEventLayer(eventLayerIndex)) {
                if (callback != null) callback.showMessage(
                        getResources().getString(R.string.validation_event_reserved_layer));
                return;
            }
            if (xyBindingEnabled && XYBindingValidator.isMoveType(event.type)) {
                XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, event);
                if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                    if (callback != null) callback.showMessage(xyBindingErrorMessage(lookup.error));
                    return;
                }
                LineEvent pair = lookup.event;
                history.execute(new EditHistory.Command() {
                    private final EditHistory.Command command =
                            XYBindingCommand.delete(layer, event, pair);

                    @Override
                    public void apply() {
                        command.apply();
                        selectedEvents.remove(event);
                        selectedEvents.remove(pair);
                        if (selectedEvent == event || selectedEvent == pair) selectedEvent = null;
                    }

                    @Override
                    public void revert() {
                        command.revert();
                        selectedEvent = event;
                        selectedEvents.add(event);
                        selectedEvents.add(pair);
                        selectedNote = null;
                    }
                });
            } else {
                List<LineEvent> list = layer.events(event.type);
                int originalIndex = list.indexOf(event);
                history.execute(new EditHistory.Command() {
                    @Override
                    public void apply() {
                        list.remove(event);
                        selectedEvents.remove(event);
                        if (selectedEvent == event) selectedEvent = null;
                    }

                    @Override
                    public void revert() {
                        int index = Math.max(0, Math.min(originalIndex, list.size()));
                        list.add(index, event);
                        selectedEvent = event;
                        selectedEvents.add(event);
                        selectedNote = null;
                    }
                });
            }
        }
        invalidate();
    }

    @Override
    protected void mirrorSelectedNote() {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.positionX = -note.positionX);
    }

    @Override
    protected void switchSelectedNoteSide() {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.above = note.above == 1 ? 0 : 1);
    }

    @Override
    protected void toggleSelectedNoteFake() {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.fake = !note.fake);
    }

    @Override
    protected void resizeSelectedNote(double delta) {
        if (selectedNote == null) return;
        mutateSelectedNote(note -> note.size = Math.max(0.1, Math.min(8.0, note.size + delta)));
    }

    private void mutateSelectedNote(NoteMutation mutation) {
        Note target = selectedNote;
        Note before = target.copy();
        Note after = target.copy();
        mutation.apply(after);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                copyNoteFields(after, target);
                selectedNote = target;
            }

            @Override
            public void revert() {
                copyNoteFields(before, target);
                selectedNote = target;
            }
        });
        invalidate();
    }

    @Override
    protected void adjustSelectedEventValue(boolean startValue, double delta) {
        if (selectedEvent == null) return;
        mutateSelectedEvent(event -> {
            if (startValue) event.start = normalizeEventValue(event.type, event.start + delta);
            else event.end = normalizeEventValue(event.type, event.end + delta);
        });
    }

    @Override
    protected void toggleSelectedEventLink() {
        if (selectedEvent == null) return;
        mutateSelectedEvent(event -> event.linkGroup = event.linkGroup == 0 ? 1 : 0);
    }

    @Override
    protected void splitSelectedEvent() {
        if (selectedEvent == null) return;
        EventLayer layer = currentLayer();
        LineEvent target = selectedEvent;
        boolean snapped = settings.splitSnapToGrid;
        BeatTime cutTime = BeatTime.fromDouble(
                snapped ? snapBeat(currentBeat) : Math.max(0.0, currentBeat),
                snapped ? subdivision : FREE_SPLIT_DIVISION);
        EventCutGlueValidator.Error validation = EventCutGlueValidator.validateCut(
                layer, eventLayerIndex, target, cutTime);
        if (validation != EventCutGlueValidator.Error.NONE) {
            if (callback != null) callback.showMessage(eventCutGlueErrorMessage(validation));
            return;
        }

        EventCutCommand.CutOperation operation = EventCutCommand.cut(layer, target, cutTime);
        EventCutCommand.CutOperation pairOperation = null;
        if (xyBindingEnabled && XYBindingValidator.isMoveType(target.type)) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, target);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                if (callback != null) callback.showMessage(xyBindingErrorMessage(lookup.error));
                return;
            }
            EventCutGlueValidator.Error pairValidation = EventCutGlueValidator.validateCut(
                    layer, eventLayerIndex, lookup.event, cutTime);
            if (pairValidation != EventCutGlueValidator.Error.NONE) {
                if (callback != null) callback.showMessage(eventCutGlueErrorMessage(pairValidation));
                return;
            }
            pairOperation = EventCutCommand.cut(layer, lookup.event, cutTime);
        }

        EventCutCommand.CutOperation finalPairOperation = pairOperation;
        EditHistory.Command command = finalPairOperation == null
                ? operation
                : XYBindingCommand.cut(operation, finalPairOperation);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                selectedNotes.clear();
                selectedEvents.clear();
                selectedEvents.add(operation.rightEvent());
                selectedEvent = operation.rightEvent();
                selectedNote = null;
            }

            @Override
            public void revert() {
                command.revert();
                selectedNotes.clear();
                selectedEvents.clear();
                selectedEvents.add(target);
                selectedEvent = target;
                selectedNote = null;
            }
        });
        if (callback != null) {
            int message;
            if (finalPairOperation == null) {
                message = snapped ? R.string.event_split_applied_grid
                        : R.string.event_split_applied_exact;
            } else {
                message = snapped ? R.string.event_split_xy_applied_grid
                        : R.string.event_split_xy_applied_exact;
            }
            callback.showMessage(getResources().getString(message));
        }
        invalidate();
    }

    @Override
    protected void passSelectedEvent() {
        if (selectedEvent == null) return;
        LineEvent target = selectedEvent;
        EventAdvancedOperation.Result result = EventAdvancedOperation.pass(currentLayer(), target);
        if (result.error != EventAdvancedOperation.Error.NONE) {
            if (callback != null) callback.showMessage(getResources().getString(
                    result.error == EventAdvancedOperation.Error.NOT_ENOUGH_PREVIOUS_EVENTS
                            ? R.string.event_pass_needs_two
                            : result.error == EventAdvancedOperation.Error.INVALID_RESULT
                            ? R.string.event_pass_invalid : R.string.validation_target_changed));
            return;
        }
        history.execute(result.command);
        selectedEvent = target;
        selectedEvents.add(target);
        if (callback != null) callback.showMessage(getResources().getString(
                R.string.event_pass_applied, compactValue(target.start), compactValue(target.end)));
        invalidate();
    }

    @Override
    protected void randomizeSelectedEvent() {
        if (selectedEvent == null) return;
        LineEvent target = selectedEvent;
        EventAdvancedOperation.Result result = EventAdvancedOperation.randomize(
                currentLayer(), target, Math.random());
        if (result.error != EventAdvancedOperation.Error.NONE) {
            if (callback != null) callback.showMessage(getResources().getString(
                    result.error == EventAdvancedOperation.Error.INVALID_RESULT
                            ? R.string.event_rand_invalid : R.string.validation_target_changed));
            return;
        }
        history.execute(result.command);
        selectedEvent = target;
        selectedEvents.add(target);
        if (callback != null) callback.showMessage(getResources().getString(
                R.string.event_rand_applied, compactValue(result.endValue)));
        invalidate();
    }

    @Override
    protected void glueSelectedEvent() {
        if (selectedEvent == null) return;
        EventLayer layer = currentLayer();
        LineEvent target = selectedEvent;
        EventCutGlueValidator.Error validation = EventCutGlueValidator.validateGlue(
                layer, eventLayerIndex, target);
        if (validation != EventCutGlueValidator.Error.NONE) {
            if (callback != null) callback.showMessage(eventCutGlueErrorMessage(validation));
            return;
        }
        LineEvent previous = EventCutGlueValidator.previousSameType(layer, target);
        if (previous == null) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.validation_event_no_previous));
            return;
        }
        if (Double.compare(previous.end, target.start) == 0) {
            if (callback != null) callback.showMessage(
                    getResources().getString(R.string.event_glue_no_change));
            return;
        }
        history.execute(EventGlueCommand.glue(previous, target));
        selectedEvent = target;
        selectedNote = null;
        if (callback != null) callback.showMessage(
                getResources().getString(R.string.event_glue_applied));
        invalidate();
    }

    private String xyBindingErrorMessage(XYBindingValidator.Error error) {
        switch (error) {
            case RESERVED_LAYER:
                return getResources().getString(R.string.validation_event_reserved_layer);
            case NEGATIVE_START_TIME:
                return getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return getResources().getString(R.string.validation_end_after_start);
            case EVENT_OVERLAP:
                return getResources().getString(R.string.validation_event_overlap);
            case PAIR_NOT_FOUND:
            case AMBIGUOUS_PAIR:
            case LAYER_NOT_SYNCHRONIZED:
                return getResources().getString(R.string.validation_xy_binding_pair);
            case NOT_MOVE_EVENT:
            case TARGET_NOT_FOUND:
            default:
                return getResources().getString(R.string.validation_target_changed);
        }
    }

    private String eventCutGlueErrorMessage(EventCutGlueValidator.Error error) {
        switch (error) {
            case RESERVED_LAYER:
                return getResources().getString(R.string.validation_event_reserved_layer);
            case INVALID_EVENT_TIME:
                return getResources().getString(R.string.validation_event_invalid_time);
            case CUT_NOT_INSIDE_EVENT:
                return getResources().getString(R.string.validation_event_cut_inside);
            case EVENT_OVERLAP:
                return getResources().getString(R.string.validation_event_overlap);
            case NO_PREVIOUS_EVENT:
                return getResources().getString(R.string.validation_event_no_previous);
            case TARGET_NOT_FOUND:
            default:
                return getResources().getString(R.string.validation_target_changed);
        }
    }

    private void mutateSelectedEvent(EventMutation mutation) {
        LineEvent target = selectedEvent;
        LineEvent before = target.copy();
        LineEvent after = target.copy();
        mutation.apply(after);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                copyEventFields(after, target);
                selectedEvent = target;
            }

            @Override
            public void revert() {
                copyEventFields(before, target);
                selectedEvent = target;
            }
        });
        invalidate();
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
    }

    private boolean addSelectionAt(float x, float y, float split, float bottom) {
        if (x < split) {
            Note note = findNearestNote(x, y, split, bottom);
            if (note == null) return false;
            selectedNotes.add(note);
        } else {
            LineEvent event = findEventAt(x, y, split, bottom);
            if (event == null) return false;
            updateRectangleEventSelection(event, true);
        }
        selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
        selectedEvent = selectedEvents.isEmpty() ? null : selectedEvents.iterator().next();
        if (selectedNote != null) selectedEvent = null;
        activateEditPanel();
        return true;
    }

    private void applyRectangleSelection(RectF rawBounds, float split, float bottom) {
        RectF bounds = normalized(rawBounds);
        float centerY = timelineY(bottom);
        boolean add = rectangleSelectionMode == RectangleSelectionMode.ADD;
        if (bounds.left < split) {
            for (Note note : currentLine().notes) {
                float x = chartXToScreen(note.positionX, 0f, split);
                float startY = beatToY(note.startTime.toDouble(), centerY);
                float endY = note.type == NoteType.HOLD
                        ? beatToY(note.endTime.toDouble(), centerY) : startY;
                if (x >= bounds.left && x <= bounds.right
                        && Math.max(Math.min(startY, endY), bounds.top)
                        <= Math.min(Math.max(startY, endY), bounds.bottom)) {
                    if (add) selectedNotes.add(note); else selectedNotes.remove(note);
                }
            }
        }
        if (bounds.right >= split) {
            float columnWidth = Math.max(1f, (getWidth() - split) / EventType.values().length);
            for (EventType type : EventType.values()) {
                float left = split + type.ordinal() * columnWidth;
                float right = left + columnWidth;
                if (right < bounds.left || left > bounds.right) continue;
                for (LineEvent event : currentLayer().events(type)) {
                    float startY = beatToY(event.startTime.toDouble(), centerY);
                    float endY = beatToY(event.endTime.toDouble(), centerY);
                    if (Math.max(Math.min(startY, endY), bounds.top)
                            <= Math.min(Math.max(startY, endY), bounds.bottom)) {
                        updateRectangleEventSelection(event, add);
                    }
                }
            }
        }
        selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
        selectedEvent = selectedEvents.isEmpty() ? null : selectedEvents.iterator().next();
        if (selectedNote != null) selectedEvent = null;
    }

    private void updateRectangleEventSelection(LineEvent event, boolean add) {
        if (add) selectedEvents.add(event); else selectedEvents.remove(event);
        if (!xyBindingEnabled || !XYBindingValidator.isMoveType(event.type)) return;
        XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(currentLayer(), event);
        if (pair.error == XYBindingValidator.Error.NONE && pair.event != null) {
            if (add) selectedEvents.add(pair.event); else selectedEvents.remove(pair.event);
        }
    }

    @Override
    protected void copySelection() {
        clipboard = ChartClipboard.copy(selectedNotesForClipboard(), selectedEventsForClipboard());
        if (clipboard.isEmpty()) {
            if (callback != null) callback.showMessage(getResources().getString(R.string.clipboard_empty_selection));
            return;
        }
        if (callback != null) callback.showMessage(getResources().getString(
                R.string.clipboard_copied, clipboard.noteCount(), clipboard.eventCount()));
        invalidate();
    }

    private void maybeAutoCopySelection() {
        if (!settings.autoMoveToClipboard || selectionCount() <= 1) return;
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(
                selectedNotesForClipboard(), selectedEventsForClipboard());
        if (!snapshot.isEmpty()) clipboard = snapshot;
    }

    @Override
    protected void cutSelection() {
        List<Note> notes = selectedNotesForClipboard();
        List<LineEvent> events = selectedEventsForClipboard();
        ChartClipboard.Snapshot snapshot = ChartClipboard.copy(notes, events);
        ChartClipboard.Operation operation = ChartClipboard.prepareCut(
                currentLine(), currentLayer(), notes, events, xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            showClipboardError(operation.error);
            return;
        }
        clipboard = snapshot;
        executeRemovalOperation(operation);
        if (callback != null) callback.showMessage(getResources().getString(R.string.clipboard_cut));
    }

    private void deleteMultipleSelection() {
        ChartClipboard.Operation operation = ChartClipboard.prepareCut(currentLine(), currentLayer(),
                selectedNotesForClipboard(), selectedEventsForClipboard(), xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            showClipboardError(operation.error);
            return;
        }
        executeRemovalOperation(operation);
    }

    private void executeRemovalOperation(ChartClipboard.Operation operation) {
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                operation.command.apply();
                clearSelection();
            }

            @Override
            public void revert() {
                operation.command.revert();
                selectedNotes.addAll(operation.notes);
                selectedEvents.addAll(operation.events);
                selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
                selectedEvent = selectedNote == null && !selectedEvents.isEmpty()
                        ? selectedEvents.iterator().next() : null;
            }
        });
        invalidate();
    }

    @Override
    protected void pasteClipboard(boolean mirrorNotes) {
        BeatTime anchor = BeatTime.fromDouble(Math.max(0.0, snapBeat(currentBeat)), subdivision);
        ChartClipboard.Operation operation = ChartClipboard.preparePaste(clipboard,
                currentLine(), currentLayer(), eventLayerIndex, anchor, mirrorNotes, xyBindingEnabled);
        if (operation.error != ChartClipboard.Error.NONE) {
            showClipboardError(operation.error);
            return;
        }
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                operation.command.apply();
                clearSelection();
                selectedNotes.addAll(operation.notes);
                selectedEvents.addAll(operation.events);
                selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.iterator().next();
                selectedEvent = selectedNote == null && !selectedEvents.isEmpty()
                        ? selectedEvents.iterator().next() : null;
            }

            @Override
            public void revert() {
                operation.command.revert();
                clearSelection();
            }
        });
        if (callback != null) callback.showMessage(getResources().getString(R.string.clipboard_pasted));
        invalidate();
    }

    @Override
    protected List<Note> selectedNotesForClipboard() {
        List<Note> result = new ArrayList<>();
        for (Note note : selectedNotes) if (currentLine().notes.contains(note)) result.add(note);
        if (selectedNote != null && currentLine().notes.contains(selectedNote)
                && !result.contains(selectedNote)) result.add(selectedNote);
        return result;
    }

    @Override
    protected List<LineEvent> selectedEventsForClipboard() {
        List<LineEvent> result = new ArrayList<>();
        for (LineEvent event : selectedEvents) {
            if (currentLayer().events(event.type).contains(event)) result.add(event);
        }
        if (selectedEvent != null && currentLayer().events(selectedEvent.type).contains(selectedEvent)
                && !result.contains(selectedEvent)) result.add(selectedEvent);
        if (xyBindingEnabled) {
            List<LineEvent> initiallySelected = new ArrayList<>(result);
            for (LineEvent event : initiallySelected) {
                if (!XYBindingValidator.isMoveType(event.type)) continue;
                XYBindingValidator.PairLookup pair = XYBindingValidator.findPair(currentLayer(), event);
                if (pair.error == XYBindingValidator.Error.NONE && pair.event != null
                        && !result.contains(pair.event)) result.add(pair.event);
            }
        }
        return result;
    }

    private void showClipboardError(ChartClipboard.Error error) {
        int message;
        switch (error) {
            case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
            case RESERVED_LAYER: message = R.string.validation_event_reserved_layer; break;
            case XY_PAIR_REQUIRED: message = R.string.clipboard_xy_pair_required; break;
            case EMPTY: message = R.string.clipboard_empty_selection; break;
            default: message = R.string.clipboard_invalid; break;
        }
        if (callback != null) callback.showMessage(getResources().getString(message));
    }

    private interface NoteMutation {
        void apply(Note note);
    }

    private interface EventMutation {
        void apply(LineEvent event);
    }

    private static final class EventHandleHit {
        final LineEvent event;
        final boolean startHandle;

        EventHandleHit(LineEvent event, boolean startHandle) {
            this.event = event;
            this.startHandle = startHandle;
        }
    }

    private static final class HoldHandleHit {
        final boolean startHandle;

        HoldHandleHit(boolean startHandle) {
            this.startHandle = startHandle;
        }
    }

}
