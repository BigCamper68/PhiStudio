package com.xpe.mobile.audio;

import com.xpe.mobile.model.*;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScheduledHitSoundMixerTest {
    @Test public void hitSamplesStayByteExactAtAllSpeedsAfterSeekAndOffset() {
        for (int rate : new int[]{44100, 48000}) for (int channels : new int[]{1, 2}) {
            short[] hit = new short[rate / 50 * channels]; // fixed 20ms sample, not an impulse
            for (int i = 0; i < hit.length; i++) hit[i] = (short)(100 + i % 731);
            for (float speed : new float[]{.25f, .5f, .75f, 1f, 1.5f, 2f}) {
                PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), rate, channels, rate * 5L);
                PcmHitSound[] sounds = new PcmHitSound[NoteType.values().length];
                Arrays.fill(sounds, new PcmHitSound(rate, channels, hit));
                ChartDocument chart = new ChartDocument();
                BpmChange bpm = new BpmChange(); bpm.bpm = 60; chart.bpmChanges.add(bpm);
                JudgeLine line = new JudgeLine(); chart.judgeLines.add(line);
                for (NoteType type : NoteType.values()) {
                    Note note = new Note(); note.type = type;
                    note.startTime = BeatTime.fromDouble(1 + type.ordinal() * .3, 100000);
                    note.endTime = note.startTime;
                    line.notes.add(note);
                }
                long startFrame = Math.round(.5 * rate);
                ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset,
                        HitSoundTimeline.build(chart, 0, 0), 137, sounds, startFrame, speed);
                int frames = (int)Math.round(3 * rate / speed);
                byte[] output = new byte[frames * channels * 2];
                byte[] expected = new byte[output.length];
                for (NoteType type : NoteType.values()) {
                    long sourceFrame = Math.round((1137 + type.ordinal() * 300) * rate / 1000.0);
                    int at = (int)Math.round((sourceFrame - startFrame) / (double)speed);
                    for (int i = 0; i < hit.length; i++) {
                        int index = at * channels * 2 + i * 2;
                        expected[index] = (byte)hit[i]; expected[index + 1] = (byte)(hit[i] >>> 8);
                    }
                }
                for (int frame = 0; frame < frames;) {
                    int count = Math.min(337, frames - frame);
                    byte[] block = new byte[count * channels * 2];
                    mixer.mix(block, block.length, startFrame + frame, 0, 1);
                    System.arraycopy(block, 0, output, frame * channels * 2, block.length);
                    frame += count;
                }
                assertArrayEquals("hit duration/pitch at " + speed + "x", expected, output);
            }
        }
    }

    static HitSoundTimeline timeline(double... millis) {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 60;
        chart.bpmChanges.add(bpm);
        JudgeLine line = new JudgeLine(); chart.judgeLines.add(line);
        for (double ms : millis) {
            Note note = new Note(); note.type = NoteType.TAP;
            note.startTime = BeatTime.fromDouble(ms / 1000, 100000);
            line.notes.add(note);
        }
        return HitSoundTimeline.build(chart, 0, 0);
    }

    static PcmHitSound[] sounds(int rate, int channels, short... values) {
        PcmHitSound[] sounds = new PcmHitSound[NoteType.values().length];
        sounds[NoteType.TAP.ordinal()] = new PcmHitSound(rate, channels, values);
        return sounds;
    }

    static int sample(byte[] pcm, int frame, int channels) {
        int i = frame * channels * 2;
        return (short) ((pcm[i] & 255) | pcm[i + 1] << 8);
    }

    @Test public void exactFramesIndependentOfUiCallbacksAndBufferBoundaries() {
        for (int rate : new int[]{44100, 48000}) for (int channels : new int[]{1, 2}) {
            PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), rate, channels, rate);
            short[] impulse = new short[channels]; Arrays.fill(impulse, (short) 1000);
            HitSoundTimeline schedule = timeline(10, 10, 13, 31);
            byte[] pcm = new byte[rate / 20 * channels * 2];
            ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, schedule, 7,
                    sounds(rate, channels, impulse), 0);
            mixer.mix(pcm, pcm.length, 0, 1, 1);
            int first = (int) Math.round(.017 * rate), second = (int) Math.round(.020 * rate);
            int third = (int) Math.round(.038 * rate);
            for (int frame = 0; frame < pcm.length / (channels * 2); frame++) {
                assertEquals(frame == first ? 2000 : frame == second || frame == third ? 1000 : 0,
                        sample(pcm, frame, channels));
            }
            ScheduledHitSoundMixer splitMixer = new ScheduledHitSoundMixer(asset, schedule, 7,
                    sounds(rate, channels, impulse), 0);
            byte[] split = new byte[pcm.length];
            int frame = 0;
            while (frame < pcm.length / (channels * 2)) {
                int n = Math.min(137, pcm.length / (channels * 2) - frame);
                byte[] block = new byte[n * channels * 2];
                splitMixer.mix(block, block.length, frame, 1, 1);
                System.arraycopy(block, 0, split, frame * channels * 2, block.length);
                frame += n;
            }
            assertArrayEquals(pcm, split);
        }
    }

    @Test public void seekDiscardsOldHitsAndTailsAndKeepsMusicPosition() {
        PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), 1000, 1, 1000);
        short[] sound = new short[20]; Arrays.fill(sound, (short) 1000);
        ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, timeline(10, 100, 300), 0,
                sounds(1000, 1, sound), 95);
        byte[] block = new byte[20 * 2];
        mixer.mix(block, block.length, 95, 0, 1);
        assertEquals(0, sample(block, 0, 1));
        assertEquals(1000, sample(block, 5, 1));
        block = new byte[20 * 2];
        mixer.mix(block, block.length, 200, 0, 1);
        assertArrayEquals(new byte[40], block);
        block = new byte[20 * 2];
        mixer.mix(block, block.length, 0, 0, 1);
        assertEquals(1000, sample(block, 10, 1));
    }

    @Test public void negativeOffsetsDontReplayPreAudioHitsAtZero() {
        PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), 1000, 1, 1000);
        ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, timeline(0, 10, 30), -20,
                sounds(1000, 1, (short) 1000), 0);
        byte[] block = new byte[40]; mixer.mix(block, block.length, 0, 1, 1);
        for (int frame = 0; frame < 20; frame++) assertEquals(frame == 10 ? 1000 : 0, sample(block, frame, 1));
    }

    @Test public void musicAndHitsHaveIndependentVolumesAndOnlyFinalSaturation() {
        PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), 1000, 1, 1000);
        ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, timeline(0, 0), 0,
                sounds(1000, 1, (short) 25000), 0);
        byte[] music = {(byte) 0x10, (byte) 0x27}; // 10000
        mixer.mix(music, 2, 0, .5f, .5f);
        assertEquals(30000, sample(music, 0, 1));
        mixer = new ScheduledHitSoundMixer(asset, timeline(0), 0, sounds(1000, 1, (short) 1000), 0);
        music = new byte[]{16, 39}; mixer.mix(music, 2, 0, 0, 1);
        assertEquals(1000, sample(music, 0, 1));
    }

    @Test public void suppliedSpamHitsLandOnExactMusicFramesAfterSeek() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR");
        org.junit.Assume.assumeNotNull(directory);
        org.junit.Assume.assumeTrue("ANTINEUTRINO fixture not supplied",
                new File(directory, "ANTINEUTRINO WITCHCRAFT.zip").isFile());
        ChartDocument chart;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(directory + "/ANTINEUTRINO WITCHCRAFT.zip")) {
            var entry = zip.stream().filter(e -> e.getName().endsWith(".json")
                    && !e.getName().endsWith("extra.json")).findFirst().orElseThrow();
            try (var input = zip.getInputStream(entry)) {
                chart = ChartDocument.fromJson(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);
        for (int rate : new int[]{44100, 48000}) for (int[] range : new int[][]{{552, 583}, {594, 610}})
                for (float speed : new float[]{.5f, .75f, 1f}) {
            long startMs = chart.beatToMillis(range[0]), endMs = chart.beatToMillis(range[1]);
            long offset = 137; // non-zero package+chart offset; deliberately not frame-aligned at 44.1kHz
            long startFrame = Math.round((startMs + offset) * rate / 1000.0);
            long endFrame = Math.round((endMs + offset) * rate / 1000.0);
            int frames = Math.toIntExact(Math.round((endFrame - startFrame) / (double)speed) + 1);
            int[] expected = new int[frames];
            int hits = 0;
            for (var cue : timeline.cuesBetween(startMs - 1, endMs)) {
                long sourceFrame = Math.round((cue.timeMs() + offset) * rate / 1000.0);
                int index = Math.toIntExact(Math.round((sourceFrame - startFrame) / (double)speed));
                expected[index] += cue.noteCount(); hits += cue.noteCount();
            }
            PcmHitSound[] impulses = new PcmHitSound[NoteType.values().length];
            Arrays.fill(impulses, new PcmHitSound(rate, 1, new short[]{1}));
            PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), rate, 1, endFrame + rate);
            ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, timeline, offset, impulses, startFrame, speed);
            int position = 0, emitted = 0;
            while (position < frames) {
                int count = Math.min(337, frames - position);
                byte[] block = new byte[count * 2];
                mixer.mix(block, block.length, startFrame + position, 0, 1);
                for (int i = 0; i < count; i++) {
                    int actual = sample(block, i, 1);
                    assertEquals("music frame " + (startFrame + position + i), expected[position + i], actual);
                    emitted += actual;
                }
                position += count;
            }
            assertEquals(range[0] == 552 ? 469 : 258, hits);
            assertEquals(hits, emitted);
            System.out.println("Scheduled ANTINEUTRINO " + range[0] + "-" + range[1]
                    + ", " + rate + " Hz, " + speed + "x: " + emitted
                    + " hits at exact output frames after seek");
        }
    }
}
