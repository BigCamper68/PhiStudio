package com.xpe.mobile.audio;

import com.xpe.mobile.model.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

public class HitSoundStreamMixerTest {
    private static PcmHitSound tone(int value, int frames) {
        short[] samples = new short[frames * 2];
        Arrays.fill(samples, (short) value);
        return new PcmHitSound(48000, 2, samples);
    }

    private static HitSoundTimeline.Cue cue(NoteType... types) {
        ChartDocument chart = new ChartDocument();
        JudgeLine line = new JudgeLine();
        chart.judgeLines.add(line);
        for (NoteType type : types) {
            Note note = new Note();
            note.type = type;
            note.startTime = BeatTime.zero();
            line.notes.add(note);
        }
        return HitSoundTimeline.build(chart, 0, 0).cuesBetween(-1, 0).get(0);
    }

    private static int sample(byte[] output, int frame) {
        return (short) ((output[frame * 4] & 255) | output[frame * 4 + 1] << 8);
    }

    private static PcmHitSound impulse(int value) {
        short[] samples = new short[960 * 2]; samples[0] = samples[1] = (short) value;
        return new PcmHitSound(48000, 2, samples);
    }

    @Test public void aLiveChordHasOnePcmOnsetIncludingItsCustomSounds() {
        HitSoundStreamMixer mixer = new HitSoundStreamMixer(impulse(1000), impulse(3000), impulse(4000));
        mixer.setCustomSounds(java.util.Map.of("chord.wav", impulse(2000)));
        Note tap = new Note(), hold = new Note(), custom = new Note(); hold.type = NoteType.HOLD;
        custom.hitSound = "chord.wav";
        mixer.add(HitSoundTimeline.forNotes(Arrays.asList(tap, hold, custom, custom)));
        byte[] pcm = new byte[960 * 4]; mixer.render(pcm, 1);
        assertEquals(6000, sample(pcm, 0));
        for (int frame = 1; frame < 960; frame++) assertEquals("Unexpected delayed onset at " + frame, 0, sample(pcm, frame));
        assertFalse(mixer.hasAudio());
    }

    @Test public void missingCustomFilesKeepAllFallbackVoicesOnTheSamePcmFrame() {
        HitSoundStreamMixer mixer = new HitSoundStreamMixer(impulse(1000), impulse(3000), impulse(4000));
        Note tap = new Note(), hold = new Note(), flick = new Note();
        hold.type = NoteType.HOLD; flick.type = NoteType.FLICK;
        hold.hitSound = flick.hitSound = "unavailable.wav";
        mixer.add(HitSoundTimeline.forNotes(Arrays.asList(tap, hold, flick)));
        byte[] pcm = new byte[960 * 4]; mixer.render(pcm, 1);
        assertEquals(5000, sample(pcm, 0));
        for (int frame = 1; frame < 960; frame++) assertEquals("Unexpected delayed fallback at " + frame, 0, sample(pcm, frame));
        assertFalse(mixer.hasAudio());
    }

    @Test public void overlappingHitsSumBeforeClippingAndVolume() {
        HitSoundStreamMixer mixer = new HitSoundStreamMixer(tone(25000, 480), tone(-10000, 480), tone(0, 0));
        byte[] block = new byte[240 * 4];
        mixer.add(cue(NoteType.TAP, NoteType.HOLD));
        mixer.render(block, .5f);
        assertEquals(25000, sample(block, 0));
        mixer.add(cue(NoteType.FLICK));
        mixer.render(block, .5f);
        assertEquals(20000, sample(block, 0));
        mixer.render(block, .5f);
        assertEquals(-5000, sample(block, 0));
        assertFalse(mixer.hasAudio());
        mixer.render(block, 1);
        assertArrayEquals(new byte[block.length], block);
    }

    @Test public void tenThousandQueuedHitsHaveOnlyOneSampleTail() {
        HitSoundStreamMixer mixer = new HitSoundStreamMixer(tone(1000, 480), tone(0, 0), tone(0, 0));
        HitSoundTimeline.Cue cue = cue(NoteType.TAP);
        for (int i = 0; i < 10000; i++) mixer.add(cue);
        byte[] block = new byte[240 * 4];
        mixer.render(block, 1);
        assertEquals(32767, sample(block, 0));
        mixer.render(block, 1);
        assertFalse(mixer.hasAudio());
        mixer.render(block, 1);
        assertArrayEquals(new byte[block.length], block);
    }

    @Test public void pauseOrSeekClearsPendingAndPlayingSamples() {
        HitSoundStreamMixer mixer = new HitSoundStreamMixer(tone(1000, 480), tone(0, 0), tone(0, 0));
        byte[] block = new byte[240 * 4];
        mixer.add(cue(NoteType.TAP));
        mixer.render(block, 1);
        mixer.add(cue(NoteType.TAP));
        mixer.clear();
        assertFalse(mixer.hasAudio());
        mixer.render(block, 1);
        assertArrayEquals(new byte[block.length], block);
    }

    @Test public void suppliedAntineutrinoSpamEndsWithinSampleLength() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR");
        Assume.assumeNotNull(directory);
        Assume.assumeTrue("ANTINEUTRINO fixture not supplied",
                new java.io.File(directory, "ANTINEUTRINO WITCHCRAFT.zip").isFile());
        ChartDocument chart;
        try (ZipFile zip = new ZipFile(directory + "/ANTINEUTRINO WITCHCRAFT.zip")) {
            var entry = zip.stream().filter(e -> e.getName().endsWith(".json")
                    && !e.getName().endsWith("extra.json")).findFirst().orElseThrow();
            try (var input = zip.getInputStream(entry)) {
                chart = ChartDocument.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);
        for (int[] range : new int[][]{{552, 583}, {594, 610}}) {
            HitSoundStreamMixer mixer = new HitSoundStreamMixer(tone(100, 9600), tone(200, 9600), tone(300, 9600));
            long start = chart.beatToMillis(range[0]);
            long end = chart.beatToMillis(range[1]);
            int hits = 0;
            byte[] block = new byte[240 * 4];
            for (long ms = start; ms <= end + 5; ms += 5) {
                for (var cue : timeline.cuesBetween(ms == start ? start - 1 : ms - 5, Math.min(ms, end))) {
                    mixer.add(cue);
                    hits += cue.noteCount();
                }
                mixer.render(block, 1);
            }
            int tailBlocks = 0;
            while (mixer.hasAudio() && tailBlocks <= 40) { mixer.render(block, 1); tailBlocks++; }
            assertEquals(range[0] == 552 ? 469 : 258, hits);
            assertTrue("Audio tail must not grow with note count", tailBlocks <= 40);
            assertFalse(mixer.hasAudio());
            System.out.println("ANTINEUTRINO " + range[0] + "-" + range[1] + ": " + hits
                    + " hits, tail <= " + tailBlocks * 5 + "ms for a 200ms sample");
        }
    }
}
