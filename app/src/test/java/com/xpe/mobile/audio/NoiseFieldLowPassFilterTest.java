package com.xpe.mobile.audio;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoiseFieldLowPassFilterTest {
    @Test public void bypassAndReleasePreservePcmExactly() {
        NoiseFieldLowPassFilter filter = new NoiseFieldLowPassFilter(48000, 2);
        byte[] dry = tone(48000, 2, 10000, 6000), pcm = dry.clone();
        filter.process(pcm, pcm.length, false); assertArrayEquals(dry, pcm);
        filter.process(pcm, pcm.length, true); assertFalse(Arrays.equals(dry, pcm));
        pcm = dry.clone(); filter.process(pcm, pcm.length, false); assertArrayEquals(dry, pcm);
    }

    @Test public void firstEntryHasNativeCutoffAndIndependentStereoChannels() {
        for (int rate : new int[]{32000, 44100, 48000}) {
            byte[] low = tone(rate, 1, 200, rate), high = tone(rate, 1, 10000, rate);
            byte[] filteredLow = low.clone(), filteredHigh = high.clone();
            new NoiseFieldLowPassFilter(rate, 1).process(filteredLow, filteredLow.length, true);
            new NoiseFieldLowPassFilter(rate, 1).process(filteredHigh, filteredHigh.length, true);
            assertEquals(1, rms(filteredLow, rate / 4) / rms(low, rate / 4), .01);
            assertTrue(rms(filteredHigh, rate / 4) / rms(high, rate / 4) < .025);
        }
        byte[] stereo = tone(48000, 2, 10000, 48000);
        new NoiseFieldLowPassFilter(48000, 2).process(stereo, stereo.length, true);
        for (int i = 0; i < 48000; i++) assertEquals(0, sample(stereo, i * 2 + 1));
    }

    @Test public void releaseReentryAndFastTogglesAreIndependentOfReadBlockSize() {
        byte[] source = tone(48000, 1, 7500, 48000);
        byte[] whole = process(source, 48000), blocks = process(source, 113);
        assertArrayEquals(whole, blocks);
        assertTrue(rms(whole, 1000) > 0);
        // Fresh workers start with the initial preset again after pause/seek/Retry.
        byte[] first = Arrays.copyOf(source, 2000), fresh = first.clone();
        new NoiseFieldLowPassFilter(48000, 1).process(first, first.length, true);
        new NoiseFieldLowPassFilter(48000, 1).process(fresh, fresh.length, true);
        assertArrayEquals(first, fresh);
    }

    private static byte[] process(byte[] input, int block) {
        byte[] result = new byte[input.length];
        NoiseFieldLowPassFilter filter = new NoiseFieldLowPassFilter(48000, 1);
        int[] boundaries = {0, 2000, 3500, 4000, 12000, 13000, 24000, 48000};
        boolean[] touching = {false, true, false, true, false, true, false};
        for (int k = 0; k < touching.length; k++) for (int pos = boundaries[k]; pos < boundaries[k+1];) {
            int end = Math.min(boundaries[k+1], pos + block);
            byte[] pcm = Arrays.copyOfRange(input, pos * 2, end * 2);
            filter.process(pcm, pcm.length, touching[k]); System.arraycopy(pcm, 0, result, pos * 2, pcm.length);
            pos = end;
        }
        return result;
    }
    static byte[] tone(int rate, int channels, double frequency, int frames) {
        byte[] pcm = new byte[frames * channels * 2];
        for (int i = 0; i < frames; i++) {
            int value = (int) Math.round(12000 * Math.sin(2 * Math.PI * frequency * i / rate));
            pcm[i * channels * 2] = (byte) value; pcm[i * channels * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }
    static short sample(byte[] pcm, int i) { return (short) ((pcm[i*2]&255) | (pcm[i*2+1]<<8)); }
    static double rms(byte[] pcm, int skip) {
        double sum = 0; int count = pcm.length / 2;
        for (int i = skip; i < count; i++) { double value = sample(pcm,i); sum += value * value; }
        return Math.sqrt(sum / (count - skip));
    }
}
