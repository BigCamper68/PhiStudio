package com.xpe.mobile.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaybackTimestampTest {
    @Test
    public void advancesFromMediaAnchorUsingSystemClockAndPlaybackRate() {
        assertEquals(2125L, PlaybackTimestamp.positionMillis(
                2_000_000L, 10_000_000_000L, 0.5f,
                10_250_000_000L, 999L));
    }

    @Test
    public void returnsAnchorWhileMediaClockIsPaused() {
        assertEquals(2000L, PlaybackTimestamp.positionMillis(
                2_000_000L, 10_000_000_000L, 0f,
                11_000_000_000L, 999L));
    }

    @Test
    public void usesFallbackForUnknownTimestamp() {
        assertEquals(321L, PlaybackTimestamp.positionMillis(
                -1L, -1L, -1f, 20L, 321L));
    }

    @Test
    public void zeroSeekWaitsForPresentationTimestampBeforeAdvancingChart() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        long startNs = 10_000_000_000L;
        tracker.startAfterSeek(0L, 1f, startNs);

        assertEquals(0L, tracker.positionMillis(
                -1L, -1L, -1f, startNs + 100_000_000L, 100L));
        assertEquals(120L, tracker.positionMillis(
                80_000L, startNs + 80_000_000L, 1f,
                startNs + 120_000_000L, 120L));
        assertFalse(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void zeroSeekFallsBackAfterBoundedPresentationWait() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        long startNs = 10_000_000_000L;
        tracker.startAfterSeek(0L, 1f, startNs);

        assertEquals(0L, tracker.positionMillis(
                -1L, -1L, -1f, startNs + 200_000_000L, 200L));
        assertEquals(275L, tracker.positionMillis(
                -1L, -1L, -1f, startNs + 275_000_000L, 275L));
        assertFalse(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void ignoresStaleTimestampAndFallbackAfterNonZeroSeek() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 1f, 10_000_000_000L);

        long position = tracker.positionMillis(
                0L, 10_100_000_000L, 1f,
                10_100_000_000L, 0L);

        assertEquals(60_100L, position);
        assertTrue(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void acceptsTimestampAfterItRebasesToSeekTarget() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 1f, 10_000_000_000L);

        long position = tracker.positionMillis(
                60_090_000L, 10_090_000_000L, 1f,
                10_100_000_000L, 60_100L);

        assertEquals(60_100L, position);
        assertFalse(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void smoothsSmallDecoderCorrectionInsteadOfSnappingGrid() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 1f, 10_000_000_000L);

        long first = tracker.positionMillis(
                -1L, -1L, -1f,
                10_800_000_000L, 60_760L);
        long second = tracker.positionMillis(
                -1L, -1L, -1f,
                10_900_000_000L, 60_860L);

        assertEquals(60_797L, first);
        assertEquals(60_894L, second);
        assertFalse(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void rejectsLargePostSeekDiscontinuityFromBothRawClocks() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(10_000L, 1f, 20_000_000_000L);

        long position = tracker.positionMillis(
                9_500_000L, 20_800_000_000L, 1f,
                20_800_000_000L, 9_500L);

        assertEquals(10_800L, position);
        assertTrue(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void neverMovesBackwardWhenAnAcceptedObservationJitters() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(10_000L, 1f, 20_000_000_000L);

        long first = tracker.positionMillis(
                -1L, -1L, -1f,
                20_200_000_000L, 10_080L);
        long second = tracker.positionMillis(
                -1L, -1L, -1f,
                20_201_000_000L, 10_000L);

        assertEquals(10_192L, first);
        assertEquals(first, second);
    }

    @Test
    public void frozenRawPositionsCannotFreezeMonotonicClock() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 1f, 10_000_000_000L);

        assertEquals(60_800L, tracker.positionMillis(
                0L, 10_800_000_000L, 1f,
                10_800_000_000L, 0L));
        assertEquals(60_900L, tracker.positionMillis(
                0L, 10_900_000_000L, 1f,
                10_900_000_000L, 0L));
    }

    @Test
    public void repeatedSeekReplacesPreviousClockAnchor() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 1f, 10_000_000_000L);
        tracker.positionMillis(-1L, -1L, -1f,
                10_500_000_000L, 60_500L);

        tracker.startAfterSeek(120_000L, 0.5f, 20_000_000_000L);

        assertEquals(120_100L, tracker.positionMillis(
                60_000_000L, 20_200_000_000L, 1f,
                20_200_000_000L, 60_000L));
        assertTrue(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void ignoresTimestampFromPreviousPlaybackRate() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 0.5f, 10_000_000_000L);

        long position = tracker.positionMillis(
                60_200_000L, 10_200_000_000L, 1f,
                10_200_000_000L, 60_100L);

        assertEquals(60_100L, position);
        assertFalse(tracker.isAwaitingTimestampRebase());
    }

    @Test
    public void retainsSubMillisecondPhaseAcrossHighRefreshFrames() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        long startNs = 10_000_000_000L;
        tracker.startAfterSeek(60_000L, 0.75f, startNs);

        long position = 60_000L;
        for (int frame = 1; frame <= 120; frame++) {
            position = tracker.positionMillis(
                    -1L, -1L, -1f,
                    startNs + frame * 8_333_333L, 0L);
        }

        assertEquals(60_750L, position);
    }

    @Test
    public void resetStopsMonotonicSeekFiltering() {
        PlaybackPositionTracker tracker = new PlaybackPositionTracker();
        tracker.startAfterSeek(60_000L, 1f, 10_000_000_000L);
        tracker.reset();

        assertEquals(123L, tracker.positionMillis(
                -1L, -1L, -1f, 11_000_000_000L, 123L));
        assertFalse(tracker.isAwaitingTimestampRebase());
    }
}
