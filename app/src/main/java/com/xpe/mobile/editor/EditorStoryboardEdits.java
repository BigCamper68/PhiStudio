package com.xpe.mobile.editor;

import com.xpe.mobile.editor.EditorDocumentView.StoryboardApplyResult;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.StoryboardEventType;
import java.util.List;

/** EditorStoryboardEdits responsibilities extracted from EditorDocumentView. */
final class EditorStoryboardEdits {
    private final EditorDocumentView delegateOwner;

    EditorStoryboardEdits(EditorDocumentView delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    StoryboardApplyResult addStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
        JudgeLine line = delegateOwner.getCurrentJudgeLine();
        if (line == null || type == null || event == null) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        if (line.extended == null) line.extended = new ExtendedLineEvents();
        StoryboardEventValidator.Error validation = StoryboardEventValidator.validate(
                line.extended, type, event, null);
        StoryboardApplyResult result = EditorStoryboardEdits.mapStoryboardValidation(validation);
        if (result != StoryboardApplyResult.APPLIED) return result;
        delegateOwner.history.execute(StoryboardEditCommand.add(line.extended, type, event));
        delegateOwner.invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    StoryboardApplyResult applyStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            ExtendedLineEvents.TimedEvent edited) {
        JudgeLine line = delegateOwner.getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null
                || target == null || edited == null
                || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        StoryboardEventValidator.Error validation = StoryboardEventValidator.validate(
                line.extended, type, edited, target);
        StoryboardApplyResult result = EditorStoryboardEdits.mapStoryboardValidation(validation);
        if (result != StoryboardApplyResult.APPLIED) return result;
        delegateOwner.history.execute(StoryboardEditCommand.edit(line.extended, type, target,
                target.copy(), edited.copy()));
        delegateOwner.invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    StoryboardApplyResult deleteStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        JudgeLine line = delegateOwner.getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null
                || !line.extended.contains(type, target)) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        delegateOwner.history.execute(StoryboardEditCommand.delete(line.extended, type, target));
        delegateOwner.invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    StoryboardApplyResult glueStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target) {
        JudgeLine line = delegateOwner.getCurrentJudgeLine();
        if (line == null || line.extended == null || type == null || target == null) {
            return StoryboardApplyResult.TARGET_NOT_FOUND;
        }
        List<? extends ExtendedLineEvents.TimedEvent> events = line.extended.events(type);
        int index = events.indexOf(target);
        if (index < 0) return StoryboardApplyResult.TARGET_NOT_FOUND;
        if (index == 0) return StoryboardApplyResult.NO_PREVIOUS;
        ExtendedLineEvents.TimedEvent previous = events.get(index - 1);
        ExtendedLineEvents.TimedEvent edited = target.copy();
        boolean changed = EditorStoryboardEdits.copyStoryboardEndToStart(previous, edited);
        if (!changed) return StoryboardApplyResult.NO_CHANGE;
        return delegateOwner.applyStoryboardEvent(type, target, edited);
    }

    StoryboardApplyResult splitStoryboardEvent(
            StoryboardEventType type, ExtendedLineEvents.TimedEvent target,
            BeatTime splitTime) {
        JudgeLine line = delegateOwner.getCurrentJudgeLine();
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
        EditorStoryboardEdits.setStoryboardSplitValue(target, left, right, splitBeat);
        StoryboardApplyResult leftResult = EditorStoryboardEdits.mapStoryboardValidation(
                StoryboardEventValidator.validate(line.extended, type, left, target));
        StoryboardApplyResult rightResult = EditorStoryboardEdits.mapStoryboardValidation(
                StoryboardEventValidator.validate(line.extended, type, right, target));
        if (leftResult != StoryboardApplyResult.APPLIED) return leftResult;
        if (rightResult != StoryboardApplyResult.APPLIED) return rightResult;
        EditHistory.Command edit = StoryboardEditCommand.edit(
                line.extended, type, target, target.copy(), left);
        EditHistory.Command add = StoryboardEditCommand.add(line.extended, type, right);
        delegateOwner.history.execute(new EditHistory.Command() {
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
        delegateOwner.invalidate();
        return StoryboardApplyResult.APPLIED;
    }

    static StoryboardApplyResult mapStoryboardValidation(
            StoryboardEventValidator.Error error) {
        if (error == StoryboardEventValidator.Error.NONE) {
            return StoryboardApplyResult.APPLIED;
        }
        if (error == StoryboardEventValidator.Error.EVENT_OVERLAP) {
            return StoryboardApplyResult.EVENT_OVERLAP;
        }
        return StoryboardApplyResult.INVALID_VALUES;
    }

    static boolean copyStoryboardEndToStart(
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

    static void setStoryboardSplitValue(
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
}
