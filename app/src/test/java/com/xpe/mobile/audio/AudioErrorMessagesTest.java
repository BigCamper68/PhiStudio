package com.xpe.mobile.audio;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioErrorMessagesTest {
    @Test public void genericDecodeWrapperRetainsVendorFailure() {
        String message = AudioErrorMessages.describe(new IOException("Audio decoder failed",
                new IllegalStateException("codec vendor.example unavailable")));
        assertTrue(message.contains("Audio decoder failed"));
        assertTrue(message.contains("codec vendor.example unavailable"));
    }

    @Test public void missingMessagesStillNameTheActualFailure() {
        assertEquals("IllegalArgumentException", AudioErrorMessages.describe(
                new IOException(" ", new IllegalArgumentException())));
    }

    @Test public void repeatedMessagesAndCyclicCausesAreBounded() {
        IOException first = new IOException("route unavailable"), second = new IOException("route unavailable");
        first.initCause(second); second.initCause(first);
        assertEquals("route unavailable", AudioErrorMessages.describe(first));
    }

    @Test public void nativeOpenFailurePrecedesTheLongPrivateFilePath() {
        IOException failure = new IOException("/data/user/0/com.xpe.mobile/cache/phistudio-audio-692738850477399013.pcm",
                new IOException("open failed: ENOENT (No such file or directory)"));
        String description = AudioErrorMessages.describe(failure);
        assertTrue(description.startsWith("open failed: ENOENT"));
        assertTrue(description.contains("phistudio-audio-692738850477399013.pcm"));
    }
}
