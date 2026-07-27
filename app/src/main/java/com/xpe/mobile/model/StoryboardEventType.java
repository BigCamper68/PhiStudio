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
}
