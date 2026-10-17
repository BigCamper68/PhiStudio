package com.xpe.mobile.editor;

import com.xpe.mobile.model.LineEvent;

/** Reversible value-only glue from the previous event into the selected event. */
public final class EventGlueCommand {
    private EventGlueCommand() {
    }

    public static EditHistory.Command glue(LineEvent previous, LineEvent target) {
        double beforeStartValue = target.start;
        double gluedStartValue = previous.end;
        return new EditHistory.Command() {
            @Override
            public void apply() {
                target.start = gluedStartValue;
            }

            @Override
            public void revert() {
                target.start = beforeStartValue;
            }
        };
    }
}
