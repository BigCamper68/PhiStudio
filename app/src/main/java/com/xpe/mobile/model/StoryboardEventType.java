package com.xpe.mobile.model;

/** Supported RPE judge-line extended/storyboard event families. */
public enum StoryboardEventType {
    SCALE_X("scaleXEvents"),
    SCALE_Y("scaleYEvents"),
    COLOR("colorEvents"),
    PAINT("paintEvents"),
    TEXT("textEvents"),
    INCLINE("inclineEvents"),
    GIF("gifEvents");

    public final String jsonKey;

    StoryboardEventType(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    public boolean isNumeric() {
        return this != COLOR && this != TEXT;
    }

    /** Incline stays losslessly importable/exportable, but is not offered by this UI. */
    public static StoryboardEventType[] editableTypes() {
        return new StoryboardEventType[]{SCALE_X, SCALE_Y, COLOR, TEXT, PAINT, GIF};
    }
}
