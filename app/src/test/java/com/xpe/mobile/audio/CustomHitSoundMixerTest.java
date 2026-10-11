package com.xpe.mobile.audio;

import com.xpe.mobile.model.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

public final class CustomHitSoundMixerTest {
    static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 60; chart.bpmChanges.add(bpm);
        chart.judgeLines.add(new JudgeLine());
        return chart;
    }

    static Note note(ChartDocument chart, NoteType type, String sound, int ms) {
        Note note = new Note(); note.type = type; note.hitSound = sound;
        note.startTime = BeatTime.fromDouble(ms / 1000.0, 100000);
        note.endTime = BeatTime.fromDouble((ms + 200) / 1000.0, 100000);
        chart.judgeLines.get(0).notes.add(note);
        return note;
    }

    static PcmHitSound[] builtins(int rate) {
        PcmHitSound[] sounds = new PcmHitSound[NoteType.values().length];
        sounds[NoteType.TAP.ordinal()] = new PcmHitSound(rate, 1, new short[]{100});
        sounds[NoteType.HOLD.ordinal()] = sounds[NoteType.TAP.ordinal()];
        sounds[NoteType.FLICK.ordinal()] = new PcmHitSound(rate, 1, new short[]{200});
        sounds[NoteType.DRAG.ordinal()] = new PcmHitSound(rate, 1, new short[]{300});
        return sounds;
    }

    @Test public void atomicCueKeepsDefaultOverridesCustomMultiplicitiesAndFakeExclusion() {
        ChartDocument chart = chart();
        note(chart, NoteType.DRAG, "tap.mp3", 100);
        note(chart, NoteType.TAP, "drag.mp3", 100);
        note(chart, NoteType.HOLD, "sounds/kick.ogg", 100);
        note(chart, NoteType.FLICK, "sounds/kick.ogg", 100);
        note(chart, NoteType.TAP, "sounds/kick.ogg", 100).fake = true;
        note(chart, NoteType.HOLD, null, 100);
        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);
        assertEquals(5, timeline.size()); assertEquals(1, timeline.cueCount());
        var cue = timeline.allCues().get(0);
        assertEquals(5, cue.noteCount());
        assertEquals(1, cue.count(NoteType.TAP));
        assertEquals(1, cue.count(NoteType.DRAG));
        assertEquals(1, cue.count(NoteType.HOLD));
        assertEquals(0, cue.count(NoteType.FLICK));
        assertEquals(Set.of("sounds/kick.ogg"), timeline.customPaths());
        assertEquals(1, cue.customSounds().size());
        var custom = cue.customSounds().get(0);
        assertEquals(2, custom.count());
        assertEquals(1, custom.fallbackCount(NoteType.HOLD));
        assertEquals(1, custom.fallbackCount(NoteType.FLICK));
        assertTrue(HitSoundTimeline.build(chart, 0, -30).cueCount() > 1);
    }

    @Test public void defaultAndTwoCustomFilesMixTogetherAtExactFramesWithoutSlowingSamples() {
        ChartDocument chart = chart();
        note(chart, NoteType.TAP, null, 100);
        note(chart, NoteType.DRAG, "sounds/a.ogg", 100);
        note(chart, NoteType.FLICK, "sounds/a.ogg", 100);
        note(chart, NoteType.HOLD, "sounds/b.wav", 100);
        var custom = Map.of(
                "sounds/a.ogg", new PcmHitSound(1000, 1, new short[]{1000, 2000, 3000, 4000}),
                "sounds/b.wav", new PcmHitSound(1000, 1, new short[]{10, 20}));
        HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);
        PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), 1000, 1, 1000);
        for (float speed : new float[]{.5f, .75f, 1f}) {
            ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, timeline, 17,
                    builtins(1000), custom, 50, speed);
            byte[] output = new byte[400 * 2];
            // Split in the middle of the hit sample.
            int start = Math.round(67 / speed);
            int cut = start + 2;
            byte[] first = new byte[cut * 2];
            mixer.mix(first, first.length, 50, 1, 1);
            byte[] second = new byte[output.length - first.length];
            mixer.mix(second, second.length, 50 + cut, 1, 1);
            System.arraycopy(first, 0, output, 0, first.length);
            System.arraycopy(second, 0, output, first.length, second.length);
            int[] expected = {2110, 4020, 6000, 8000};
            for (int frame = 0; frame < 400; frame++) {
                assertEquals(frame >= start && frame < start + 4 ? expected[frame-start] : 0,
                        ScheduledHitSoundMixerTest.sample(output, frame, 1));
            }
            // Seek discards custom tails just like standard tails.
            byte[] after = new byte[20];
            mixer.mix(after, after.length, 500, 1, 1);
            assertArrayEquals(new byte[20], after);
        }
    }

    @Test public void missingCustomFileFallsBackPerOriginalTypeWithoutDoublePlayingLoadedCustom() {
        ChartDocument chart = chart();
        note(chart, NoteType.HOLD, "missing.ogg", 0);
        note(chart, NoteType.DRAG, "missing.ogg", 0);
        note(chart, NoteType.TAP, "loaded.ogg", 0);
        PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), 1000, 1, 1000);
        ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, HitSoundTimeline.build(chart, 0, 0), 0,
                builtins(1000), Map.of("loaded.ogg", new PcmHitSound(1000, 1, new short[]{1000})), 0, 1);
        byte[] output = new byte[2]; mixer.mix(output, 2, 0, 1, 1);
        assertEquals(1400, ScheduledHitSoundMixerTest.sample(output, 0, 1));
    }

    @Test public void streamWithoutMusicSupportsCustomOverlapClearAndMissingFallback() {
        PcmHitSound[] builtins = builtins(48000);
        HitSoundStreamMixer mixer = new HitSoundStreamMixer(builtins[NoteType.TAP.ordinal()],
                builtins[NoteType.FLICK.ordinal()], builtins[NoteType.DRAG.ordinal()]);
        mixer.setCustomSounds(Map.of("custom.wav", new PcmHitSound(48000, 1, new short[]{1000, 2000})));
        ChartDocument chart = chart();
        note(chart, NoteType.TAP, "custom.wav", 0);
        note(chart, NoteType.FLICK, "custom.wav", 0);
        note(chart, NoteType.DRAG, "missing.wav", 0);
        mixer.add(HitSoundTimeline.build(chart, 0, 0).allCues().get(0));
        byte[] block = new byte[HitSoundStreamMixer.BLOCK_FRAMES * 4];
        mixer.render(block, 1);
        assertEquals(2300, ScheduledHitSoundMixerTest.sample(block, 0, 2));
        assertEquals(4000, ScheduledHitSoundMixerTest.sample(block, 1, 2));
        assertFalse(mixer.hasAudio());
        mixer.add(HitSoundTimeline.build(chart, 0, 0).allCues().get(0)); mixer.clear();
        mixer.render(block, 1); assertArrayEquals(new byte[block.length], block);
    }

    @Test public void actualAconsmaRoutesAll43PerNoteOverridesLikePrpr() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR"); Assume.assumeNotNull(directory);
        Assume.assumeTrue("Aconsma fixture not supplied", new File(directory, "Aconsma.zip").isFile());
        try (ZipFile zip = new ZipFile(directory + "/Aconsma.zip");
             var input = zip.getInputStream(zip.getEntry("1300906474114773.json"))) {
            ChartDocument chart = ChartDocument.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            int overrideCount = 0;
            Map<Long,Integer> expected = new HashMap<>();
            List<Long> overrideTimes = new ArrayList<>();
            for (JudgeLine line : chart.judgeLines) for (Note note : line.notes) {
                if (note.fake) continue;
                int value;
                if ("tap.mp3".equals(note.hitSound)) value = 100;
                else if ("flick.mp3".equals(note.hitSound)) value = 200;
                else if ("drag.mp3".equals(note.hitSound)) value = 300;
                else value = note.type == NoteType.FLICK ? 200 : note.type == NoteType.DRAG ? 300 : 100;
                long ms = chart.beatToMillis(note.startTime.toDouble());
                expected.merge(ms, value, Integer::sum);
                if (note.hitSound != null) { overrideCount++; overrideTimes.add(ms); }
            }
            HitSoundTimeline timeline = HitSoundTimeline.build(chart, 0, 0);
            assertEquals(550, chart.totalNotes()); assertEquals(250, timeline.size());
            assertEquals(43, overrideCount); assertTrue(timeline.customPaths().isEmpty());
            for (long ms : overrideTimes) {
                PcmAudioAsset asset = new PcmAudioAsset(new File("unused"), 1000, 1, ms + 1000);
                ScheduledHitSoundMixer mixer = new ScheduledHitSoundMixer(asset, timeline, 0,
                        builtins(1000), ms);
                byte[] pcm = new byte[2]; mixer.mix(pcm, 2, ms, 1, 1);
                assertEquals("Aconsma hit at " + ms, expected.get(ms).intValue(),
                        ScheduledHitSoundMixerTest.sample(pcm, 0, 1));
            }
            ChartDocument restored = ChartDocument.fromJson(chart.toJsonString());
            assertEquals(43, restored.judgeLines.stream().flatMap(line -> line.notes.stream())
                    .filter(note -> note.hitSound != null).count());
            System.out.println("Aconsma: 550 notes, 250 real hits, all 43 per-note overrides matched prpr routing");
        }
    }
}
