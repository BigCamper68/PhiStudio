package com.xpe.mobile.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PlaybackSeekCoordinatorTest {
    @Test
    public void startsImmediatelyWhenSeekReportsTargetNeighborhood() {
        PlaybackSeekCoordinator coordinator = new PlaybackSeekCoordinator();
        coordinator.begin();

        assertEquals(PlaybackSeekCoordinator.Action.START,
                coordinator.onSeekComplete(10_000L, 9_800L));
        assertEquals(0, coordinator.retryCount());
    }

    @Test
    public void retriesSeveralStaleCallbacksButCannotRemainPendingForever() {
        PlaybackSeekCoordinator coordinator = new PlaybackSeekCoordinator();
        coordinator.begin();

        assertEquals(PlaybackSeekCoordinator.Action.RETRY,
                coordinator.onSeekComplete(10_000L, 0L));
        assertEquals(1, coordinator.retryCount());
        assertEquals(PlaybackSeekCoordinator.Action.RETRY,
                coordinator.onSeekComplete(10_000L, 0L));
        assertEquals(2, coordinator.retryCount());
        assertEquals(PlaybackSeekCoordinator.Action.RETRY,
                coordinator.onSeekComplete(10_000L, 0L));
        assertEquals(3, coordinator.retryCount());
        assertEquals(PlaybackSeekCoordinator.Action.START,
                coordinator.onSeekComplete(10_000L, 0L));
        assertEquals(3, coordinator.retryCount());
    }

    @Test
    public void beginResetsRetryBudgetForNextPlaybackRequest() {
        PlaybackSeekCoordinator coordinator = new PlaybackSeekCoordinator();
        coordinator.begin();
        assertEquals(PlaybackSeekCoordinator.Action.RETRY,
                coordinator.onSeekComplete(10_000L, 0L));

        coordinator.begin();

        assertEquals(PlaybackSeekCoordinator.Action.RETRY,
                coordinator.onSeekComplete(20_000L, 0L));
        assertEquals(1, coordinator.retryCount());
    }
}
