package com.xpe.mobile.editor;

import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorView.EventMutation;
import com.xpe.mobile.editor.EditorView.NoteMutation;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import java.util.List;

/** EditorSelectionEdits responsibilities extracted from EditorView. */
final class EditorSelectionEdits {
    private final EditorView delegateOwner;

    EditorSelectionEdits(EditorView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void deleteSelection() {
        if (delegateOwner.workbenchTab == 1 && delegateOwner.storyboardTimeline.selected() != null) {
            delegateOwner.storyboardTimeline.delete(); return;
        }
        if (delegateOwner.selectionCount() > 1) {
            delegateOwner.editorClipboardController.deleteMultipleSelection();
            return;
        }
        if (delegateOwner.selectedNote != null) {
            JudgeLine line = delegateOwner.currentLine();
            Note note = delegateOwner.selectedNote;
            int originalIndex = line.notes.indexOf(note);
            delegateOwner.history.execute(new EditHistory.Command() {
                @Override
                public void apply() {
                    line.notes.remove(note);
                    delegateOwner.selectedNotes.remove(note);
                    if (delegateOwner.selectedNote == note) delegateOwner.selectedNote = null;
                }

                @Override
                public void revert() {
                    int index = Math.max(0, Math.min(originalIndex, line.notes.size()));
                    line.notes.add(index, note);
                    delegateOwner.selectedNote = note;
                    delegateOwner.selectedNotes.add(note);
                    delegateOwner.selectedEvent = null;
                }
            });
        } else if (delegateOwner.selectedEvent != null) {
            EventLayer layer = delegateOwner.currentLayer();
            LineEvent event = delegateOwner.selectedEvent;
            if (!EditorView.isEditableEventLayer(delegateOwner.eventLayerIndex)) {
                if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                        delegateOwner.getResources().getString(R.string.validation_event_reserved_layer));
                return;
            }
            if (delegateOwner.xyBindingEnabled && XYBindingValidator.isMoveType(event.type)) {
                XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, event);
                if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                    if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.xyBindingErrorMessage(lookup.error));
                    return;
                }
                LineEvent pair = lookup.event;
                delegateOwner.history.execute(new EditHistory.Command() {
                    private final EditHistory.Command command =
                            XYBindingCommand.delete(layer, event, pair);

                    @Override
                    public void apply() {
                        command.apply();
                        delegateOwner.selectedEvents.remove(event);
                        delegateOwner.selectedEvents.remove(pair);
                        if (delegateOwner.selectedEvent == event || delegateOwner.selectedEvent == pair) delegateOwner.selectedEvent = null;
                    }

                    @Override
                    public void revert() {
                        command.revert();
                        delegateOwner.selectedEvent = event;
                        delegateOwner.selectedEvents.add(event);
                        delegateOwner.selectedEvents.add(pair);
                        delegateOwner.selectedNote = null;
                    }
                });
            } else {
                List<LineEvent> list = layer.events(event.type);
                int originalIndex = list.indexOf(event);
                delegateOwner.history.execute(new EditHistory.Command() {
                    @Override
                    public void apply() {
                        list.remove(event);
                        delegateOwner.selectedEvents.remove(event);
                        if (delegateOwner.selectedEvent == event) delegateOwner.selectedEvent = null;
                    }

                    @Override
                    public void revert() {
                        int index = Math.max(0, Math.min(originalIndex, list.size()));
                        list.add(index, event);
                        delegateOwner.selectedEvent = event;
                        delegateOwner.selectedEvents.add(event);
                        delegateOwner.selectedNote = null;
                    }
                });
            }
        }
        delegateOwner.invalidate();
    }

    void mirrorSelectedNote() {
        if (delegateOwner.selectedNote == null) return;
        delegateOwner.editorSelectionEdits.mutateSelectedNote(note -> note.positionX = -note.positionX);
    }

    void switchSelectedNoteSide() {
        if (delegateOwner.selectedNote == null) return;
        delegateOwner.editorSelectionEdits.mutateSelectedNote(note -> note.above = note.above == 1 ? 0 : 1);
    }

    void toggleSelectedNoteFake() {
        if (delegateOwner.selectedNote == null) return;
        delegateOwner.editorSelectionEdits.mutateSelectedNote(note -> note.fake = !note.fake);
    }

    void resizeSelectedNote(double delta) {
        if (delegateOwner.selectedNote == null) return;
        delegateOwner.editorSelectionEdits.mutateSelectedNote(note -> note.size = Math.max(0.1, Math.min(8.0, note.size + delta)));
    }

    void mutateSelectedNote(NoteMutation mutation) {
        Note target = delegateOwner.selectedNote;
        Note before = target.copy();
        Note after = target.copy();
        mutation.apply(after);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                EditorView.copyNoteFields(after, target);
                delegateOwner.selectedNote = target;
            }

            @Override
            public void revert() {
                EditorView.copyNoteFields(before, target);
                delegateOwner.selectedNote = target;
            }
        });
        delegateOwner.invalidate();
    }

    void adjustSelectedEventValue(boolean startValue, double delta) {
        if (delegateOwner.selectedEvent == null) return;
        delegateOwner.editorSelectionEdits.mutateSelectedEvent(event -> {
            if (startValue) event.start = EditorView.normalizeEventValue(event.type, event.start + delta);
            else event.end = EditorView.normalizeEventValue(event.type, event.end + delta);
        });
    }

    void toggleSelectedEventLink() {
        if (delegateOwner.selectedEvent == null) return;
        delegateOwner.editorSelectionEdits.mutateSelectedEvent(event -> event.linkGroup = event.linkGroup == 0 ? 1 : 0);
    }

    void subdivideSelectedEvents() {
        List<LineEvent> originalEvents = delegateOwner.getSelectedEventsForBatch();
        List<Note> originalNotes = delegateOwner.getSelectedNotesForBatch();
        LineEvent previousEvent = delegateOwner.selectedEvent;
        Note previousNote = delegateOwner.selectedNote;
        int originalLine = delegateOwner.lineIndex;
        int originalLayer = delegateOwner.eventLayerIndex;
        EventSubdivisionOperation.Result result = EventSubdivisionOperation.prepare(
                delegateOwner.currentLayer(), delegateOwner.eventLayerIndex, originalEvents,
                delegateOwner.getCurrentBeatTime(), delegateOwner.subdivision,
                delegateOwner.xyBindingEnabled,
                delegateOwner.chart::beatToSeconds);
        if (result.error != EventSubdivisionOperation.Error.NONE) {
            int message;
            switch (result.error) {
                case TOO_MANY_SEGMENTS: message = R.string.event_subdivide_too_many; break;
                case NO_CUTS: message = R.string.event_subdivide_no_cuts; break;
                case UNSUPPORTED_CURVE: message = R.string.event_subdivide_unsupported; break;
                case XY_BINDING_INVALID: message = R.string.event_subdivide_xy; break;
                case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
                default: message = R.string.event_subdivide_invalid; break;
            }
            if (delegateOwner.callback != null) {
                delegateOwner.callback.showMessage(delegateOwner.getResources().getString(message));
            }
            return;
        }
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override public void apply() {
                result.command.apply();
                delegateOwner.lineIndex = originalLine;
                delegateOwner.eventLayerIndex = originalLayer;
                delegateOwner.clearSelection();
                delegateOwner.selectedEvents.addAll(result.events);
                delegateOwner.selectedEvent = result.events.get(0);
                delegateOwner.activateEditPanel();
            }

            @Override public void revert() {
                result.command.revert();
                delegateOwner.lineIndex = originalLine;
                delegateOwner.eventLayerIndex = originalLayer;
                delegateOwner.clearSelection();
                delegateOwner.selectedEvents.addAll(originalEvents);
                delegateOwner.selectedNotes.addAll(originalNotes);
                delegateOwner.selectedEvent = previousEvent;
                delegateOwner.selectedNote = previousNote;
                delegateOwner.activateEditPanel();
            }
        });
        if (delegateOwner.callback != null) {
            delegateOwner.callback.showMessage(delegateOwner.getResources().getQuantityString(
                    R.plurals.event_subdivide_applied, result.events.size(), result.sourceCount, result.events.size(),
                    result.step.toString()));
        }
        delegateOwner.invalidate();
    }

    void splitSelectedEvent() {
        if (delegateOwner.selectedEvent == null) return;
        EventLayer layer = delegateOwner.currentLayer();
        LineEvent target = delegateOwner.selectedEvent;
        boolean snapped = delegateOwner.settings.splitSnapToGrid;
        BeatTime cutTime = BeatTime.fromDouble(
                snapped ? delegateOwner.snapBeat(delegateOwner.currentBeat) : Math.max(0.0, delegateOwner.currentBeat),
                snapped ? delegateOwner.subdivision : EditorView.FREE_SPLIT_DIVISION);
        EventCutGlueValidator.Error validation = EventCutGlueValidator.validateCut(
                layer, delegateOwner.eventLayerIndex, target, cutTime);
        if (validation != EventCutGlueValidator.Error.NONE) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.eventCutGlueErrorMessage(validation));
            return;
        }

        EventCutCommand.CutOperation operation = EventCutCommand.cut(layer, target, cutTime);
        EventCutCommand.CutOperation pairOperation = null;
        if (delegateOwner.xyBindingEnabled && XYBindingValidator.isMoveType(target.type)) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, target);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.xyBindingErrorMessage(lookup.error));
                return;
            }
            EventCutGlueValidator.Error pairValidation = EventCutGlueValidator.validateCut(
                    layer, delegateOwner.eventLayerIndex, lookup.event, cutTime);
            if (pairValidation != EventCutGlueValidator.Error.NONE) {
                if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.eventCutGlueErrorMessage(pairValidation));
                return;
            }
            pairOperation = EventCutCommand.cut(layer, lookup.event, cutTime);
        }

        EventCutCommand.CutOperation finalPairOperation = pairOperation;
        EditHistory.Command command = finalPairOperation == null
                ? operation
                : XYBindingCommand.cut(operation, finalPairOperation);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                delegateOwner.selectedNotes.clear();
                delegateOwner.selectedEvents.clear();
                delegateOwner.selectedEvents.add(operation.rightEvent());
                delegateOwner.selectedEvent = operation.rightEvent();
                delegateOwner.selectedNote = null;
            }

            @Override
            public void revert() {
                command.revert();
                delegateOwner.selectedNotes.clear();
                delegateOwner.selectedEvents.clear();
                delegateOwner.selectedEvents.add(target);
                delegateOwner.selectedEvent = target;
                delegateOwner.selectedNote = null;
            }
        });
        if (delegateOwner.callback != null) {
            int message;
            if (finalPairOperation == null) {
                message = snapped ? R.string.event_split_applied_grid
                        : R.string.event_split_applied_exact;
            } else {
                message = snapped ? R.string.event_split_xy_applied_grid
                        : R.string.event_split_xy_applied_exact;
            }
            delegateOwner.callback.showMessage(delegateOwner.getResources().getString(message));
        }
        delegateOwner.invalidate();
    }

    void passSelectedEvent() {
        if (delegateOwner.selectedEvent == null) return;
        LineEvent target = delegateOwner.selectedEvent;
        EventAdvancedOperation.Result result = EventAdvancedOperation.pass(delegateOwner.currentLayer(), target);
        if (result.error != EventAdvancedOperation.Error.NONE) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(
                    result.error == EventAdvancedOperation.Error.NOT_ENOUGH_PREVIOUS_EVENTS
                            ? R.string.event_pass_needs_two
                            : result.error == EventAdvancedOperation.Error.INVALID_RESULT
                            ? R.string.event_pass_invalid : R.string.validation_target_changed));
            return;
        }
        delegateOwner.history.execute(result.command);
        delegateOwner.selectedEvent = target;
        delegateOwner.selectedEvents.add(target);
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(
                R.string.event_pass_applied, EditorView.compactValue(target.start), EditorView.compactValue(target.end)));
        delegateOwner.invalidate();
    }

    void randomizeSelectedEvent() {
        if (delegateOwner.selectedEvent == null) return;
        LineEvent target = delegateOwner.selectedEvent;
        EventAdvancedOperation.Result result = EventAdvancedOperation.randomize(
                delegateOwner.currentLayer(), target, Math.random());
        if (result.error != EventAdvancedOperation.Error.NONE) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(
                    result.error == EventAdvancedOperation.Error.INVALID_RESULT
                            ? R.string.event_rand_invalid : R.string.validation_target_changed));
            return;
        }
        delegateOwner.history.execute(result.command);
        delegateOwner.selectedEvent = target;
        delegateOwner.selectedEvents.add(target);
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.getResources().getString(
                R.string.event_rand_applied, EditorView.compactValue(result.endValue)));
        delegateOwner.invalidate();
    }

    void glueSelectedEvent() {
        if (delegateOwner.selectedEvent == null) return;
        EventLayer layer = delegateOwner.currentLayer();
        LineEvent target = delegateOwner.selectedEvent;
        EventCutGlueValidator.Error validation = EventCutGlueValidator.validateGlue(
                layer, delegateOwner.eventLayerIndex, target);
        if (validation != EventCutGlueValidator.Error.NONE) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(delegateOwner.editorSelectionEdits.eventCutGlueErrorMessage(validation));
            return;
        }
        LineEvent previous = EventCutGlueValidator.previousSameType(layer, target);
        if (previous == null) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                    delegateOwner.getResources().getString(R.string.validation_event_no_previous));
            return;
        }
        if (Double.compare(previous.end, target.start) == 0) {
            if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                    delegateOwner.getResources().getString(R.string.event_glue_no_change));
            return;
        }
        delegateOwner.history.execute(EventGlueCommand.glue(previous, target));
        delegateOwner.selectedEvent = target;
        delegateOwner.selectedNote = null;
        if (delegateOwner.callback != null) delegateOwner.callback.showMessage(
                delegateOwner.getResources().getString(R.string.event_glue_applied));
        delegateOwner.invalidate();
    }

    String xyBindingErrorMessage(XYBindingValidator.Error error) {
        switch (error) {
            case RESERVED_LAYER:
                return delegateOwner.getResources().getString(R.string.validation_event_reserved_layer);
            case NEGATIVE_START_TIME:
                return delegateOwner.getResources().getString(R.string.validation_negative_start);
            case END_TIME_NOT_AFTER_START:
                return delegateOwner.getResources().getString(R.string.validation_end_after_start);
            case EVENT_OVERLAP:
                return delegateOwner.getResources().getString(R.string.validation_event_overlap);
            case PAIR_NOT_FOUND:
            case AMBIGUOUS_PAIR:
            case LAYER_NOT_SYNCHRONIZED:
                return delegateOwner.getResources().getString(R.string.validation_xy_binding_pair);
            case NOT_MOVE_EVENT:
            case TARGET_NOT_FOUND:
            default:
                return delegateOwner.getResources().getString(R.string.validation_target_changed);
        }
    }

    String eventCutGlueErrorMessage(EventCutGlueValidator.Error error) {
        switch (error) {
            case RESERVED_LAYER:
                return delegateOwner.getResources().getString(R.string.validation_event_reserved_layer);
            case INVALID_EVENT_TIME:
                return delegateOwner.getResources().getString(R.string.validation_event_invalid_time);
            case CUT_NOT_INSIDE_EVENT:
                return delegateOwner.getResources().getString(R.string.validation_event_cut_inside);
            case EVENT_OVERLAP:
                return delegateOwner.getResources().getString(R.string.validation_event_overlap);
            case NO_PREVIOUS_EVENT:
                return delegateOwner.getResources().getString(R.string.validation_event_no_previous);
            case TARGET_NOT_FOUND:
            default:
                return delegateOwner.getResources().getString(R.string.validation_target_changed);
        }
    }

    void mutateSelectedEvent(EventMutation mutation) {
        LineEvent target = delegateOwner.selectedEvent;
        LineEvent before = target.copy();
        LineEvent after = target.copy();
        mutation.apply(after);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                EditorView.copyEventFields(after, target);
                delegateOwner.selectedEvent = target;
            }

            @Override
            public void revert() {
                EditorView.copyEventFields(before, target);
                delegateOwner.selectedEvent = target;
            }
        });
        delegateOwner.invalidate();
    }
}
