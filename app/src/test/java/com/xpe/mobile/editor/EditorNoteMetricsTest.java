package com.xpe.mobile.editor;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class EditorNoteMetricsTest {
    @Test public void widthDoesNotStretchTextureOrHoldCapsVertically() {
        for (double size : new double[]{0.25, 1, 4}) {
            assertEquals(100 * size, EditorNoteMetrics.width(100, size), 0.001);
            assertEquals(10, EditorNoteMetrics.height(100, 1000, 100), 0.001);
            assertEquals(5, EditorNoteMetrics.height(100, 1000, 50), 0.001);
        }
    }
}
