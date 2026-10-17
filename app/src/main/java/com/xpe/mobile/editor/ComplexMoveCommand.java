package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;

import java.util.ArrayList;
import java.util.List;

/** Atomic history command for a fitted set of paired MoveX/MoveY events. */
public final class ComplexMoveCommand {
    private ComplexMoveCommand() {
    }

    public static EditHistory.Command add(EventLayer layer,
                                          List<LineEvent> moveX,
                                          List<LineEvent> moveY) {
        List<LineEvent> xEvents = new ArrayList<>(moveX);
        List<LineEvent> yEvents = new ArrayList<>(moveY);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                addMissing(layer.events(EventType.MOVE_X), xEvents);
                addMissing(layer.events(EventType.MOVE_Y), yEvents);
            }

            @Override
            public void revert() {
                layer.events(EventType.MOVE_X).removeAll(xEvents);
                layer.events(EventType.MOVE_Y).removeAll(yEvents);
            }
        };
    }

    private static void addMissing(List<LineEvent> target, List<LineEvent> values) {
        for (LineEvent value : values) if (!target.contains(value)) target.add(value);
        target.sort((first, second) -> first.startTime.compareTo(second.startTime));
    }
}
