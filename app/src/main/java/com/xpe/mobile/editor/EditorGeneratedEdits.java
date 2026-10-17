package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import java.util.ArrayList;
import java.util.List;

/** EditorGeneratedEdits responsibilities extracted from EditorDocumentView. */
final class EditorGeneratedEdits {
    private final EditorDocumentView delegateOwner;

    EditorGeneratedEdits(EditorDocumentView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    List<Note> getSelectedNotesForCurve() {
        if (delegateOwner.chart == null) return new ArrayList<>();
        List<Note> result = delegateOwner.selectedNotesForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    List<Note> getSelectedNotesForBatch() {
        if (delegateOwner.chart == null) return new ArrayList<>();
        List<Note> result = delegateOwner.selectedNotesForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    List<LineEvent> getSelectedEventsForBatch() {
        if (delegateOwner.chart == null) return new ArrayList<>();
        List<LineEvent> result = delegateOwner.selectedEventsForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    BatchEditOperation.Result applyNoteBatch(BatchEditOperation.NoteField field,
                                                    BatchValueTransform.Spec profile,
                                                    BatchValueTransform.Mode mode) {
        BatchEditOperation.Result result = BatchEditOperation.notes(
                delegateOwner.currentLine(), delegateOwner.getSelectedNotesForBatch(), field, profile, mode);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        delegateOwner.history.execute(result.command);
        delegateOwner.clearSelection();
        delegateOwner.selectedNotes.addAll(result.notes);
        delegateOwner.selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
        delegateOwner.activateEditPanel();
        delegateOwner.invalidate();
        return result;
    }

    BatchEditOperation.Result applyEventBatch(BatchEditOperation.EventField field,
                                                     BatchValueTransform.Spec profile,
                                                     BatchValueTransform.Mode mode,
                                                     EventType eventType) {
        List<LineEvent> filtered = new ArrayList<>();
        for (LineEvent event : delegateOwner.getSelectedEventsForBatch()) {
            if (event.type == eventType) filtered.add(event);
        }
        BatchEditOperation.Result result = BatchEditOperation.events(
                delegateOwner.currentLayer(), filtered, field, profile, mode);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        delegateOwner.history.execute(result.command);
        delegateOwner.clearSelection();
        delegateOwner.selectedEvents.addAll(result.events);
        delegateOwner.selectedEvent = result.events.isEmpty() ? null : result.events.get(0);
        delegateOwner.activateEditPanel();
        delegateOwner.invalidate();
        return result;
    }

    BatchEditOperation.Result stickSelectedEvents(EventType eventType) {
        List<LineEvent> filtered = new ArrayList<>();
        for (LineEvent event : delegateOwner.getSelectedEventsForBatch()) {
            if (event.type == eventType) filtered.add(event);
        }
        BatchEditOperation.Result result = BatchEditOperation.stick(delegateOwner.currentLayer(), filtered);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        delegateOwner.history.execute(result.command);
        delegateOwner.clearSelection();
        delegateOwner.selectedEvents.addAll(result.events);
        delegateOwner.selectedEvent = result.events.isEmpty() ? null : result.events.get(0);
        delegateOwner.activateEditPanel();
        delegateOwner.invalidate();
        return result;
    }

    EventCloneOperation.Result applyEventClone(EventCloneOperation.Spec spec) {
        EventCloneOperation.Result result = EventCloneOperation.prepare(
                delegateOwner.chart, delegateOwner.currentLine(), delegateOwner.eventLayerIndex, delegateOwner.getSelectedEventsForBatch(), spec);
        if (result.error != EventCloneOperation.Error.NONE) return result;
        int sourceLineIndex = delegateOwner.lineIndex;
        int targetLineIndex = spec.lineSequence[0];
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                result.command.apply();
                delegateOwner.lineIndex = targetLineIndex;
                delegateOwner.clearSelection();
                EventLayer visibleLayer = delegateOwner.currentLayer();
                for (LineEvent event : result.events) {
                    if (visibleLayer.events(event.type).contains(event)) delegateOwner.selectedEvents.add(event);
                }
                delegateOwner.selectedEvent = delegateOwner.selectedEvents.isEmpty()
                        ? null : delegateOwner.selectedEvents.iterator().next();
                delegateOwner.activateEditPanel();
            }

            @Override
            public void revert() {
                result.command.revert();
                delegateOwner.lineIndex = sourceLineIndex;
                delegateOwner.clearSelection();
            }
        });
        delegateOwner.invalidate();
        return result;
    }

    ComplexMoveGenerator.Result applyComplexMove(ComplexMoveGenerator.Spec spec) {
        if (delegateOwner.chart == null) {
            return ComplexMoveGenerator.Result.error(
                    ComplexMoveGenerator.Error.RESERVED_LAYER, "");
        }
        EventLayer layer = delegateOwner.currentLayer();
        ComplexMoveGenerator.Result result = ComplexMoveGenerator.generate(
                layer, delegateOwner.eventLayerIndex, spec);
        if (result.error != ComplexMoveGenerator.Error.NONE) return result;
        EditHistory.Command command = ComplexMoveCommand.add(
                layer, result.moveXEvents, result.moveYEvents);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                delegateOwner.clearSelection();
                delegateOwner.selectedEvents.addAll(result.moveXEvents);
                delegateOwner.selectedEvents.addAll(result.moveYEvents);
                delegateOwner.selectedEvent = result.moveXEvents.isEmpty()
                        ? null : result.moveXEvents.get(0);
                delegateOwner.activateEditPanel();
            }

            @Override
            public void revert() {
                command.revert();
                delegateOwner.clearSelection();
            }
        });
        delegateOwner.invalidate();
        return result;
    }

    CurveNoteGenerator.Result applyCurveNotes(Note startNote, Note endNote,
                                                      double curveDensity,
                                                      NoteType noteType,
                                                      int easingType) {
        if (delegateOwner.chart == null) {
            return CurveNoteGenerator.Result.error(
                    CurveNoteGenerator.Error.TARGET_NOT_FOUND);
        }
        JudgeLine line = delegateOwner.currentLine();
        CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                line, startNote, endNote, curveDensity, delegateOwner.subdivision, noteType, easingType);
        if (result.error != CurveNoteGenerator.Error.NONE) return result;
        EditHistory.Command command = CurveNoteCommand.add(line, result.notes);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                delegateOwner.clearSelection();
                delegateOwner.selectedNotes.addAll(result.notes);
                delegateOwner.selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
                delegateOwner.activateEditPanel();
            }

            @Override
            public void revert() {
                command.revert();
                delegateOwner.clearSelection();
            }
        });
        delegateOwner.invalidate();
        return result;
    }
}
