package com.xpe.mobile.editor;

import android.content.Context;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.StoryboardEventType;

import java.util.ArrayList;
import java.util.List;

/**
 * Chart lifecycle and undoable document commands shared by the interactive editor.
 *
 * <p>Canvas rendering lives in {@link EditorSurfaceView}; touch handling and editor chrome live in
 * {@link EditorView}. This layer keeps document mutations independent from both.
 */
abstract class EditorDocumentView extends EditorSurfaceView {
    public enum BpmApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        INVALID_VALUE,
        DUPLICATE_START_TIME,
        FIRST_ENTRY_LOCKED,
        LAST_ENTRY_REQUIRED
    }

    public enum PropertyApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        INVALID_VALUES,
        EVENT_OVERLAP,
        XY_BINDING_INVALID
    }

    public enum LineApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        LINE_ZERO_PROTECTED,
        LAST_LINE_REQUIRED,
        INVALID_PROPERTIES
    }

    public enum StoryboardApplyResult {
        APPLIED,
        TARGET_NOT_FOUND,
        INVALID_VALUES,
        EVENT_OVERLAP,
        NO_PREVIOUS,
        NO_CHANGE,
        SPLIT_OUTSIDE
    }

    protected final EditHistory history;

    protected EditorDocumentView(Context context) {
        super(context);
        history = new EditHistory(300, this::markChartDirty);
    }

    public boolean isPlaying() {
        return playing;
    }

    public BpmApplyResult addBpmChange(BpmChange change) {
        if (chart == null || change == null) return BpmApplyResult.TARGET_NOT_FOUND;
        BpmListValidator.Error validation = BpmListValidator.validate(chart.bpmChanges, change, null);
        BpmApplyResult result = mapBpmValidation(validation);
        if (result != BpmApplyResult.APPLIED) return result;
        history.execute(BpmEditCommand.add(chart, change));
        invalidate();
        return BpmApplyResult.APPLIED;
    }

    public BpmApplyResult applyBpmProperties(BpmChange target, BpmChange edited) {
        if (chart == null || target == null || edited == null || !chart.bpmChanges.contains(target)) {
            return BpmApplyResult.TARGET_NOT_FOUND;
        }
        BpmListValidator.Error validation = BpmListValidator.validate(chart.bpmChanges, edited, target);
        BpmApplyResult result = mapBpmValidation(validation);
        if (result != BpmApplyResult.APPLIED) return result;
        history.execute(BpmEditCommand.edit(chart, target, target.copy(), edited.copy()));
        invalidate();
        return BpmApplyResult.APPLIED;
    }

    public BpmApplyResult deleteBpmChange(BpmChange target) {
        if (chart == null || target == null || !chart.bpmChanges.contains(target)) {
            return BpmApplyResult.TARGET_NOT_FOUND;
        }
        if (chart.bpmChanges.size() <= 1) return BpmApplyResult.LAST_ENTRY_REQUIRED;
        if (target == chart.bpmChanges.get(0)) return BpmApplyResult.FIRST_ENTRY_LOCKED;
        history.execute(BpmEditCommand.delete(chart, target));
        invalidate();
        return BpmApplyResult.APPLIED;
    }

    private static BpmApplyResult mapBpmValidation(BpmListValidator.Error error) {
        switch (error) {
            case NONE: return BpmApplyResult.APPLIED;
            case DUPLICATE_START_TIME: return BpmApplyResult.DUPLICATE_START_TIME;
            case FIRST_ENTRY_LOCKED: return BpmApplyResult.FIRST_ENTRY_LOCKED;
            default: return BpmApplyResult.INVALID_VALUE;
        }
    }

    public PropertyApplyResult applyNoteProperties(Note target, Note edited) {
        if (chart == null || target == null || edited == null) return PropertyApplyResult.TARGET_NOT_FOUND;
        JudgeLine line = currentLine();
        if (!line.notes.contains(target)) return PropertyApplyResult.TARGET_NOT_FOUND;
        if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
            return PropertyApplyResult.INVALID_VALUES;
        }

        history.execute(PropertyEditCommand.note(line, target, target.copy(), edited.copy()));
        selectedNote = target;
        selectedEvent = null;
        invalidate();
        return PropertyApplyResult.APPLIED;
    }

    public PropertyApplyResult applyEventProperties(LineEvent target, LineEvent edited) {
        if (chart == null || target == null || edited == null) return PropertyApplyResult.TARGET_NOT_FOUND;
        if (!isEditableEventLayer(eventLayerIndex)) return PropertyApplyResult.INVALID_VALUES;
        EventLayer layer = currentLayer();
        if (target.type != edited.type || !layer.events(target.type).contains(target)) {
            return PropertyApplyResult.TARGET_NOT_FOUND;
        }
        if (PropertyValidator.validate(edited) != PropertyValidator.Error.NONE) {
            return PropertyApplyResult.INVALID_VALUES;
        }

        boolean timeChanged = !target.startTime.equals(edited.startTime)
                || !target.endTime.equals(edited.endTime);
        EditHistory.Command command;
        if (xyBindingEnabled && XYBindingValidator.isMoveType(target.type) && timeChanged) {
            XYBindingValidator.PairLookup lookup = XYBindingValidator.findPair(layer, target);
            if (lookup.error != XYBindingValidator.Error.NONE || lookup.event == null) {
                return PropertyApplyResult.XY_BINDING_INVALID;
            }
            XYBindingValidator.Error validation = XYBindingValidator.validatePairedTimes(
                    layer, eventLayerIndex, target, lookup.event,
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
        history.execute(withAutoStick(layer, target, edited, command));
        selectedEvent = target;
        selectedNote = null;
        invalidate();
        return PropertyApplyResult.APPLIED;
    }

    public int getLineIndex() {
        return lineIndex;
    }

    public int getEventLayerIndex() {
        return eventLayerIndex;
    }

    public int getSubdivision() {
        return subdivision;
    }

    public BeatTime getCurrentBeatTime() {
        return BeatTime.fromDouble(Math.max(0.0, snapBeat(currentBeat)), subdivision);
    }

    public List<Note> getCurrentLineNotes() {
        if (chart == null) return new ArrayList<>();
        return new ArrayList<>(currentLine().notes);
    }

    public JudgeLine getCurrentJudgeLine() {
        return chart == null ? null : currentLine();
    }

    public StoryboardApplyResult addStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || type == null || event == null) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        if (line.extended == null) line.extended = new ExtendedLineEvents();
        StoryboardEventValidator.Error validation = StoryboardEventValidator.validate(
                line.extended, type, event, null);
        StoryboardApplyResult result = mapStoryboardValidation(validation);
        if (result != StoryboardApplyResult.APPLIED) return result;
        history.execute(StoryboardEditCommand.add(line.extended, type, event));
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    public StoryboardApplyResult applyStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            ExtendedLineEvents.TimedEvent edited) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null
                || target == null || edited == null
                || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        StoryboardEventValidator.Error validation = StoryboardEventValidator.validate(
                line.extended, type, edited, target);
        StoryboardApplyResult result = mapStoryboardValidation(validation);
        if (result != StoryboardApplyResult.APPLIED) return result;
        history.execute(StoryboardEditCommand.edit(line.extended, type, target,
                target.copy(), edited.copy()));
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    public StoryboardApplyResult deleteStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null
                || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        history.execute(StoryboardEditCommand.delete(line.extended, type, target));
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    public StoryboardApplyResult glueStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        List<? extends ExtendedLineEvents.TimedEvent> events = line.extended.events(type);
        int index = events.indexOf(target);
        if (index < 0) return StoryboardApplyResult.TARGET_NOT_FOUND;
        if (index == 0) return StoryboardApplyResult.NO_PREVIOUS;
        ExtendedLineEvents.TimedEvent previous = events.get(index - 1);
        ExtendedLineEvents.TimedEvent edited = target.copy();
        boolean changed = copyStoryboardEndToStart(previous, edited);
        if (!changed) return StoryboardApplyResult.NO_CHANGE;
        return applyStoryboardEvent(type, target, edited);
    }

    public StoryboardApplyResult splitStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            BeatTime splitTime) {
        JudgeLine line = getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null
                || splitTime == null || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        if (splitTime.compareTo(target.startTime) <= 0
                || splitTime.compareTo(target.endTime) >= 0) {
            return StoryboardApplyResult.SPLIT_OUTSIDE;
        }
        double splitBeat = splitTime.toDouble();
        ExtendedLineEvents.TimedEvent left = target.copy();
        ExtendedLineEvents.TimedEvent right = target.copy();
        left.endTime = splitTime;
        right.startTime = splitTime;
        setStoryboardSplitValue(target, left, right, splitBeat);
        StoryboardApplyResult leftResult = mapStoryboardValidation(
                StoryboardEventValidator.validate(line.extended, type, left, target));
        StoryboardApplyResult rightResult = mapStoryboardValidation(
                StoryboardEventValidator.validate(line.extended, type, right, target));
        if (leftResult != StoryboardApplyResult.APPLIED) return leftResult;
        if (rightResult != StoryboardApplyResult.APPLIED) return rightResult;
        EditHistory.Command edit = StoryboardEditCommand.edit(
                line.extended, type, target, target.copy(), left);
        EditHistory.Command add = StoryboardEditCommand.add(line.extended, type, right);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                edit.apply();
                add.apply();
            }

            @Override
            public void revert() {
                add.revert();
                edit.revert();
            }
        });
        invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    private static StoryboardApplyResult mapStoryboardValidation(
            StoryboardEventValidator.Error error) {
        if (error == StoryboardEventValidator.Error.NONE) {
            return StoryboardApplyResult.APPLIED;
        }
        if (error == StoryboardEventValidator.Error.EVENT_OVERLAP) {
            return StoryboardApplyResult.EVENT_OVERLAP;
        }
        return StoryboardApplyResult.INVALID_VALUES;
    }

    private static boolean copyStoryboardEndToStart(
            ExtendedLineEvents.TimedEvent source,
            ExtendedLineEvents.TimedEvent target) {
        if (source instanceof ExtendedLineEvents.NumericEvent
                && target instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent from = (ExtendedLineEvents.NumericEvent) source;
            ExtendedLineEvents.NumericEvent to = (ExtendedLineEvents.NumericEvent) target;
            boolean changed = Double.compare(to.start, from.end) != 0;
            to.start = from.end;
            return changed;
        }
        if (source instanceof ExtendedLineEvents.ColorEvent
                && target instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent from = (ExtendedLineEvents.ColorEvent) source;
            ExtendedLineEvents.ColorEvent to = (ExtendedLineEvents.ColorEvent) target;
            boolean changed = to.startRgb != from.endRgb;
            to.startRgb = from.endRgb;
            return changed;
        }
        if (source instanceof ExtendedLineEvents.TextEvent
                && target instanceof ExtendedLineEvents.TextEvent) {
            ExtendedLineEvents.TextEvent from = (ExtendedLineEvents.TextEvent) source;
            ExtendedLineEvents.TextEvent to = (ExtendedLineEvents.TextEvent) target;
            boolean changed = !to.start.equals(from.end);
            to.start = from.end;
            return changed;
        }
        return false;
    }

    private static void setStoryboardSplitValue(
            ExtendedLineEvents.TimedEvent source,
            ExtendedLineEvents.TimedEvent left,
            ExtendedLineEvents.TimedEvent right, double splitBeat) {
        if (source instanceof ExtendedLineEvents.NumericEvent) {
            double value = ((ExtendedLineEvents.NumericEvent) source).valueAt(splitBeat);
            ((ExtendedLineEvents.NumericEvent) left).end = value;
            ((ExtendedLineEvents.NumericEvent) right).start = value;
        } else if (source instanceof ExtendedLineEvents.ColorEvent) {
            int value = ((ExtendedLineEvents.ColorEvent) source).valueAt(splitBeat);
            ((ExtendedLineEvents.ColorEvent) left).endRgb = value;
            ((ExtendedLineEvents.ColorEvent) right).startRgb = value;
        } else if (source instanceof ExtendedLineEvents.TextEvent) {
            String value = ((ExtendedLineEvents.TextEvent) source).valueAt(splitBeat);
            ((ExtendedLineEvents.TextEvent) left).end = value;
            ((ExtendedLineEvents.TextEvent) right).start = value;
        }
    }

    public List<Note> getSelectedNotesForCurve() {
        if (chart == null) return new ArrayList<>();
        List<Note> result = selectedNotesForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    public List<Note> getSelectedNotesForBatch() {
        if (chart == null) return new ArrayList<>();
        List<Note> result = selectedNotesForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    public List<LineEvent> getSelectedEventsForBatch() {
        if (chart == null) return new ArrayList<>();
        List<LineEvent> result = selectedEventsForClipboard();
        result.sort((first, second) -> first.startTime.compareTo(second.startTime));
        return result;
    }

    public BatchEditOperation.Result applyNoteBatch(BatchEditOperation.NoteField field,
                                                    BatchValueTransform.Spec profile,
                                                    BatchValueTransform.Mode mode) {
        BatchEditOperation.Result result = BatchEditOperation.notes(
                currentLine(), getSelectedNotesForBatch(), field, profile, mode);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        history.execute(result.command);
        clearSelection();
        selectedNotes.addAll(result.notes);
        selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
        activateEditPanel();
        invalidate();
        return result;
    }

    public BatchEditOperation.Result applyEventBatch(BatchEditOperation.EventField field,
                                                     BatchValueTransform.Spec profile,
                                                     BatchValueTransform.Mode mode,
                                                     EventType eventType) {
        List<LineEvent> filtered = new ArrayList<>();
        for (LineEvent event : getSelectedEventsForBatch()) {
            if (event.type == eventType) filtered.add(event);
        }
        BatchEditOperation.Result result = BatchEditOperation.events(
                currentLayer(), filtered, field, profile, mode);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        history.execute(result.command);
        clearSelection();
        selectedEvents.addAll(result.events);
        selectedEvent = result.events.isEmpty() ? null : result.events.get(0);
        activateEditPanel();
        invalidate();
        return result;
    }

    public BatchEditOperation.Result stickSelectedEvents(EventType eventType) {
        List<LineEvent> filtered = new ArrayList<>();
        for (LineEvent event : getSelectedEventsForBatch()) {
            if (event.type == eventType) filtered.add(event);
        }
        BatchEditOperation.Result result = BatchEditOperation.stick(currentLayer(), filtered);
        if (result.error != BatchEditOperation.Error.NONE) return result;
        history.execute(result.command);
        clearSelection();
        selectedEvents.addAll(result.events);
        selectedEvent = result.events.isEmpty() ? null : result.events.get(0);
        activateEditPanel();
        invalidate();
        return result;
    }

    public EventCloneOperation.Result applyEventClone(EventCloneOperation.Spec spec) {
        EventCloneOperation.Result result = EventCloneOperation.prepare(
                chart, currentLine(), eventLayerIndex, getSelectedEventsForBatch(), spec);
        if (result.error != EventCloneOperation.Error.NONE) return result;
        int sourceLineIndex = lineIndex;
        int targetLineIndex = spec.lineSequence[0];
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                result.command.apply();
                lineIndex = targetLineIndex;
                clearSelection();
                EventLayer visibleLayer = currentLayer();
                for (LineEvent event : result.events) {
                    if (visibleLayer.events(event.type).contains(event)) selectedEvents.add(event);
                }
                selectedEvent = selectedEvents.isEmpty()
                        ? null : selectedEvents.iterator().next();
                activateEditPanel();
            }

            @Override
            public void revert() {
                result.command.revert();
                lineIndex = sourceLineIndex;
                clearSelection();
            }
        });
        invalidate();
        return result;
    }

    public void performUndo() {
        undo();
    }

    public void performRedo() {
        redo();
    }

    public void performCopy() {
        copySelection();
    }

    public void performCut() {
        cutSelection();
    }

    public void performPaste(boolean mirrorNotes) {
        pasteClipboard(mirrorNotes);
    }

    public void performDeleteSelection() {
        deleteSelection();
    }

    public void performTogglePlay() {
        togglePlay();
    }

    public ComplexMoveGenerator.Result applyComplexMove(ComplexMoveGenerator.Spec spec) {
        if (chart == null) {
            return ComplexMoveGenerator.Result.error(
                    ComplexMoveGenerator.Error.RESERVED_LAYER, "");
        }
        EventLayer layer = currentLayer();
        ComplexMoveGenerator.Result result = ComplexMoveGenerator.generate(
                layer, eventLayerIndex, spec);
        if (result.error != ComplexMoveGenerator.Error.NONE) return result;
        EditHistory.Command command = ComplexMoveCommand.add(
                layer, result.moveXEvents, result.moveYEvents);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                clearSelection();
                selectedEvents.addAll(result.moveXEvents);
                selectedEvents.addAll(result.moveYEvents);
                selectedEvent = result.moveXEvents.isEmpty()
                        ? null : result.moveXEvents.get(0);
                activateEditPanel();
            }

            @Override
            public void revert() {
                command.revert();
                clearSelection();
            }
        });
        invalidate();
        return result;
    }

    public CurveNoteGenerator.Result applyCurveNotes(Note startNote, Note endNote,
                                                      double curveDensity,
                                                      NoteType noteType,
                                                      int easingType) {
        if (chart == null) {
            return CurveNoteGenerator.Result.error(
                    CurveNoteGenerator.Error.TARGET_NOT_FOUND);
        }
        JudgeLine line = currentLine();
        CurveNoteGenerator.Result result = CurveNoteGenerator.generate(
                line, startNote, endNote, curveDensity, subdivision, noteType, easingType);
        if (result.error != CurveNoteGenerator.Error.NONE) return result;
        EditHistory.Command command = CurveNoteCommand.add(line, result.notes);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                clearSelection();
                selectedNotes.addAll(result.notes);
                selectedNote = result.notes.isEmpty() ? null : result.notes.get(0);
                activateEditPanel();
            }

            @Override
            public void revert() {
                command.revert();
                clearSelection();
            }
        });
        invalidate();
        return result;
    }

    public LineApplyResult selectLine(int index) {
        if (chart == null || index < 0 || index >= chart.judgeLines.size()) {
            return LineApplyResult.TARGET_NOT_FOUND;
        }
        cancelActiveGesture();
        lineIndex = index;
        eventLayerIndex = Math.min(eventLayerIndex, currentLine().eventLayers.size() - 1);
        clearSelection();
        invalidate();
        return LineApplyResult.APPLIED;
    }

    public boolean navigateToDiagnostic(ChartDiagnostic diagnostic) {
        if (chart == null || diagnostic == null) return false;
        cancelActiveGesture();
        clearSelection();
        if (diagnostic.lineIndex >= 0) {
            if (diagnostic.lineIndex >= chart.judgeLines.size()) return false;
            lineIndex = diagnostic.lineIndex;
        }
        JudgeLine line = currentLine();
        if (diagnostic.layerIndex >= 0 && diagnostic.layerIndex < 4) {
            eventLayerIndex = diagnostic.layerIndex;
        }
        currentBeat = Math.max(0.0, diagnostic.beat.toDouble());
        if (diagnostic.note != null && line.notes.contains(diagnostic.note)) {
            selectedNote = diagnostic.note;
            selectedNotes.add(diagnostic.note);
        } else if (diagnostic.event != null
                && diagnostic.layerIndex >= 0
                && diagnostic.layerIndex < line.eventLayers.size()) {
            EventLayer layer = line.eventLayers.get(diagnostic.layerIndex);
            if (layer.events(diagnostic.event.type).contains(diagnostic.event)) {
                selectedEvent = diagnostic.event;
                selectedEvents.add(diagnostic.event);
            }
        }
        invalidate();
        return true;
    }

    public JudgeLine addJudgeLine() {
        if (chart == null) return null;
        JudgeLine line = new JudgeLine();
        line.name = "Line " + chart.judgeLines.size();
        int index = chart.judgeLines.size();
        EditHistory.Command command = JudgeLineCommand.add(chart, line, index);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                lineIndex = chart.judgeLines.indexOf(line);
                eventLayerIndex = 0;
                clearSelection();
            }

            @Override
            public void revert() {
                command.revert();
                lineIndex = Math.max(0, Math.min(index - 1, chart.judgeLines.size() - 1));
                clearSelection();
            }
        });
        invalidate();
        return line;
    }

    public LineApplyResult deleteActiveLine() {
        JudgeLineValidator.Error validation = JudgeLineValidator.validateDelete(chart, lineIndex);
        if (validation != JudgeLineValidator.Error.NONE) return mapLineValidation(validation);
        JudgeLine target = currentLine();
        int index = lineIndex;
        EditHistory.Command command = JudgeLineCommand.delete(chart, index);
        history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                lineIndex = Math.max(0, Math.min(index - 1, chart.judgeLines.size() - 1));
                eventLayerIndex = Math.min(eventLayerIndex, currentLine().eventLayers.size() - 1);
                clearSelection();
            }

            @Override
            public void revert() {
                command.revert();
                lineIndex = chart.judgeLines.indexOf(target);
                clearSelection();
            }
        });
        invalidate();
        return LineApplyResult.APPLIED;
    }

    public LineApplyResult applyJudgeLineProperties(JudgeLine target, JudgeLine edited) {
        if (chart == null || target == null || edited == null || !chart.judgeLines.contains(target)) {
            return LineApplyResult.TARGET_NOT_FOUND;
        }
        JudgeLineValidator.Error validation = JudgeLineValidator.validateProperties(edited);
        if (validation != JudgeLineValidator.Error.NONE) return mapLineValidation(validation);
        history.execute(JudgeLineCommand.edit(target, target.copyProperties(), edited.copyProperties()));
        invalidate();
        return LineApplyResult.APPLIED;
    }

    private static LineApplyResult mapLineValidation(JudgeLineValidator.Error error) {
        switch (error) {
            case NONE: return LineApplyResult.APPLIED;
            case LINE_ZERO_PROTECTED: return LineApplyResult.LINE_ZERO_PROTECTED;
            case LAST_LINE_REQUIRED: return LineApplyResult.LAST_LINE_REQUIRED;
            case TARGET_NOT_FOUND: return LineApplyResult.TARGET_NOT_FOUND;
            default: return LineApplyResult.INVALID_PROPERTIES;
        }
    }

    protected static boolean isEditableEventLayer(int layerIndex) {
        return layerIndex >= 0 && layerIndex <= 3;
    }

    public abstract void markChartDirty();

    protected abstract void cancelActiveGesture();

    protected abstract void clearSelection();

    protected abstract void activateEditPanel();

    protected abstract List<Note> selectedNotesForClipboard();

    protected abstract List<LineEvent> selectedEventsForClipboard();

    protected abstract void undo();

    protected abstract void redo();

    protected abstract void copySelection();

    protected abstract void cutSelection();

    protected abstract void pasteClipboard(boolean mirrorNotes);

    protected abstract void deleteSelection();

    protected abstract void togglePlay();

    protected abstract EditHistory.Command withAutoStick(
            EventLayer layer, LineEvent target, LineEvent edited,
            EditHistory.Command baseCommand);
}
