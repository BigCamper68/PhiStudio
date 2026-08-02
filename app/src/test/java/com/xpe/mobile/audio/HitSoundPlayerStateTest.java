package com.xpe.mobile.audio;

import android.media.AudioTrack;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class HitSoundPlayerStateTest {
    @Test
    public void acceptsStaticTrackBeforeItsFirstWrite() {
        assertEquals(true,
                HitSoundPlayer.canWriteStaticDataState(AudioTrack.STATE_NO_STATIC_DATA));
    }

    @Test
    public void acceptsAnAlreadyInitializedTrack() {
        assertEquals(true,
                HitSoundPlayer.canWriteStaticDataState(AudioTrack.STATE_INITIALIZED));
    }

    @Test
    public void rejectsAnUninitializedTrack() {
        assertEquals(false,
                HitSoundPlayer.canWriteStaticDataState(AudioTrack.STATE_UNINITIALIZED));
    }
}
