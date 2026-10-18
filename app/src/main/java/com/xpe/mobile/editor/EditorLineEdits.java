package com.xpe.mobile.editor;

import com.xpe.mobile.editor.EditorDocumentView.LineApplyResult;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.JudgeLine;

/** EditorLineEdits responsibilities extracted from EditorDocumentView. */
final class EditorLineEdits {
    private final EditorDocumentView delegateOwner;

    EditorLineEdits(EditorDocumentView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    LineApplyResult selectLine(int index) {
        if (delegateOwner.chart == null || index < 0 || index >= delegateOwner.chart.judgeLines.size()) {
            return LineApplyResult.TARGET_NOT_FOUND;
        }
        delegateOwner.cancelActiveGesture();
        delegateOwner.lineIndex = index;
        delegateOwner.eventLayerIndex = Math.min(delegateOwner.eventLayerIndex, delegateOwner.currentLine().eventLayers.size() - 1);
        delegateOwner.clearSelection();
        delegateOwner.invalidate();
        return LineApplyResult.APPLIED;
    }

    boolean navigateToDiagnostic(ChartDiagnostic diagnostic) {
        if (delegateOwner.chart == null || diagnostic == null) return false;
        delegateOwner.cancelActiveGesture();
        delegateOwner.clearSelection();
        if (diagnostic.lineIndex >= 0) {
            if (diagnostic.lineIndex >= delegateOwner.chart.judgeLines.size()) return false;
            delegateOwner.lineIndex = diagnostic.lineIndex;
        }
        JudgeLine line = delegateOwner.currentLine();
        if (diagnostic.layerIndex >= 0 && diagnostic.layerIndex < 4) {
            delegateOwner.eventLayerIndex = diagnostic.layerIndex;
        }
        delegateOwner.currentBeat = Math.max(0.0, diagnostic.beat.toDouble());
        if (diagnostic.note != null && line.notes.contains(diagnostic.note)) {
            delegateOwner.selectedNote = diagnostic.note;
            delegateOwner.selectedNotes.add(diagnostic.note);
        } else if (diagnostic.event != null
                && diagnostic.layerIndex >= 0
                && diagnostic.layerIndex < line.eventLayers.size()) {
            EventLayer layer = line.eventLayers.get(diagnostic.layerIndex);
            if (layer.events(diagnostic.event.type).contains(diagnostic.event)) {
                delegateOwner.selectedEvent = diagnostic.event;
                delegateOwner.selectedEvents.add(diagnostic.event);
            }
        }
        delegateOwner.invalidate();
        return true;
    }

    JudgeLine addJudgeLine() {
        if (delegateOwner.chart == null) return null;
        JudgeLine line = new JudgeLine();
        line.name = "Line " + delegateOwner.chart.judgeLines.size();
        int index = delegateOwner.chart.judgeLines.size();
        EditHistory.Command command = JudgeLineCommand.add(delegateOwner.chart, line, index);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                delegateOwner.lineIndex = delegateOwner.chart.judgeLines.indexOf(line);
                delegateOwner.eventLayerIndex = 0;
                delegateOwner.clearSelection();
            }

            @Override
            public void revert() {
                command.revert();
                delegateOwner.lineIndex = Math.max(0, Math.min(index - 1, delegateOwner.chart.judgeLines.size() - 1));
                delegateOwner.clearSelection();
            }
        });
        delegateOwner.invalidate();
        return line;
    }

    LineApplyResult deleteActiveLine() {
        JudgeLineValidator.Error validation = JudgeLineValidator.validateDelete(delegateOwner.chart, delegateOwner.lineIndex);
        if (validation != JudgeLineValidator.Error.NONE) return EditorLineEdits.mapLineValidation(validation);
        JudgeLine target = delegateOwner.currentLine();
        int index = delegateOwner.lineIndex;
        EditHistory.Command command = JudgeLineCommand.delete(delegateOwner.chart, index);
        delegateOwner.history.execute(new EditHistory.Command() {
            @Override
            public void apply() {
                command.apply();
                delegateOwner.lineIndex = Math.max(0, Math.min(index - 1, delegateOwner.chart.judgeLines.size() - 1));
                delegateOwner.eventLayerIndex = Math.min(delegateOwner.eventLayerIndex, delegateOwner.currentLine().eventLayers.size() - 1);
                delegateOwner.clearSelection();
            }

            @Override
            public void revert() {
                command.revert();
                delegateOwner.lineIndex = delegateOwner.chart.judgeLines.indexOf(target);
                delegateOwner.clearSelection();
            }
        });
        delegateOwner.invalidate();
        return LineApplyResult.APPLIED;
    }

    LineApplyResult applyJudgeLineProperties(JudgeLine target, JudgeLine edited) {
        if (delegateOwner.chart == null || target == null || edited == null || !delegateOwner.chart.judgeLines.contains(target)) {
            return LineApplyResult.TARGET_NOT_FOUND;
        }
        JudgeLineValidator.Error validation = JudgeLineValidator.validateProperties(delegateOwner.chart, target, edited);
        if (validation != JudgeLineValidator.Error.NONE) return EditorLineEdits.mapLineValidation(validation);
        delegateOwner.history.execute(JudgeLineCommand.edit(target, target.copyProperties(), edited.copyProperties()));
        delegateOwner.invalidate();
        return LineApplyResult.APPLIED;
    }

    static LineApplyResult mapLineValidation(JudgeLineValidator.Error error) {
        switch (error) {
            case NONE: return LineApplyResult.APPLIED;
            case LINE_ZERO_PROTECTED: return LineApplyResult.LINE_ZERO_PROTECTED;
            case LAST_LINE_REQUIRED: return LineApplyResult.LAST_LINE_REQUIRED;
            case TARGET_NOT_FOUND: return LineApplyResult.TARGET_NOT_FOUND;
            case INVALID_PARENT: return LineApplyResult.INVALID_PARENT;
            default: return LineApplyResult.INVALID_PROPERTIES;
        }
    }
}
