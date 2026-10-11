package com.xpe.mobile.audio;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import static org.junit.Assert.*;

public final class PcmMusicStreamTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static void writeSample(OutputStream output, int value) throws IOException {
        output.write(value & 255); output.write(value >> 8 & 255);
    }

    private byte[] render(PcmAudioAsset asset, long start, float speed) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(asset.file, "r")) {
            PcmMusicStream stream = new PcmMusicStream(input, asset, start, speed);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[337 * asset.bytesPerFrame()];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    @Test public void oneXIsByteExactAndSeeksToSourceNotOutputPosition() throws Exception {
        File file = temporary.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < 1500; i++) writeSample(out, i);
        }
        PcmAudioAsset asset = new PcmAudioAsset(file, 1000, 1, 1500);
        byte[] full = java.nio.file.Files.readAllBytes(file.toPath());
        assertArrayEquals(full, render(asset, 0, 1));
        assertArrayEquals(java.util.Arrays.copyOfRange(full, 714, full.length), render(asset, 357, 1));
        assertEquals(0, render(asset, 1500, .5f).length);
    }

    @Test public void slowMusicKeepsPitchStereoAndExactDurationAt44100And48000() throws Exception {
        for (int rate : new int[]{44100, 48000}) for (int channels : new int[]{1, 2}) {
            File file = temporary.newFile();
            int frames = rate * 2;
            try (FileOutputStream out = new FileOutputStream(file)) {
                for (int i = 0; i < frames; i++) {
                    int sample = (int) Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 10000);
                    writeSample(out, sample);
                    // Different channel gain, without cancelling Sonic's mono pitch analysis.
                    if (channels == 2) writeSample(out, sample / 2);
                }
            }
            PcmAudioAsset asset = new PcmAudioAsset(file, rate, channels, frames);
            for (float speed : new float[]{.25f, .5f, .75f, 1f, 1.5f, 2f}) {
                long before = System.nanoTime();
                byte[] data = render(asset, 0, speed);
                int outputFrames = data.length / asset.bytesPerFrame();
                assertEquals(Math.round(frames / (double) speed), outputFrames);
                int from = rate / 10, to = outputFrames - rate / 10, crossings = 0;
                int previous = ScheduledHitSoundMixerTest.sample(data, from - 1, channels);
                for (int i = from; i < to; i++) {
                    int sample = ScheduledHitSoundMixerTest.sample(data, i, channels);
                    if (previous <= 0 && sample > 0) crossings++;
                    previous = sample;
                    if (channels == 2) {
                        int offset = i * 4 + 2;
                        int second = (short)((data[offset] & 255) | data[offset + 1] << 8);
                        assertEquals(sample, second * 2, 3);
                    }
                }
                double frequency = crossings * (double) rate / (to - from);
                assertEquals("pitch at " + speed + "x", 440, frequency, 3);
                System.out.println("Music stretch " + rate + " Hz/" + channels + "ch "
                        + speed + "x: " + frequency + " Hz, " + outputFrames
                        + " frames, " + (System.nanoTime()-before)/1e6 + " ms");
            }
        }
    }

    @Test public void shortTailUsesExactRemainingDurationAtSlowSpeed() throws Exception {
        File file = temporary.newFile();
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[48000 * 4]); }
        PcmAudioAsset asset = new PcmAudioAsset(file, 48000, 2, 48000);
        for (float speed : new float[]{.5f, .75f}) {
            assertEquals(Math.round(7 / (double)speed) * 4, render(asset, 47993, speed).length);
        }
    }
}
