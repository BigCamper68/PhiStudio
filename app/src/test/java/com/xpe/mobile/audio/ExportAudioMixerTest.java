package com.xpe.mobile.audio;

import android.content.Context;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.*;
import com.xpe.mobile.video.VideoExportOptions;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ExportAudioMixerTest {
    @Test public void negativeRangePadsOnlyPrerollAndKeepsTheFirstMusicSample() throws Exception {
        File file = File.createTempFile("preroll-music-", ".pcm", context.getCacheDir());
        try {
            byte[] music = new byte[2000];
            for (int i = 0; i < music.length; i += 2) { music[i] = (byte) 0xD0; music[i + 1] = 7; }
            Files.write(file.toPath(), music);
            PcmAudioAsset asset = new PcmAudioAsset(file, 1000, 1, 1000);
            ChartDocument chart = chart(); chart.offsetMs = -21;
            try (ExportAudioMixer mixer = new ExportAudioMixer(context, asset, chart, null, 0,
                    options(-21, 100, true, false), new EditorSettings())) {
                byte[] output = render(mixer, 17);
                for (int i = 0; i < 42; i++) assertEquals(0, output[i]);
                for (int i = 42; i < output.length; i += 2) {
                    assertEquals((byte) 0xE8, output[i]); assertEquals(3, output[i + 1]);
                }
            }
        } finally { file.delete(); }
    }

    @Test public void hitsAtChartZeroSurviveNegativePrerollAndPartialRanges() throws Exception {
        ChartDocument chart = chart(0); chart.offsetMs = -21;
        byte[] full;
        try (ExportAudioMixer mixer = new ExportAudioMixer(context, null, chart, null, 0,
                options(-21, 300, false, true), new EditorSettings())) { full = render(mixer, 337); }
        byte[] hit = Files.readAllBytes(new File("src/main/res/raw/hitsound_click_pcm.pcm").toPath());
        assertArrayEquals(hit, java.util.Arrays.copyOf(full, hit.length));
        try (ExportAudioMixer mixer = new ExportAudioMixer(context, null, chart, null, 0,
                options(-10, 300, false, true), new EditorSettings())) {
            byte[] partial = render(mixer, 101);
            int offset = 11 * 48 * 4;
            assertArrayEquals(java.util.Arrays.copyOfRange(hit, offset, hit.length),
                    java.util.Arrays.copyOf(partial, hit.length - offset));
        }
    }
    private final Context context = RuntimeEnvironment.getApplication();
    private static ChartDocument chart(double... noteBeats) {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 60; chart.bpmChanges.add(bpm);
        JudgeLine line = new JudgeLine(); chart.judgeLines.add(line);
        for (double beat : noteBeats) {
            Note note = new Note(); note.type = NoteType.TAP;
            note.startTime = BeatTime.fromDouble(beat, 100000); note.endTime = note.startTime;
            line.notes.add(note);
        }
        return chart;
    }
    private static VideoExportOptions options(long start, long end, boolean music, boolean hits) {
        return new VideoExportOptions(1280, 720, 30, 8_000_000, start, end, music, hits, .5f, 1, false, true);
    }
    private static byte[] render(ExportAudioMixer mixer, int blockFrames) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[blockFrames * mixer.channels * 2];
        int bytes;
        while ((bytes = mixer.read(buffer, buffer.length)) > 0) output.write(buffer, 0, bytes);
        return output.toByteArray();
    }
    @Test public void combinesChartPackageAndHitOffsetsAtExactSamples() throws Exception {
        ChartDocument chart = chart(.1); chart.offsetMs = 37;
        EditorSettings settings = new EditorSettings(); settings.tapFlickHitsoundOffsetMs = 7;
        byte[] actual;
        try (ExportAudioMixer mixer = new ExportAudioMixer(context, null, chart, null, 13,
                options(0, 400, false, true), settings)) { actual = render(mixer, 337); }
        byte[] sample = Files.readAllBytes(new File("src/main/res/raw/hitsound_click_pcm.pcm").toPath());
        byte[] expected = new byte[actual.length];
        int at = Math.round(157 * 48f) * 4;
        System.arraycopy(sample, 0, expected, at, Math.min(sample.length, expected.length - at));
        assertArrayEquals(expected, actual);
    }
    @Test public void rangeStartRetainsTheTailOfAnEarlierHit() throws Exception {
        ChartDocument chart = chart(.1);
        byte[] full, ranged;
        try (ExportAudioMixer mixer = new ExportAudioMixer(context, null, chart, null, 0,
                options(0, 300, false, true), new EditorSettings())) { full = render(mixer, 317); }
        try (ExportAudioMixer mixer = new ExportAudioMixer(context, null, chart, null, 0,
                options(110, 310, false, true), new EditorSettings())) { ranged = render(mixer, 101); }
        assertArrayEquals(java.util.Arrays.copyOfRange(full, 110 * 48 * 4, 300 * 48 * 4),
                java.util.Arrays.copyOf(ranged, 190 * 48 * 4));
        boolean hasTail = false;
        for (byte sample : ranged) if (sample != 0) { hasTail = true; break; }
        assertTrue(hasTail);
    }
    @Test public void monoMusicStartsAtRangeAndPadsSilenceAfterEnd() throws Exception {
        File file = File.createTempFile("music-test-", ".pcm", context.getCacheDir());
        try {
            byte[] song = new byte[100 * 48 * 2];
            for (int i = 0; i < song.length; i += 2) { song[i] = (byte) 0xD0; song[i + 1] = 7; } // 2000
            Files.write(file.toPath(), song);
            PcmAudioAsset asset = new PcmAudioAsset(file, 48000, 1, song.length / 2);
            try (ExportAudioMixer mixer = new ExportAudioMixer(context, asset, chart(), null, 0,
                    options(50, 150, true, false), new EditorSettings())) {
                byte[] result = render(mixer, 237);
                assertEquals(100 * 48 * 2, result.length);
                for (int i = 0; i < 50 * 48 * 2; i += 2) {
                    assertEquals((byte) 0xE8, result[i]); assertEquals(3, result[i + 1]); // 1000
                }
                for (int i = 50 * 48 * 2; i < result.length; i++) assertEquals(0, result[i]);
            }
        } finally { file.delete(); }
    }
    @Test public void silentTrackAndFinalShortBlockHaveExactLength() throws Exception {
        try (ExportAudioMixer mixer = new ExportAudioMixer(context, null, chart(), null, 0,
                options(0, 1, false, false), new EditorSettings())) {
            byte[] result = render(mixer, 337);
            assertEquals(1600 * 4, result.length);
            for (byte value : result) assertEquals(0, value);
        }
    }
}
