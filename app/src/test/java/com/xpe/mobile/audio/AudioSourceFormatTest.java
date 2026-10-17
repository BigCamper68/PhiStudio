package com.xpe.mobile.audio;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AudioSourceFormatTest {
    @Test
    public void detectsMp3ByExtensionOrMimeType() {
        assertTrue(AudioSourceFormat.isMp3("file:///music/track.MP3", null));
        assertTrue(AudioSourceFormat.isMp3("content://provider/42", "audio/mpeg"));
        assertTrue(AudioSourceFormat.isMp3("track.mp3?download=1", "application/octet-stream"));
    }

    @Test
    public void doesNotRouteOggOrM4aThroughMp3Gate() {
        assertFalse(AudioSourceFormat.isMp3("track.ogg", "audio/ogg"));
        assertFalse(AudioSourceFormat.isMp3("track.m4a", "audio/mp4"));
        assertFalse(AudioSourceFormat.isMp3("track.aac", "audio/aac"));
    }
}
