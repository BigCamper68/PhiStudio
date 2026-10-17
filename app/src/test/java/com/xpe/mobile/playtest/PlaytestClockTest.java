package com.xpe.mobile.playtest;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.xpe.mobile.playtest.PlaytestClock.State.*;

public class PlaytestClockTest {
    @Test public void countdownKeepsTheExactStartAndStartsMusicOnce() {
        PlaytestClock c = new PlaytestClock(12.345, 3, 100);
        c.tick(3099, 99, true); assertEquals(COUNTDOWN, c.state()); assertEquals(12.345, c.seconds(), 0);
        c.tick(3100, 99, true); assertEquals(PLAYING, c.state()); assertTrue(c.takeAudioStart());
        assertFalse(c.takeAudioStart()); assertEquals(12.345, c.seconds(), 0);
    }
    @Test public void audioIsMasterAndSilentTailContinuesWithoutJumpingBack() {
        PlaytestClock c = new PlaytestClock(10, 0, 0); c.tick(0, 0, false); c.takeAudioStart();
        c.tick(100, 10.085, true); assertEquals(10.085, c.seconds(), 1e-10);
        c.tick(200, 10.080, true); assertEquals(10.085, c.seconds(), 1e-10);
        c.tick(700, Double.NaN, false); assertEquals(10.585, c.seconds(), 1e-10);
    }
    @Test public void pauseAndResumePreserveProgressAndRepeatTheChosenCountdown() {
        PlaytestClock c = new PlaytestClock(4, 2, 0); c.tick(2000, 0, false); c.takeAudioStart();
        c.tick(2500, 4.5, true); c.pause(); c.tick(10000, 99, true); assertEquals(4.5, c.seconds(), 0);
        c.resume(10000); c.tick(11999, 99, true); assertEquals(COUNTDOWN, c.state());
        c.tick(12000, 99, true); assertEquals(4.5, c.seconds(), 0); assertTrue(c.takeAudioStart());
        c.finish(); c.tick(30000, 99, true); assertEquals(FINISHED, c.state()); assertEquals(4.5, c.seconds(), 0);
    }
    @Test public void offHasNoDelayAndWorksWithoutMusic() {
        PlaytestClock c = new PlaytestClock(0, 0, 100); c.tick(100, 0, false);
        assertEquals(PLAYING, c.state()); c.takeAudioStart(); c.tick(250, 0, false); assertEquals(.15, c.seconds(), 0);
    }
    @Test public void timeParsingRejectsInvalidAndNonfiniteInputs() {
        assertEquals(83.125, PlaytestOptions.parseTime("1:23.125"), 0);
        assertEquals(3723.5, PlaytestOptions.parseTime("1:02:03,5"), 0);
        assertEquals(12.25, PlaytestOptions.parseTime("12,25"), 0);
        for (String s : new String[]{"", "-1", "NaN", "Infinity", "1:60", "1::2", "1.2:03", "1:2:3:4"}) {
            try { PlaytestOptions.parseTime(s); fail(s); } catch (IllegalArgumentException expected) { }
        }
    }
}
