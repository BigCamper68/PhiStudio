package com.xpe.mobile.model;

public enum NoteType {
    TAP(1),
    HOLD(2),
    FLICK(3),
    DRAG(4);

    public final int rpeCode;

    NoteType(int rpeCode) {
        this.rpeCode = rpeCode;
    }

    public static NoteType fromCode(int code) {
        for (NoteType type : values()) {
            if (type.rpeCode == code) return type;
        }
        return TAP;
    }
}
