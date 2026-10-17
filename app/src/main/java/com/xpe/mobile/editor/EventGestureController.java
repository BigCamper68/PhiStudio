package com.xpe.mobile.editor;

import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorSurfaceView.GestureMode;
import com.xpe.mobile.editor.EditorView.EventHandleHit;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

/** EventGestureController responsibilities extracted from EditorView. */
final class EventGestureController {
    private final EditorView delegateOwner;

    EventGestureController(EditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void addEvent(EventType type, double startBeat, double endBeat) {
        if (type == null) return;
        if (!EditorView.isEditableEventLayer(delegateOwner.eventLayerIndex)) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                    delegateOwner.getResources().getString(R.string.validation_event_reserved_layer));
            return;
        }
        EventLayer layer = delegateOwner.currentLayer();
        LineEvent event = delegateOwner.eventGestureController.createEvent(layer, type, startBeat, endBeat);

        if (delegateOwner.xyBindingEnabled && XYBindingValidator.isMoveType(type)) {
            LineEvent pair = delegateOwner.eventGestureController.createEvent(layer, XYBindingValidator.pairedType(type),
                    startBeat, endBeat);
            XYBindingValidator.Error validation = XYBindingValidator.validatePlacement(
                    layer, delegateOwner.eventLayerIndex, event, pair);
            if (validation != XYBindingValidator.Error.NONE) {
                if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.xyBindingErrorMessage(validation));
                return;
            }
            delegateOwner.history.execute(new EditHistory.Command() {
                private final EditHistory.Command command = XYBindingCommand.add(layer, event, pair);

                @Override
                public void apply() {
                    command.apply();
                    delegateOwner.clearSelection();
                    delegateOwner.selectedEvent = event;
                    delegateOwner.selectedEvents.add(event);
                    delegateOwner.activateEditPanel();
                }

                @Override
                public void revert() {
                    command.revert();
                    delegateOwner.selectedEvents.remove(event);
                    delegateOwner.selectedEvents.remove(pair);
                    if (delegateOwner.selectedEvent == event || delegateOwner.selectedEvent == pair) delegateOwner.selectedEvent = null;
                }
            });
        } else {
            if (layer.overlaps(event, null)) {
                if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                        delegateOwner.getResources().getString(R.string.validation_event_overlap));
                return;
            }
            delegateOwner.history.execute(new EditHistory.Command() {
                @Override
                public void apply() {
                    if (!layer.events(type).contains(event)) layer.events(type).add(event);
                    layer.events(type).sort((first, second) ->
                            first.startTime.compareTo(second.startTime));
                    delegateOwner.clearSelection();
                    delegateOwner.selectedEvent = event;
                    delegateOwner.selectedEvents.add(event);
                    delegateOwner.activateEditPanel();
                }

                @Override
                public void revert() {
                    layer.events(type).remove(event);
                    delegateOwner.selectedEvents.remove(event);
                    if (delegateOwner.selectedEvent == event) delegateOwner.selectedEvent = null;
                }
            });
        }
        delegateOwner.invalidate();
    }

    LineEvent createEvent(EventLayer layer, EventType type,
                                  double startBeat, double endBeat) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = BeatTime.fromDouble(Math.max(0.0, startBeat), delegateOwner.subdivision);
        event.endTime = BeatTime.fromDouble(
                Math.max(startBeat + 1.0 / delegateOwner.subdivision, endBeat), delegateOwner.subdivision);
        LineEvent previous = EventGestureController.previousEvent(layer, type, event.startTime.toDouble());
        event.start = previous == null ? LineEvent.defaultValue(type) : previous.end;
        event.end = event.start;
        event.easingType = type == EventType.SPEED || previous == null ? 1 : previous.easingType;
        return event;
    }

    static LineEvent previousEvent(EventLayer layer, EventType type, double beat) {
        LineEvent previous = null;
        for (LineEvent event : layer.events(type)) {
            if (event.endTime.toDouble() <= beat) previous = event;
            else break;
        }
        return previous;
    }

    LineEvent findEventAt(float x, float y, float split, float bottom) {
        EventType type = delegateOwner.eventTypeAtX(x, split);
        float centerY = delegateOwner.timelineY(bottom);
        float beat = (float) delegateOwner.yToBeat(y, centerY);
        LineEvent nearest = null;
        double bestDistance = 0.18;
        for (LineEvent event : delegateOwner.currentLayer().events(type)) {
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

    EventHandleHit findEventHandleAt(float x, float y, float split, float bottom) {
        if (!EditorView.isEditableEventLayer(delegateOwner.eventLayerIndex)) return null;
        EventType type = delegateOwner.eventTypeAtX(x, split);
        float centerY = delegateOwner.timelineY(bottom);
        float columnWidth = Math.max(1f, (delegateOwner.getWidth() - split) / EventType.values().length);
        float handleX = split + (type.ordinal() + 0.5f) * columnWidth;
        float touchRadius = Math.min(26f * delegateOwner.density, columnWidth * 0.48f);
        EventHandleHit nearest = null;
        float bestDistance = touchRadius;
        for (LineEvent event : delegateOwner.currentLayer().events(type)) {
            float startY = delegateOwner.beatToY(event.startTime.toDouble(), centerY);
            float endY = delegateOwner.beatToY(event.endTime.toDouble(), centerY);
            float dx = x - handleX;
            float startDy = y - startY;
            float endDy = y - endY;
            float startDistance = (float) Math.sqrt(dx * dx + startDy * startDy);
            float endDistance = (float) Math.sqrt(dx * dx + endDy * endDy);
            boolean startHandle = startDistance <= endDistance;
            float distance = startHandle ? startDistance : endDistance;
            if (event == delegateOwner.selectedEvent) distance -= 0.5f * delegateOwner.density;
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = new EventHandleHit(event, startHandle);
            }
        }
        return nearest;
    }

    void beginEventDrag(LineEvent event, boolean startHandle) {
        EventLayer layer = delegateOwner.currentLayer();
        if (!EditorView.isEditableEventLayer(delegateOwner.eventLayerIndex)
                || event == null || !layer.events(event.type).contains(event)) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(R.string.validation_target_changed));
            delegateOwner.gestureMode = GestureMode.NONE;
            return;
        }
        LineEvent pair = null;
        if (delegateOwner.xyBindingEnabled && XYBindingValidator.isMoveType(event.type)) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, event);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.xyBindingErrorMessage(lookup.error));
                delegateOwner.gestureMode = GestureMode.NONE;
                return;
            }
            pair = lookup.event;
        }
        delegateOwner.draggedEvent = event;
        delegateOwner.draggedEventPair = pair;
        delegateOwner.draggedEventLayer = layer;
        delegateOwner.dragBeforeStart = event.startTime;
        delegateOwner.dragBeforeEnd = event.endTime;
        delegateOwner.dragPairBeforeStart = pair == null ? null : pair.startTime;
        delegateOwner.dragPairBeforeEnd = pair == null ? null : pair.endTime;
        delegateOwner.dragCandidateStart = event.startTime;
        delegateOwner.dragCandidateEnd = event.endTime;
        delegateOwner.dragValidation = EventDragValidator.Error.NONE;
        delegateOwner.xyDragValidation = XYBindingValidator.Error.NONE;
        delegateOwner.eventDragPointerMoved = false;
        delegateOwner.gestureMode = startHandle ? GestureMode.EVENT_DRAG_START : GestureMode.EVENT_DRAG_END;
    }

    void updateEventDragCandidate(float y, float bottom) {
        if (delegateOwner.draggedEvent == null || delegateOwner.draggedEventLayer == null) return;
        double baseBeat = delegateOwner.gestureMode == GestureMode.EVENT_DRAG_START
                ? delegateOwner.dragBeforeStart.toDouble() : delegateOwner.dragBeforeEnd.toDouble();
        double adjustedBeat = baseBeat - (y - delegateOwner.gestureStartY) / delegateOwner.pixelsPerBeat
                * delegateOwner.settings.eventScrollSpeed;
        BeatTime snapped = BeatTime.fromDouble(delegateOwner.snapBeat(adjustedBeat), delegateOwner.subdivision);
        if (delegateOwner.gestureMode == GestureMode.EVENT_DRAG_START) delegateOwner.dragCandidateStart = snapped;
        else if (delegateOwner.gestureMode == GestureMode.EVENT_DRAG_END) delegateOwner.dragCandidateEnd = snapped;
        if (delegateOwner.draggedEventPair == null) {
            delegateOwner.dragValidation = EventDragValidator.validate(delegateOwner.draggedEventLayer, delegateOwner.eventLayerIndex,
                    delegateOwner.draggedEvent, delegateOwner.dragCandidateStart, delegateOwner.dragCandidateEnd);
            delegateOwner.xyDragValidation = XYBindingValidator.Error.NONE;
        } else {
            delegateOwner.dragValidation = EventDragValidator.Error.NONE;
            delegateOwner.xyDragValidation = XYBindingValidator.validatePairedTimes(
                    delegateOwner.draggedEventLayer, delegateOwner.eventLayerIndex, delegateOwner.draggedEvent, delegateOwner.draggedEventPair,
                    delegateOwner.dragCandidateStart, delegateOwner.dragCandidateEnd);
        }
    }

    void finishEventDrag() {
        if (delegateOwner.draggedEvent == null || delegateOwner.draggedEventLayer == null
                || delegateOwner.dragCandidateStart == null || delegateOwner.dragCandidateEnd == null) {
            delegateOwner.clearEventDragState();
            return;
        }
        boolean changed = !delegateOwner.dragBeforeStart.equals(delegateOwner.dragCandidateStart)
                || !delegateOwner.dragBeforeEnd.equals(delegateOwner.dragCandidateEnd);
        if (delegateOwner.draggedEventPair == null) {
            EventDragValidator.Error validation = EventDragValidator.validate(
                    delegateOwner.draggedEventLayer, delegateOwner.eventLayerIndex, delegateOwner.draggedEvent,
                    delegateOwner.dragCandidateStart, delegateOwner.dragCandidateEnd);
            delegateOwner.dragValidation = validation;
            if (validation == EventDragValidator.Error.NONE && changed) {
                delegateOwner.history.execute(EventDragCommand.move(delegateOwner.draggedEventLayer, delegateOwner.draggedEvent,
                        delegateOwner.dragBeforeStart, delegateOwner.dragBeforeEnd,
                        delegateOwner.dragCandidateStart, delegateOwner.dragCandidateEnd));
                delegateOwner.selectedEvent = delegateOwner.draggedEvent;
                delegateOwner.selectedNote = null;
            } else if (validation != EventDragValidator.Error.NONE && delegateOwner.callback != null) {
                delegateOwner.callback.showMessage(delegateOwner.eventGestureController.eventDragErrorMessage(validation));
            }
        } else {
            XYBindingValidator.Error validation = XYBindingValidator.validatePairedTimes(
                    delegateOwner.draggedEventLayer, delegateOwner.eventLayerIndex, delegateOwner.draggedEvent, delegateOwner.draggedEventPair,
                    delegateOwner.dragCandidateStart, delegateOwner.dragCandidateEnd);
            delegateOwner.xyDragValidation = validation;
            if (validation == XYBindingValidator.Error.NONE && changed) {
                delegateOwner.history.execute(XYBindingCommand.move(
                        delegateOwner.draggedEventLayer,
                        delegateOwner.draggedEvent, delegateOwner.dragBeforeStart, delegateOwner.dragBeforeEnd,
                        delegateOwner.draggedEventPair, delegateOwner.dragPairBeforeStart, delegateOwner.dragPairBeforeEnd,
                        delegateOwner.dragCandidateStart, delegateOwner.dragCandidateEnd));
                delegateOwner.selectedEvent = delegateOwner.draggedEvent;
                delegateOwner.selectedNote = null;
            } else if (validation != XYBindingValidator.Error.NONE && delegateOwner.callback != null) {
                delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.xyBindingErrorMessage(validation));
            }
        }
        delegateOwner.clearEventDragState();
    }

    String eventDragErrorMessage(EventDragValidator.Error error) {
        switch (error) {
            case NEGATIVE_START_TIME:
                return delegateOwner.getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return delegateOwner.getResources().getString(R.string.validation_end_after_start);
            case EVENT_OVERLAP:
                return delegateOwner.getResources().getString(R.string.validation_event_overlap);
            case RESERVED_LAYER:
                return delegateOwner.getResources().getString(R.string.validation_event_reserved_layer);
            case TARGET_NOT_FOUND:
            default:
                return delegateOwner.getResources().getString(R.string.validation_target_changed);
        }
    }

    boolean isEventDragGesture() {
        return delegateOwner.gestureMode == GestureMode.EVENT_DRAG_START
                || delegateOwner.gestureMode == GestureMode.EVENT_DRAG_END;
    }

    void clearEventDragState() {
        delegateOwner.draggedEvent = null;
        delegateOwner.draggedEventPair = null;
        delegateOwner.draggedEventLayer = null;
        delegateOwner.dragBeforeStart = null;
        delegateOwner.dragBeforeEnd = null;
        delegateOwner.dragPairBeforeStart = null;
        delegateOwner.dragPairBeforeEnd = null;
        delegateOwner.dragCandidateStart = null;
        delegateOwner.dragCandidateEnd = null;
        delegateOwner.dragValidation = EventDragValidator.Error.NONE;
        delegateOwner.xyDragValidation = XYBindingValidator.Error.NONE;
        delegateOwner.eventDragPointerMoved = false;
    }
}
