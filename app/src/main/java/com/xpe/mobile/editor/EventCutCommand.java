package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import java.util.List;

/** Reversible split of one normal event into two touching events. */
public final class EventCutCommand {
    private EventCutCommand() {
    }

    public static CutOperation cut(EventLayer layer, LineEvent target, BeatTime cutTime) {
        LineEvent before = target.copy();
        LineEvent left = target.copy();
        LineEvent right = target.copy();
        double cutValue = target.valueAt(cutTime.toDouble());

        left.endTime = cutTime;
        left.end = cutValue;
        right.startTime = cutTime;
        right.start = cutValue;
        if (target.type == EventType.SPEED) {
            left.easingType = 1;
            right.easingType = 1;
        } else {
            double duration = target.endTime.toDouble() - target.startTime.toDouble();
            double cutProgress = (cutTime.toDouble() - target.startTime.toDouble()) / duration;
            double easingCut = target.easingLeft
                    + (target.easingRight - target.easingLeft) * cutProgress;
            left.easingRight = easingCut;
            right.easingLeft = easingCut;
        }
        return new CutOperation(layer, target, before, left, right);
    }

    public static final class CutOperation implements EditHistory.Command {
        private final LineEvent target;
        private final LineEvent before;
        private final LineEvent left;
        private final LineEvent right;
        private final List<LineEvent> events;

        private CutOperation(EventLayer layer, LineEvent target, LineEvent before,
                             LineEvent left, LineEvent right) {
            this.target = target;
            this.before = before;
            this.left = left;
            this.right = right;
            this.events = layer.events(target.type);
        }

        public LineEvent rightEvent() {
            return right;
        }

        @Override
        public void apply() {
            copyFields(left, target);
            if (!events.contains(right)) events.add(right);
            sort(events);
        }

        @Override
        public void revert() {
            events.remove(right);
            copyFields(before, target);
            sort(events);
        }
    }

    private static void copyFields(LineEvent source, LineEvent target) {
        target.type = source.type;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.start = source.start;
        target.end = source.end;
        target.easingType = source.easingType;
        target.easingLeft = source.easingLeft;
        target.easingRight = source.easingRight;
        target.linkGroup = source.linkGroup;
        target.bezier = source.bezier;
        System.arraycopy(source.bezierPoints, 0, target.bezierPoints, 0,
                source.bezierPoints.length);
    }

    private static void sort(List<LineEvent> events) {
        events.sort((first, second) -> first.startTime.compareTo(second.startTime));
    }
}
