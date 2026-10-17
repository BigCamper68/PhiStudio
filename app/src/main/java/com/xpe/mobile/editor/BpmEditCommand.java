package com.xpe.mobile.editor;

import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;

import java.util.List;

/** Reversible BPM-list mutations that keep the list ordered. */
public final class BpmEditCommand {
    private BpmEditCommand() {
    }

    public static EditHistory.Command add(ChartDocument chart, BpmChange change) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                if (!chart.bpmChanges.contains(change)) chart.bpmChanges.add(change);
                chart.sortBpm();
            }

            @Override
            public void revert() {
                chart.bpmChanges.remove(change);
                chart.sortBpm();
            }
        };
    }

    public static EditHistory.Command edit(ChartDocument chart, BpmChange target,
                                           BpmChange before, BpmChange after) {
        return new EditHistory.Command() {
            @Override
            public void apply() {
                copyEditableFields(after, target);
                chart.sortBpm();
            }

            @Override
            public void revert() {
                copyEditableFields(before, target);
                chart.sortBpm();
            }
        };
    }

    public static EditHistory.Command delete(ChartDocument chart, BpmChange change) {
        List<BpmChange> changes = chart.bpmChanges;
        int originalIndex = changes.indexOf(change);
        return new EditHistory.Command() {
            @Override
            public void apply() {
                changes.remove(change);
                chart.sortBpm();
            }

            @Override
            public void revert() {
                if (!changes.contains(change)) {
                    int index = Math.max(0, Math.min(originalIndex, changes.size()));
                    changes.add(index, change);
                }
                chart.sortBpm();
            }
        };
    }

    private static void copyEditableFields(BpmChange source, BpmChange target) {
        target.bpm = source.bpm;
        target.startTime = source.startTime;
    }
}
