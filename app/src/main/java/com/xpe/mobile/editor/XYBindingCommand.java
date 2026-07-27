package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.LineEvent;

import java.util.List;

/** Atomic reversible commands used while MoveX/MoveY time binding is enabled. */
public final class XYBindingCommand {
    private XYBindingCommand() {
    }

    public static EditHistory.Command add(EventLayer layer, LineEvent first, LineEvent second) {
        List<LineEvent> firstEvents = layer.events(first.type);
        List<LineEvent> secondEvents = layer.events(second.type);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                if (!firstEvents.contains(first)) firstEvents.add(first);
                if (!secondEvents.contains(second)) secondEvents.add(second);
                sort(firstEvents);
                sort(secondEvents);
            }

            @Override
            public void revert() {
                firstEvents.remove(first);
                secondEvents.remove(second);
            }
        };
    }

    public static EditHistory.Command delete(EventLayer layer, LineEvent first, LineEvent second) {
        List<LineEvent> firstEvents = layer.events(first.type);
        List<LineEvent> secondEvents = layer.events(second.type);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                firstEvents.remove(first);
                secondEvents.remove(second);
            }

            @Override
            public void revert() {
                if (!firstEvents.contains(first)) firstEvents.add(first);
                if (!secondEvents.contains(second)) secondEvents.add(second);
                sort(firstEvents);
                sort(secondEvents);
            }
        };
    }

    public static EditHistory.Command move(EventLayer layer,
                                           LineEvent first, BeatTime firstBeforeStart,
                                           BeatTime firstBeforeEnd,
                                           LineEvent second, BeatTime secondBeforeStart,
                                           BeatTime secondBeforeEnd,
                                           BeatTime afterStart, BeatTime afterEnd) {
        return atomic(
                EventDragCommand.move(layer, first,
                        firstBeforeStart, firstBeforeEnd, afterStart, afterEnd),
                EventDragCommand.move(layer, second,
                        secondBeforeStart, secondBeforeEnd, afterStart, afterEnd));
    }

    public static EditHistory.Command editWithPairedTimes(EventLayer layer,
                                                           LineEvent target,
                                                           LineEvent targetBefore,
                                                           LineEvent targetAfter,
                                                           LineEvent pair,
                                                           BeatTime pairBeforeStart,
                                                           BeatTime pairBeforeEnd) {
        return atomic(
                PropertyEditCommand.event(layer, target, targetBefore, targetAfter),
                EventDragCommand.move(layer, pair,
                        pairBeforeStart, pairBeforeEnd,
                        targetAfter.startTime, targetAfter.endTime));
    }

    public static EditHistory.Command cut(EventCutCommand.CutOperation first,
                                          EventCutCommand.CutOperation second) {
        return atomic(first, second);
    }

    public static EditHistory.Command atomic(EditHistory.Command... commands) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                for (EditHistory.Command command : commands) command.apply();
            }

            @Override
            public void revert() {
                for (int index = commands.length - 1; index >= 0; index--) {
                    commands[index].revert();
                }
            }
        };
    }

    private static void sort(List<LineEvent> events) {
        events.sort((first, second) -> first.startTime.compareTo(second.startTime));
    }
}
