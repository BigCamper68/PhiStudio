package com.xpe.mobile.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class EditorTimelineMetricsTest {
    @Test
    public void placesBaselineAtMarkedScreenshotPosition() {
        assertEquals(385f, EditorTimelineMetrics.baselineY(427f, 1.5f), 0f);
    }
}
