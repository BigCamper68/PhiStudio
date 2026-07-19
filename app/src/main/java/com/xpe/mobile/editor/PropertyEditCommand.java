package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

import java.util.List;

public final class PropertyEditCommand {
    private PropertyEditCommand() {
    }

    public static EditHistory.Command note(JudgeLine line, Note target, Note before, Note after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                copyNoteFields(after, target);
                line.sortNotes();
            }

            @Override
            public void revert() {
                copyNoteFields(before, target);
                line.sortNotes();
            }
        };
    }

    public static EditHistory.Command event(EventLayer layer, LineEvent target,
                                            LineEvent before, LineEvent after) {
        List<LineEvent> events = layer.events(target.type);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                copyEventFields(after, target);
                events.sort((first, second) -> first.startTime.compareTo(second.startTime));
            }

            @Override
            public void revert() {
                copyEventFields(before, target);
                events.sort((first, second) -> first.startTime.compareTo(second.startTime));
            }
        };
    }

    private static void copyNoteFields(Note source, Note target) {
        target.above = source.above;
        target.alpha = source.alpha;
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.fake = source.fake;
        target.positionX = source.positionX;
        target.size = source.size;
        target.speed = source.speed;
        target.type = source.type;
        target.visibleTime = source.visibleTime;
        target.yOffset = source.yOffset;
        target.hasTint = source.hasTint;
        target.tintRgb = source.tintRgb;
        target.hasHitEffectTint = source.hasHitEffectTint;
        target.hitEffectTintRgb = source.hitEffectTintRgb;
        target.judgeArea = source.judgeArea;
    }

    private static void copyEventFields(LineEvent source, LineEvent target) {
        target.startTime = source.startTime;
        target.endTime = source.endTime;
        target.start = source.start;
        target.end = source.end;
        target.easingType = source.easingType;
        target.easingLeft = source.easingLeft;
        target.easingRight = source.easingRight;
        target.linkGroup = source.linkGroup;
        target.bezier = source.bezier;
        System.arraycopy(source.bezierPoints, 0, target.bezierPoints, 0, source.bezierPoints.length);
    }
}
