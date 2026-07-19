package com.xpe.mobile.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BeatTimeTest {
    @Test
    public void exactAdditionAndSubtractionPreserveRationalOffsets() {
        BeatTime first = new BeatTime(1, 1, 3);
        BeatTime second = new BeatTime(2, 1, 6);
        assertEquals(new BeatTime(3, 1, 2), first.plus(second));
        assertEquals(new BeatTime(0, 5, 6), second.minus(first));
        assertEquals(first, first.plus(second).minus(second));
    }

    @Test
    public void normalizesFractionsAndNegativeValues() {
        assertEquals(new BeatTime(1, 1, 2), new BeatTime(1, 2, 4));
        assertEquals(new BeatTime(-1, 1, 2), new BeatTime(0, -1, 2));
        assertEquals("-1:1/2", new BeatTime(0, -1, 2).toString());
    }

    @Test
    public void parsesCanonicalBeatText() {
        assertEquals(new BeatTime(12, 1, 2), BeatTime.parse(" 12:2/4 "));
    }

    @Test
    public void flexibleParserAndInterpolationRemainExact() {
        assertEquals(new BeatTime(12, 1, 2), BeatTime.parseFlexible("12.5"));
        assertEquals(new BeatTime(-1, 3, 4), BeatTime.parseFlexible("-0.25"));
        assertEquals(new BeatTime(2, 1, 3), BeatTime.interpolate(
                new BeatTime(1, 0, 1), new BeatTime(3, 0, 1), 2, 3));
        assertEquals(new BeatTime(1, 7, 12), BeatTime.interpolate(
                new BeatTime(1, 1, 4), new BeatTime(2, 1, 4), 1, 3));
    }

    @Test
    public void comparesLargeCloseFractionsExactly() {
        BeatTime base = new BeatTime(2_000_000_000, 0, 1);
        BeatTime slightlyLater = new BeatTime(2_000_000_000, 1, 2_000_000_000);

        assertEquals(base.toDouble(), slightlyLater.toDouble(), 0.0);
        assertTrue(base.compareTo(slightlyLater) < 0);
        assertTrue(slightlyLater.compareTo(base) > 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedBeatText() {
        BeatTime.parse("12.5");
    }
}
