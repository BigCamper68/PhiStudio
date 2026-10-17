package com.xpe.mobile.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CustomHitSoundLimitsTest {
    @Test public void durationAndPcmBudgetAreEnforcedBeforeWritingAnotherBuffer() throws Exception {
        AudioPcmDecoder.checkLimits(48000L * 30, 48000, 4,
                CustomHitSoundLoader.MAX_DECODED_BYTES, CustomHitSoundLoader.MAX_DURATION_MS);
        assertThrows(java.io.IOException.class, () -> AudioPcmDecoder.checkLimits(
                48000L * 30 + 1, 48000, 4, CustomHitSoundLoader.MAX_DECODED_BYTES,
                CustomHitSoundLoader.MAX_DURATION_MS));
        assertThrows(java.io.IOException.class, () -> AudioPcmDecoder.checkLimits(
                CustomHitSoundLoader.MAX_DECODED_BYTES / 4 + 1, 192000, 4,
                CustomHitSoundLoader.MAX_DECODED_BYTES, CustomHitSoundLoader.MAX_DURATION_MS));
    }
}
