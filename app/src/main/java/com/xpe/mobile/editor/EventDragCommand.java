package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.LineEvent;

import java.util.List;

/** A reversible time-only mutation for one normal event. */
public final class EventDragCommand {
    private EventDragCommand() {
    }

    public static EditHistory.Command move(EventLayer layer, LineEvent target,
                                           BeatTime beforeStart, BeatTime beforeEnd,
                                           BeatTime afterStart, BeatTime afterEnd) {
        List<LineEvent> events = layer.events(target.type);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                applyTimes(target, afterStart, afterEnd);
                sort(events);
            }

            @Override
            public void revert() {
                applyTimes(target, beforeStart, beforeEnd);
                sort(events);
            }
        };
    }

    private static void applyTimes(LineEvent target, BeatTime startTime, BeatTime endTime) {
        target.startTime = startTime;
        target.endTime = endTime;
    }

    private static void sort(List<LineEvent> events) {
        events.sort((first, second) -> first.startTime.compareTo(second.startTime));
    }
}
