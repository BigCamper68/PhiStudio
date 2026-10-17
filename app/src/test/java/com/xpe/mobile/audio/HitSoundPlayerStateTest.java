package com.xpe.mobile.audio;

import android.media.AudioTrack;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class HitSoundPlayerStateTest {
    @Test
    public void rejectsStaticTrackWithoutData() {
        assertEquals(false,
                HitSoundPlayer.canWriteStreamDataState(AudioTrack.STATE_NO_STATIC_DATA));
    }

    @Test
    public void acceptsAnAlreadyInitializedTrack() {
        assertEquals(true,
                HitSoundPlayer.canWriteStreamDataState(AudioTrack.STATE_INITIALIZED));
    }

    @Test
    public void rejectsAnUninitializedTrack() {
        assertEquals(false,
                HitSoundPlayer.canWriteStreamDataState(AudioTrack.STATE_UNINITIALIZED));
    }
}
