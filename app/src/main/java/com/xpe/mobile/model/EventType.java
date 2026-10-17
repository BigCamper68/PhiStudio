package com.xpe.mobile.model;

public enum EventType {
    MOVE_X("moveXEvents", "Move X", -675.0, 675.0),
    MOVE_Y("moveYEvents", "Move Y", -450.0, 450.0),
    ROTATE("rotateEvents", "Rotate", -180.0, 180.0),
    ALPHA("alphaEvents", "Alpha", 0.0, 255.0),
    SPEED("speedEvents", "Speed", -20.0, 20.0);

    public final String jsonKey;
    public final String label;
    public final double displayMin;
    public final double displayMax;

    EventType(String jsonKey, String label, double displayMin, double displayMax) {
        this.jsonKey = jsonKey;
        this.label = label;
        this.displayMin = displayMin;
        this.displayMax = displayMax;
    }

    public static EventType fromColumn(int column) {
        EventType[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, column))];
    }
}
