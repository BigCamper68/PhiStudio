package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class BpmListValidatorTest {
    @Test
    public void requiresPositiveFiniteBpmAndNonNegativeBeat() {
        List<BpmChange> changes = new ArrayList<>();
        BpmChange change = change(0, 120.0);
        assertEquals(BpmListValidator.Error.NONE,
                BpmListValidator.validate(changes, change, null));

        change.bpm = 0.0;
        assertEquals(BpmListValidator.Error.BPM_NOT_POSITIVE_FINITE,
                BpmListValidator.validate(changes, change, null));
        change.bpm = Double.POSITIVE_INFINITY;
        assertEquals(BpmListValidator.Error.BPM_NOT_POSITIVE_FINITE,
                BpmListValidator.validate(changes, change, null));
        change.bpm = 120.0;
        change.startTime = new BeatTime(-1, 0, 1);
        assertEquals(BpmListValidator.Error.NEGATIVE_START_TIME,
                BpmListValidator.validate(changes, change, null));
    }

    @Test
    public void keepsFirstEntryStableAndRejectsDuplicateStarts() {
        BpmChange first = change(0, 120.0);
        BpmChange second = change(4, 150.0);
        List<BpmChange> changes = new ArrayList<>();
        changes.add(first);
        changes.add(second);

        BpmChange movedFirst = first.copy();
        movedFirst.startTime = new BeatTime(1, 0, 1);
        assertEquals(BpmListValidator.Error.FIRST_ENTRY_LOCKED,
                BpmListValidator.validate(changes, movedFirst, first));

        assertEquals(BpmListValidator.Error.FIRST_ENTRY_LOCKED,
                BpmListValidator.validate(changes, change(0, 180.0), null));
        assertEquals(BpmListValidator.Error.DUPLICATE_START_TIME,
                BpmListValidator.validate(changes, change(4, 180.0), null));
        assertEquals(BpmListValidator.Error.NONE,
                BpmListValidator.validate(changes, change(8, 180.0), null));
    }

    private static BpmChange change(int beat, double bpm) {
        BpmChange change = new BpmChange();
        change.startTime = new BeatTime(beat, 0, 1);
        change.bpm = bpm;
        return change;
    }
}
