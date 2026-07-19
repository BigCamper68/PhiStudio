package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;

public final class ChartDiagnostic {
    public enum Severity {
        ERROR,
        WARNING,
        CAUTION
    }

    public enum Category {
        NOTE,
        EVENT,
        OTHER
    }

    public enum Code {
        INVALID_BPM,
        NEGATIVE_BPM_START,
        DUPLICATE_BPM_START,
        NOTE_TIME_OUT_OF_RANGE,
        HOLD_INTERVAL_INVALID,
        NOTE_X_TOO_LARGE,
        FAKE_NOTE,
        CUSTOM_NOTE_SIZE,
        CUSTOM_VISIBLE_TIME,
        EVENT_TIME_OUT_OF_RANGE,
        EVENT_INTERVAL_INVALID,
        EVENT_OVERLAP,
        ALPHA_OUT_OF_RANGE,
        RESERVED_LAYER_NORMAL_EVENT
    }

    public final Severity severity;
    public final Category category;
    public final Code code;
    public final int lineIndex;
    public final int layerIndex;
    public final BeatTime beat;
    public final Note note;
    public final LineEvent event;

    ChartDiagnostic(Severity severity, Category category, Code code,
                    int lineIndex, int layerIndex, BeatTime beat,
                    Note note, LineEvent event) {
        this.severity = severity;
        this.category = category;
        this.code = code;
        this.lineIndex = lineIndex;
        this.layerIndex = layerIndex;
        this.beat = beat;
        this.note = note;
        this.event = event;
    }
}
