package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventType;

/** Pure shared hit/scroll math for the six storyboard lanes. */
public final class StoryboardTimelineGeometry {
    private static final int EVENT_COLUMNS = EventType.values().length;
    private StoryboardTimelineGeometry() { }
    public static float laneWidth(float width, float density) {
        // Match ordinary event columns; the sixth storyboard lane remains horizontally reachable.
        return width / EVENT_COLUMNS;
    }
    public static float clampScroll(float offset, float width, float density, int count) {
        return Math.max(0, Math.min(offset, Math.max(0, laneWidth(width, density) * count - width)));
    }
    public static int laneAt(float x, float left, float width, float density, float offset, int count) {
        if (x < left || x >= left + width) return -1;
        return Math.max(0, Math.min(count - 1, (int) ((x - left + offset) / laneWidth(width, density))));
    }
    public static double snapped(double beat, int division) {
        return Math.max(0, Math.round(beat * division) / (double) division);
    }
}
