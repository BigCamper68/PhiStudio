package com.xpe.mobile.video;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoExportOptionsTest {
    private static VideoExportOptions options(int width, int height, int fps, int bitrate, long start, long end) {
        return new VideoExportOptions(width, height, fps, bitrate, start, end, true, true, 1, 1, true, true);
    }
    @Test public void fractionalRangeKeepsLastFrameAndRegularTimestamps() {
        VideoExportOptions settings = options(1920, 1080, 60, 12_000_000, 1234, 2251);
        assertEquals(62, settings.frameCount());
        assertEquals(0, settings.frameTimeUs(0));
        assertEquals(16_666, settings.frameTimeUs(1));
        assertEquals(1_033_333, settings.durationUs());
        for (long frame = 1; frame < settings.frameCount(); frame++)
            assertTrue(settings.frameTimeUs(frame) > settings.frameTimeUs(frame - 1));
    }
    @Test public void hourLongVideoHasNoAccumulatedTimestampDrift() {
        VideoExportOptions settings = options(1280, 720, 30, 8_000_000, 0, 3_600_000);
        assertEquals(108000, settings.frameCount());
        assertEquals(3_600_000_000L, settings.durationUs());
        assertEquals(3_599_966_666L, settings.frameTimeUs(settings.frameCount() - 1));
    }
    @Test public void chartClockKeepsFrameMicrosecondsAndAppliesBothOffsets() {
        VideoExportOptions settings = options(1920, 1080, 60, 12_000_000, 300010, 301000);
        assertEquals(300.0, settings.chartSeconds(0, 60, -50), 0.0);
        assertEquals(300.016666, settings.chartSeconds(1, 60, -50), 0.0);
        assertEquals(300.033333, settings.chartSeconds(2, 60, -50), 0.0);
        assertNotEquals(300.017, settings.chartSeconds(1, 60, -50), 0.0);
        assertEquals(0.0, options(1920, 1080, 60, 12_000_000, 0, 1000)
                .chartSeconds(0, 60, 50), 0.0);
    }
    @Test public void estimateIncludesAudioOnlyWhenSelected() {
        VideoExportOptions silent = new VideoExportOptions(1280, 720, 30, 8_000_000, 0, 1000,
                false, false, 0, 0, false, true);
        assertEquals(1_000_000, silent.estimatedBytes());
        assertEquals(1_024_000, options(1280, 720, 30, 8_000_000, 0, 1000).estimatedBytes());
    }
    @Test public void tinyRangeStillHasOneCompleteFrame() {
        VideoExportOptions settings = options(960, 540, 30, 4_000_000, 5000, 5001);
        assertEquals(1, settings.frameCount()); assertEquals(33_333, settings.durationUs());
    }
    @Test public void rejectsOddOrUnsafeSizeInvalidBitrateAndReversedRange() {
        assertThrows(IllegalArgumentException.class, () -> options(1919, 1080, 60, 12_000_000, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> options(1920, 1081, 60, 12_000_000, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> options(4096, 2160, 60, 12_000_000, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> options(1920, 1080, 59, 12_000_000, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> options(1920, 1080, 60, 900_000, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> options(1920, 1080, 60, 12_000_000, 1000, 999));
        assertThrows(IllegalArgumentException.class, () -> options(1920, 1080, 60, 12_000_000, -1, 1000));
        assertThrows(IllegalArgumentException.class, () -> options(1920, 1080, 60, 12_000_000, 0, Long.MAX_VALUE));
    }
    @Test public void rejectsNonFiniteGain() {
        assertThrows(IllegalArgumentException.class, () -> new VideoExportOptions(1280, 720, 30, 8_000_000,
                0, 1000, true, true, Float.NaN, 1, true, true));
    }
}
