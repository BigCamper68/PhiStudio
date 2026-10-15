package com.xpe.mobile.audio;

import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PlaybackPcmFilesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void processStartupCleansOrphansButFurtherLoadsKeepCurrentSessions() throws Exception {
        File directory = new File(temp.getRoot(), "audio-playback"); assertTrue(directory.mkdir());
        File abandoned = new File(directory, "phistudio-audio-abandoned.pcm"); assertTrue(abandoned.createNewFile());
        File unrelated = new File(directory, "keep.txt"); assertTrue(unrelated.createNewFile());
        File first = PlaybackPcmFiles.create(temp.getRoot());
        assertFalse(abandoned.exists()); assertTrue(unrelated.exists());
        File second = PlaybackPcmFiles.create(temp.getRoot());
        assertTrue(first.isFile()); assertTrue(second.isFile()); assertNotEquals(first, second);
    }

    @Test(expected = IOException.class) public void unusablePrivateStorageReportsAnIoFailure() throws Exception {
        File occupied = new File(temp.getRoot(), "audio-playback"); assertTrue(occupied.createNewFile());
        PlaybackPcmFiles.create(temp.getRoot());
    }
}
