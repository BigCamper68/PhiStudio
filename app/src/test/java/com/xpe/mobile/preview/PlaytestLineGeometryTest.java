package com.xpe.mobile.preview;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.Note;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaytestLineGeometryTest {
    private static final class Recorded extends Canvas {
        final RectF viewport;
        int paths;
        Recorded(RectF viewport) { this.viewport = viewport; }
        @Override public void drawRect(float l, float t, float r, float b, Paint paint) {
            float limit = 8 * Math.max(viewport.width(), viewport.height());
            for (float coordinate : new float[]{l, t, r, b})
                assertTrue("Unbounded Canvas rectangle: " + coordinate,
                        Float.isFinite(coordinate) && Math.abs(coordinate) <= limit);
        }
        @Override public void drawPath(Path path, Paint paint) {
            RectF bounds = new RectF(); path.computeBounds(bounds, true);
            if (bounds.isEmpty()) return;
            paths++;
            assertTrue(bounds.toString(), bounds.left >= viewport.left - .01f
                    && bounds.top >= viewport.top - .01f
                    && bounds.right <= viewport.right + .01f
                    && bounds.bottom <= viewport.bottom + .01f);
        }
    }

    @Test public void longRotatedWhiteLinesAfterMissNeverReachCanvasAsHugeRectangles() throws Exception {
        // Micrology lines 3 at 0:33 and 1 at 1:23. These scales are well below
        // the old f32 clipper threshold, yet become million-pixel rectangles.
        for (double[] sample : new double[][]{{-1771.749755859375, -3.816999912261963, 337.5, -300.65453588962555},
                {-230.48971557617188, -3.3354103565216064, 337.5, -302.92734146118164}}) {
            ChartDocument chart = ChartDocument.fromJson("{}");
            var line = chart.judgeLines.get(0);
            for (EventType type : new EventType[]{EventType.MOVE_X, EventType.MOVE_Y, EventType.ROTATE}) {
                double value = type == EventType.MOVE_X ? sample[2]
                        : type == EventType.MOVE_Y ? sample[3] : -sample[1];
                line.layer(0).events(type).get(0).start = value;
                line.layer(0).events(type).get(0).end = value;
            }
            var scale = new ExtendedLineEvents.NumericEvent();
            scale.start = scale.end = sample[0] * 2;
            line.extended.scaleXEvents.add(scale);
            line.notes.add(new Note());
            for (double aspect : new double[]{1.5, 16.0 / 9}) {
                PlaytestJudge judge = new PlaytestJudge(chart, false, aspect, 0);
                judge.update(.5);
                assertEquals(1, judge.count(PlaytestJudge.Grade.MISS));
                RenderScene scene = ChartEvaluator.evaluatePlaytest(chart, .5, false,
                        -1, false, aspect, new PreviewPlaybackState(), judge);
                assertEquals(0xFFFFFF, scene.lines.get(0).colorRgb);
                checkLayers(scene, aspect, true);
            }
        }
    }

    @Test public void suppliedMicrologyPlaytestWindowsHaveBoundedCanvasGeometry() throws Exception {
        String archive = System.getenv("PHISTUDIO_MICROLOGY_CHART");
        Assume.assumeNotNull(archive);
        ChartDocument chart;
        try (ZipFile zip = new ZipFile(archive);
             var input = zip.getInputStream(zip.getEntry("31486821.json"))) {
            chart = ChartDocument.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (double aspect : new double[]{1.5, 16.0 / 9}) {
            PlaytestJudge judge = new PlaytestJudge(chart, false, aspect, 0);
            PreviewPlaybackState playback = new PreviewPlaybackState();
            var renderer = renderer();
            try {
                for (int[] window : new int[][]{{31, 38}, {81, 96}}) {
                    for (int frame = window[0] * 60; frame <= window[1] * 60; frame++) {
                        double seconds = frame / 60.0;
                        judge.update(seconds);
                        RenderScene scene = ChartEvaluator.evaluatePlaytest(chart, seconds, false,
                                140781, false, aspect, playback, judge);
                        for (int width : new int[]{960, 2400}) {
                            RectF viewport = new RectF(20, 30, 20 + width, 30 + (float) (width / aspect));
                            renderer.draw(new Recorded(viewport), scene, viewport, viewport,
                                    null, settings(), -1, .6);
                        }
                    }
                }
            } finally { renderer.release(); }
        }
    }

    private static void checkLayers(RenderScene scene, double aspect, boolean expectPath) {
        PreviewRenderer renderer = renderer();
        try {
            for (int width : new int[]{960, 1920, 2400, 3840}) {
                RectF viewport = new RectF(20, 30, 20 + width, 30 + (float) (width / aspect));
                Recorded playtest = new Recorded(viewport), overlay = new Recorded(viewport),
                        shader = new Recorded(viewport);
                renderer.draw(playtest, scene, viewport, viewport, null, settings(), -1, .6);
                renderer.drawGameplayOverlay(overlay, scene, viewport, settings(), -1);
                renderer.drawShaderLayers(shader, new Canvas(), scene, viewport, viewport,
                        null, settings(), -1, .6);
                if (expectPath) for (Recorded canvas : new Recorded[]{playtest, overlay, shader})
                    assertTrue("Visible line should be drawn", canvas.paths > 0);
            }
        } finally { renderer.release(); }
    }

    private static EditorSettings settings() {
        EditorSettings settings = new EditorSettings();
        settings.markLineId = false; settings.highlightCurrentLine = false;
        return settings;
    }
    private static PreviewRenderer renderer() {
        var app = RuntimeEnvironment.getApplication();
        var renderer = new PreviewRenderer(new NoteTextureSet(app.getResources()), 1, "", app.getAssets());
        renderer.setShowHud(false);
        return renderer;
    }
}
