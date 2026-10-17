package com.xpe.mobile.editor;

import com.xpe.mobile.editor.EditorDocumentView.BpmApplyResult;
import com.xpe.mobile.editor.EditorDocumentView.PropertyApplyResult;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

/** EditorPropertyEdits responsibilities extracted from EditorDocumentView. */
final class EditorPropertyEdits {
    private final EditorDocumentView delegateOwner;

    EditorPropertyEdits(EditorDocumentView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    BpmApplyResult addBpmChange(BpmChange change) {
        if (delegateOwner.chart == null || change == null) return BpmApplyResult.TARGET_NOT_FOUND;
        BpmListValidator.Error validation = BpmListValidator.validate(delegateOwner.chart.bpmChanges, change, null);
        BpmApplyResult result = EditorPropertyEdits.mapBpmValidation(validation);
        if (result != BpmApplyResult.APPLIED) return result;
        delegateOwner.history.execute(BpmEditCommand.add(delegateOwner.chart, change));
        delegateOwner.invalidate();
        return BpmApplyResult.APPLIED;
    }

    BpmApplyResult applyBpmProperties(BpmChange target, BpmChange edited) {
        if (delegateOwner.chart == null || target == null || edited == null || !delegateOwner.chart.bpmChanges.contains(target)) {
            return BpmApplyResult.TARGET_NOT_FOUND;
        }
        BpmListValidator.Error validation = BpmListValidator.validate(delegateOwner.chart.bpmChanges, edited, target);
        BpmApplyResult result = EditorPropertyEdits.mapBpmValidation(validation);
        if (result != BpmApplyResult.APPLIED) return result;
        delegateOwner.history.execute(BpmEditCommand.edit(delegateOwner.chart, target, target.copy(), edited.copy()));
        delegateOwner.invalidate();
        return BpmApplyResult.APPLIED;
    }

    BpmApplyResult deleteBpmChange(BpmChange target) {
        if (delegateOwner.chart == null || target == null || !delegateOwner.chart.bpmChanges.contains(target)) {
            return BpmApplyResult.TARGET_NOT_FOUND;
        }
        if (delegateOwner.chart.bpmChanges.size() <= 1) return BpmApplyResult.LAST_ENTRY_REQUIRED;
        if (target == delegateOwner.chart.bpmChanges.get(0)) return BpmApplyResult.FIRST_ENTRY_LOCKED;
        delegateOwner.history.execute(BpmEditCommand.delete(delegateOwner.chart, target));
        delegateOwner.invalidate();
        return BpmApplyResult.APPLIED;
    }

    static BpmApplyResult mapBpmValidation(BpmListValidator.Error error) {
        switch (error) {
            case NONE: return BpmApplyResult.APPLIED;
            case DUPLICATE_START_TIME: return BpmApplyResult.DUPLICATE_START_TIME;
            case FIRST_ENTRY_LOCKED: return BpmApplyResult.FIRST_ENTRY_LOCKED;
            default: return BpmApplyResult.INVALID_VALUE;
        }
    }

    PropertyApplyResult applyNoteProperties(Note target, Note edited) {
        if (delegateOwner.chart == null || target == null || edited == null) return PropertyApplyResult.TARGET_NOT_FOUND;
        JudgeLine line = delegateOwner.currentLine();
        if (!line.notes.contains(target)) return PropertyApplyResult.TARGET_NOT_FOUND;
        if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
            return PropertyApplyResult.INVALID_VALUES;
        }

        delegateOwner.history.execute(PropertyEditCommand.note(line, target, target.copy(), edited.copy()));
        delegateOwner.selectedNote = target;
        delegateOwner.selectedEvent = null;
        delegateOwner.invalidate();
        return PropertyApplyResult.APPLIED;
    }

    PropertyApplyResult applyEventProperties(LineEvent target, LineEvent edited) {
        if (delegateOwner.chart == null || target == null || edited == null) return PropertyApplyResult.TARGET_NOT_FOUND;
        if (!EditorDocumentView.isEditableEventLayer(delegateOwner.eventLayerIndex)) return PropertyApplyResult.INVALID_VALUES;
        EventLayer layer = delegateOwner.currentLayer();
        if (target.type != edited.type || !layer.events(target.type).contains(target)) {
            return PropertyApplyResult.TARGET_NOT_FOUND;
        }
        if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
            return PropertyApplyResult.INVALID_VALUES;
        }

        boolean timeChanged = !target.startTime.equals(edited.startTime)
                || !target.endTime.equals(edited.endTime);
        EditHistory.Command command;
        if (delegateOwner.xyBindingEnabled && XYBindingValidator.isMoveType(target.type) && timeChanged) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, target);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                return PropertyApplyResult.XY_BINDING_INVALID;
            }
            XYBindingValidator.Error validation = XYBindingValidator.validatePairedTimes(
                    layer, delegateOwner.eventLayerIndex, target, lookup.event,
                    edited.startTime, edited.endTime);
            if (validation == XYBindingValidator.Error.EVENT_OVERLAP) {
                return PropertyApplyResult.EVENT_OVERLAP;
            }
            if (validation != XYBindingValidator.Error.NONE) {
                return PropertyApplyResult.XY_BINDING_INVALID;
            }
            command = XYBindingCommand.editWithPairedTimes(
                    layer, target, target.copy(), edited.copy(), lookup.event,
                    lookup.event.startTime, lookup.event.endTime);
        } else {
            if (layer.overlaps(edited, target)) return PropertyApplyResult.EVENT_OVERLAP;
            command = PropertyEditCommand.event(layer, target, target.copy(), edited.copy());
        }
        delegateOwner.history.execute(delegateOwner.withAutoStick(layer, target, edited, command));
        delegateOwner.selectedEvent = target;
        delegateOwner.selectedNote = null;
        delegateOwner.invalidate();
        return PropertyApplyResult.APPLIED;
    }
}
