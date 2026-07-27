package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;

/** Cyclic touch-move mode for notes selected on the editor timeline. */
public enum NoteMoveMode {
    OFF,
    FREE,
    X_ONLY,
    Y_ONLY;

    public NoteMoveMode next() {
        switch (this) {
            case OFF: return FREE;
            case FREE: return X_ONLY;
            case X_ONLY: return Y_ONLY;
            case Y_ONLY:
            default: return OFF;
        }
    }

    public boolean enabled() {
        return this != OFF;
    }

    public BeatTime constrainBeatDelta(BeatTime value) {
        return this == X_ONLY ? BeatTime.zero() : value;
    }

    public double constrainXDelta(double value) {
        return this == Y_ONLY ? 0.0 : value;
    }
}
