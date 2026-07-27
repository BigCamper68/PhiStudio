package com.xpe.mobile.editor;

import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.StoryboardEventType;

/** Reversible mutations for RPE extended/storyboard events. */
public final class StoryboardEditCommand {
    private StoryboardEditCommand() {
    }

    public static EditHistory.Command add(ExtendedLineEvents owner,
                                          StoryboardEventType type,
                                          ExtendedLineEvents.TimedEvent event) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                if (!owner.contains(type, event)) owner.add(type, event);
            }

            @Override
            public void revert() {
                owner.remove(type, event);
            }
        };
    }

    public static EditHistory.Command edit(ExtendedLineEvents owner,
                                           StoryboardEventType type,
                                           ExtendedLineEvents.TimedEvent target,
                                           ExtendedLineEvents.TimedEvent before,
                                           ExtendedLineEvents.TimedEvent after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                owner.copyEvent(type, after, target);
            }

            @Override
            public void revert() {
                owner.copyEvent(type, before, target);
            }
        };
    }

    public static EditHistory.Command delete(ExtendedLineEvents owner,
                                             StoryboardEventType type,
                                             ExtendedLineEvents.TimedEvent event) {
        int index = owner.indexOf(type, event);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                owner.remove(type, event);
            }

            @Override
            public void revert() {
                if (!owner.contains(type, event)) owner.insert(type, event, index);
            }
        };
    }
}
