package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Optional real chart regression; one fixture is enough, independent of the other chart corpus. */
public class ParallelUniversePlaybackTest {
    @Test public void realChartSeeksAndPlaybackPreserveScenesAndShaderIntervals() throws Exception {
        String path = System.getenv("PHISTUDIO_PLAYBACK_CHART");
        Assume.assumeNotNull(path);
        ChartDocument chart;
        ShaderTimeline shaders;
        try (ZipFile zip = new ZipFile(path)) {
            var entry = zip.stream().filter(item -> item.getName().endsWith(".json")
                    && !item.getName().endsWith("extra.json")).findFirst().orElseThrow();
            chart = ChartDocument.fromJson(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            shaders = ShaderTimeline.parse(new String(zip.getInputStream(zip.getEntry("extra.json")).readAllBytes(),
                    StandardCharsets.UTF_8), name -> "precision mediump float; uniform sampler2D screenTexture; varying vec2 uv;"
                            + "void main(){gl_FragColor=texture2D(screenTexture,uv);}");
        }
        assertEquals(501, chart.judgeLines.size()); assertEquals(6555, chart.totalNotes());
        ChartEvaluator.prepare(chart, false);
        PreviewPlaybackState playback = new PreviewPlaybackState();
        double[] times = new double[560]; int frames = 0, inactive = 0, active = 0;
        for (double seconds : new double[]{5, 30, 60, 90, 120, 180, 240, 270, 285, 300, 330, 360, 390, 420}) {
            playback.reset();
            for (int frame = 0; frame < 40; frame++) {
                double beat = chart.millisToBeat(Math.round((seconds + frame / 60.0) * 1000));
                long start = System.nanoTime();
                RenderScene scene = ChartEvaluator.evaluate(chart, beat, true, 429499, false, 16.0 / 9, playback);
                times[frames++] = (System.nanoTime() - start) / 1e6;
                RenderScene still = ChartEvaluator.evaluate(chart, beat, true, 429499, false, 16.0 / 9);
                assertEquals(still.lines.size(), scene.lines.size()); assertEquals(still.hud.combo, scene.hud.combo);
                for (int i = 0; i < scene.lines.size(); i++) {
                    assertEquals(still.lines.get(i).notes.size(), scene.lines.get(i).notes.size());
                    assertEquals(still.lines.get(i).hitEffects.size(), scene.lines.get(i).hitEffects.size());
                }
            }
        }
        for (int i = 0; i < 429 * 60; i++) {
            if (shaders.hasActive(i / 60.0)) active++; else inactive++;
        }
        assertTrue(active > 0); assertTrue(inactive > active);
        Arrays.sort(times);
        System.out.println("Parallel Universe Shifter: 560 playback/seek frames; evaluate p50=" + times[280]
                + "ms p95=" + times[532] + "ms; direct Canvas intervals=" + inactive + "/" + (active + inactive));
    }
}
