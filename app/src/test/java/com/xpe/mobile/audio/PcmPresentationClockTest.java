package com.xpe.mobile.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmPresentationClockTest {
    @Test public void laggingTimestampCannotMovePreviewBehindCheckpoint3HeadClock() {
        for (int rate : new int[]{44100, 48000}) for (float speed : new float[]{.5f, .75f, 1f}) {
            PcmPresentationClock clock = new PcmPresentationClock(rate, -.021, 10, speed);
            long now = 2_000_000_000L;
            clock.observe(rate, now, rate * 2);
            PcmPresentationClock.Timestamp sample = new PcmPresentationClock.Timestamp();
            sample.framePosition = rate - rate / 10; sample.nanoTime = now;
            clock.timestamp(sample, now);
            assertEquals(-.021 + speed, clock.seconds(now), 1e-12);
            assertEquals(-.021 + 1.01 * speed, clock.seconds(now + 10_000_000L), 1e-12);
        }
    }

    @Test public void laggingTimestampKeepsTheHeadClockSmoothAt60And120Fps() {
        for (int fps : new int[]{60, 120}) {
            PcmPresentationClock clock = new PcmPresentationClock(48000, 0, 10, 1);
            PcmPresentationClock.Timestamp sample = new PcmPresentationClock.Timestamp();
            for (int ms = 0, frame = 0; ms <= 1000; ms++) {
                long now = 1_000_000_000L + ms * 1_000_000L;
                long head = ms / 20 * 960;
                clock.observe(head, now, 96000);
                if (ms >= 120) {
                    sample.framePosition = head - 4800; sample.nanoTime = now;
                    clock.timestamp(sample, now);
                    if (ms >= frame * 1000.0 / fps) {
                        assertEquals(ms / 1000.0, clock.seconds(now), 1e-12); frame++;
                    }
                }
            }
        }
    }

    @Test public void advancesUniformlyBetweenCoarseHeadUpdatesAt60And120Fps() {
        for (int fps : new int[]{60, 120}) {
            PcmPresentationClock clock = new PcmPresentationClock(48000, 0, 10, 1);
            long origin = 1_000_000_000L;
            for (int ms = 0, frame = 0; ms < 1000; ms++) {
                long now = origin + ms * 1_000_000L;
                clock.observe(ms / 20 * 960, now, 96000);
                if (ms >= 40 && ms >= frame * 1000.0 / fps) {
                    assertEquals(ms / 1000.0, clock.seconds(now), 1e-9); frame++;
                }
            }
        }
    }
    @Test public void keepsExactNegativeStartUntilTheOutputAdvancesIncluding44100Hz() {
        for (int rate : new int[]{44100, 48000}) {
            PcmPresentationClock clock = new PcmPresentationClock(rate, -.021, 10, .5f);
            clock.observe(0, 1_000_000_000L, rate);
            assertEquals(-.021, clock.seconds(2_000_000_000L), 0);
            clock.observe(rate / 10, 2_000_000_000L, rate);
            assertEquals(.029, clock.seconds(2_000_000_000L), 1e-12);
        }
    }
    @Test public void hardwareTimestampAvoidsObservationDelayAndRejectsInvalidSamples() {
        PcmPresentationClock clock = new PcmPresentationClock(48000, 12, 20, 1);
        clock.observe(4800, 2_020_000_000L, 10000);
        PcmPresentationClock.Timestamp sample = new PcmPresentationClock.Timestamp();
        sample.framePosition = 4800; sample.nanoTime = 2_000_000_000L;
        clock.timestamp(sample, 2_020_000_000L);
        assertEquals(12.025 + .1, clock.seconds(2_025_000_000L), 1e-12);
        sample.framePosition = 100000; sample.nanoTime = 2_030_000_000L;
        clock.timestamp(sample, 2_025_000_000L);
        assertEquals(12.13, clock.seconds(2_030_000_000L), 1e-12);
    }
    @Test public void cannotRunAheadOfQueuedAudioOrBackwardAfterAnUnderrun() {
        PcmPresentationClock clock = new PcmPresentationClock(48000, 0, 1, 1);
        clock.observe(4800, 1_000_000_000L, 5280);
        assertEquals(.11, clock.seconds(1_030_000_000L), 1e-12);
        assertEquals(.11, clock.seconds(2_000_000_000L), 1e-12);
        clock.observe(4900, 2_000_000_000L, 5280);
        assertEquals(.11, clock.seconds(2_000_000_000L), 1e-12);
    }
}
