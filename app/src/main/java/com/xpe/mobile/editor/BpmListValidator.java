package com.xpe.mobile.editor;

import com.xpe.mobile.model.BpmChange;

import java.util.List;

/** Validation rules for deterministic and safe BPM-list editing. */
public final class BpmListValidator {
    public enum Error {
        NONE,
        MISSING_VALUE,
        NEGATIVE_START_TIME,
        BPM_NOT_POSITIVE_FINITE,
        DUPLICATE_START_TIME,
        FIRST_ENTRY_LOCKED
    }

    private BpmListValidator() {
    }

    public static Error validate(List<BpmChange> changes, BpmChange candidate, BpmChange ignored) {
        if (candidate == null || candidate.startTime == null) return Error.MISSING_VALUE;
        if (candidate.startTime.toDouble() < 0.0) return Error.NEGATIVE_START_TIME;
        if (!Double.isFinite(candidate.bpm) || candidate.bpm <= 0.0) {
            return Error.BPM_NOT_POSITIVE_FINITE;
        }

        if (changes != null && !changes.isEmpty()) {
            BpmChange first = changes.get(0);
            if (ignored == first && !candidate.startTime.equals(first.startTime)) {
                return Error.FIRST_ENTRY_LOCKED;
            }
            if (ignored != first && candidate.startTime.compareTo(first.startTime) <= 0) {
                return Error.FIRST_ENTRY_LOCKED;
            }
            for (BpmChange existing : changes) {
                if (existing == ignored) continue;
                if (existing.startTime.equals(candidate.startTime)) {
                    return Error.DUPLICATE_START_TIME;
                }
            }
        }
        return Error.NONE;
    }
}
