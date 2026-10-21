package com.xpe.mobile.editor;

import com.xpe.mobile.model.StoryboardEventType;
import com.xpe.mobile.model.EventType;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class WorkbenchLayoutTest {
    @Test public void landscapePaneKeepsAUsableNoteGridAcrossScreensAndDensities() {
        for (float d : new float[]{1, 1.5f, 2.4f, 3}) {
            for (float widthDp : new float[]{480, 640, 800, 960, 1280}) {
                float width = widthDp * d;
                float split = WorkbenchLayout.split(width, d);
                assertTrue(split >= width * .56f - .01f);
                assertTrue(width - split >= Math.min(240 * d, width * .44f) - .01f);
                assertTrue(width - split < Math.min(Math.max(280 * d, width * .36f), width * .48f));
                assertEquals(76 * d, WorkbenchLayout.footer(d), .01f);
                assertEquals(34 * d, WorkbenchLayout.workbenchFooter(d), .01f);
                assertEquals(48 * d, WorkbenchLayout.toolbar(d), .01f);
            }
        }
    }
    @Test public void laneHitTestsMatchScrollableGeometryIncludingPartialLanes() {
        for (float d : new float[]{1, 2.4f, 3}) {
            float left = 512 * d, width = 288 * d;
            float lane = StoryboardTimelineGeometry.laneWidth(width, d);
            for (int index = 0; index < 6; index++) {
                float offset = StoryboardTimelineGeometry.clampScroll(index * lane, width, d, 6);
                float x = left + index * lane - offset + lane / 2;
                assertEquals(index, StoryboardTimelineGeometry.laneAt(x, left, width, d, offset, 6));
            }
            assertEquals(-1, StoryboardTimelineGeometry.laneAt(left - 1, left, width, d, 0, 6));
            assertEquals(-1, StoryboardTimelineGeometry.laneAt(left + width, left, width, d, 0, 6));
            assertEquals(0, StoryboardTimelineGeometry.clampScroll(-1000, width, d, 6), 0);
            assertEquals(6 * lane - width, StoryboardTimelineGeometry.clampScroll(99999, width, d, 6), .01f);
        }
    }
    @Test public void storyboardColumnsMatchOrdinaryEventsWithoutAMinimumWidthOverride() {
        for (float d : new float[]{1, 1.5f, 2.4f, 3}) {
            for (float widthDp : new float[]{120, 230, 280, 360, 480, 690}) {
                float width = widthDp * d;
                assertEquals(width / EventType.values().length,
                        StoryboardTimelineGeometry.laneWidth(width, d), .001f);
            }
        }
    }
    @Test public void allRequestedFamiliesAreEditableAndInclineIsPreservedButHidden() {
        assertEquals(6, StoryboardEventType.editableTypes().length);
        assertFalse(Arrays.asList(StoryboardEventType.editableTypes()).contains(StoryboardEventType.INCLINE));
        assertTrue(Arrays.asList(StoryboardEventType.values()).contains(StoryboardEventType.INCLINE));
        assertEquals(6, new java.util.HashSet<>(Arrays.asList(StoryboardEventType.editableTypes())).size());
    }
    @Test public void snapDoesNotProduceNegativeCreationTimes() {
        assertEquals(0, StoryboardTimelineGeometry.snapped(-3, 4), 0);
        assertEquals(2.25, StoryboardTimelineGeometry.snapped(2.23, 4), 0);
        assertEquals(2.125, StoryboardTimelineGeometry.snapped(2.12, 8), 0);
    }
}
