package com.xpe.mobile.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class PcmHitSoundTest {
    @Test
    public void convertsMonoRateAndChannelsWithoutMovingTheOnset() {
        PcmHitSound source = new PcmHitSound(24_000, 1,
                new short[]{0, 1_000});

        PcmHitSound converted = source.convertTo(48_000, 2);

        assertEquals(4, converted.frameCount());
        assertFrame(converted, 0, 0, 0);
        assertFrame(converted, 1, 500, 500);
        assertFrame(converted, 2, 1_000, 1_000);
        assertFrame(converted, 3, 1_000, 1_000);
    }

    @Test
    public void averagesStereoWhenConvertingToMono() {
        PcmHitSound source = new PcmHitSound(48_000, 2,
                new short[]{-1_000, 1_000, 2_000, 4_000});

        PcmHitSound converted = source.convertTo(48_000, 1);

        assertEquals(2, converted.frameCount());
        assertEquals(0, converted.sample(0, 0));
        assertEquals(3_000, converted.sample(1, 0));
    }

    @Test
    public void keepsAlreadyMatchingImmutableSample() {
        PcmHitSound source = new PcmHitSound(48_000, 2,
                new short[]{1, 2});

        assertSame(source, source.convertTo(48_000, 2));
    }

    private static void assertFrame(PcmHitSound sound, int frame, int left, int right) {
        assertEquals(left, sound.sample(frame, 0));
        assertEquals(right, sound.sample(frame, 1));
    }
}
