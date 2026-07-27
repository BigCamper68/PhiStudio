package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NoteMoveModeTest {
    @Test
    public void cyclesThroughRequestedFourStates() {
        NoteMoveMode mode = NoteMoveMode.OFF;
        assertFalse(mode.enabled());
        mode = mode.next();
        assertEquals(NoteMoveMode.FREE, mode);
        mode = mode.next();
        assertEquals(NoteMoveMode.X_ONLY, mode);
        mode = mode.next();
        assertEquals(NoteMoveMode.Y_ONLY, mode);
        assertEquals(NoteMoveMode.OFF, mode.next());
    }

    @Test
    public void axisModesConstrainOnlyTheBlockedAxis() {
        BeatTime beatDelta = new BeatTime(0, 1, 2);
        assertEquals(beatDelta, NoteMoveMode.FREE.constrainBeatDelta(beatDelta));
        assertEquals(12.5, NoteMoveMode.FREE.constrainXDelta(12.5), 0.0);
        assertEquals(BeatTime.zero(), NoteMoveMode.X_ONLY.constrainBeatDelta(beatDelta));
        assertEquals(12.5, NoteMoveMode.X_ONLY.constrainXDelta(12.5), 0.0);
        assertEquals(beatDelta, NoteMoveMode.Y_ONLY.constrainBeatDelta(beatDelta));
        assertEquals(0.0, NoteMoveMode.Y_ONLY.constrainXDelta(12.5), 0.0);
        assertTrue(NoteMoveMode.Y_ONLY.enabled());
    }
}
