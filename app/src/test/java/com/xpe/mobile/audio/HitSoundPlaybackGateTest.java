package com.xpe.mobile.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HitSoundPlaybackGateTest {
    @Test public void pendingStartFreezesClockAndLatestSeekSpeedWins() {
        HitSoundPlaybackGate gate = new HitSoundPlaybackGate();
        assertFalse(gate.request(1000, .5f, true));
        assertTrue(gate.isWaiting()); assertEquals(1000, gate.positionMillis());
        assertNull(gate.takeIfReady(false));
        assertFalse(gate.request(7000, .75f, true));
        var request = gate.takeIfReady(true);
        assertEquals(7000, request.positionMs); assertEquals(.75f, request.speed, 0);
        assertFalse(gate.isWaiting()); assertNull(gate.takeIfReady(true));
    }
    @Test public void pauseSeekProjectSwitchCancellationCannotResumeOldStart() {
        HitSoundPlaybackGate gate = new HitSoundPlaybackGate();
        gate.request(1000, .5f, true); gate.cancel();
        assertNull(gate.takeIfReady(true)); assertFalse(gate.isWaiting());
        assertTrue(gate.request(2000, 1, false)); assertFalse(gate.isWaiting());
        assertNull(gate.takeIfReady(true));
    }
}
