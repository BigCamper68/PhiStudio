package com.xpe.mobile.preview;

import android.graphics.*;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class PrprExtremeLineRenderingTest {
    private static final class Recorded extends Canvas {
        final RectF visible = new RectF(); final RectF viewport;
        Recorded(RectF viewport) { this.viewport = viewport; }
        private void record(RectF r, Paint paint) {
            if (paint.getColor() != PhiraJudgeLineStyle.PERFECT_COLOR_ARGB) return;
            Matrix matrix = new Matrix(); getMatrix(matrix); matrix.mapRect(r);
            if (r.intersect(viewport)) visible.union(r);
        }
        @Override public void drawRect(float l, float t, float r, float b, Paint p) {
            record(new RectF(l, t, r, b), p);
        }
        @Override public void drawPath(Path path, Paint paint) {
            RectF bounds = new RectF(); path.computeBounds(bounds, true); record(bounds, paint);
        }
    }
    @Test public void futureStoryboardScaleDrawsRightHalfInPreviewAndExportLayers() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\"BPMList\":[{\"bpm\":130,\"startTime\":[0,0,1]}]}");
        JudgeLine line = chart.judgeLines.get(0);
        line.layer(0).events(EventType.MOVE_Y).get(0).start = -250;
        line.layer(0).events(EventType.MOVE_Y).get(0).end = -250;
        ExtendedLineEvents.NumericEvent scale = new ExtendedLineEvents.NumericEvent();
        scale.startTime = new BeatTime(101, 0, 1); scale.endTime = new BeatTime(102, 0, 1);
        scale.start = 0; scale.end = .249; scale.easingType = 15;
        line.extended.scaleXEvents.add(scale);
        RenderScene scene = ChartEvaluator.evaluateAtSeconds(chart, 0, false);
        assertEquals(-1308507520f, scene.lines.get(0).scaleX, 0);
        PreviewRenderer renderer = new PreviewRenderer(new NoteTextureSet(RuntimeEnvironment.getApplication().getResources()),
                1, "", RuntimeEnvironment.getApplication().getAssets());
        renderer.setShowHud(false);
        EditorSettings settings = new EditorSettings(); settings.markLineId = false; settings.highlightCurrentLine = false;
        try {
            for (int width : new int[]{960, 1920}) {
                RectF viewport = new RectF(20, 30, 20 + width, 30 + width * 9f / 16);
                Recorded preview = new Recorded(viewport), export = new Recorded(viewport);
                renderer.drawGameplayOverlay(preview, scene, viewport, settings, -1);
                renderer.drawShaderLayers(export, new Canvas(), scene, viewport, viewport, null, settings, -1, .6);
                for (Recorded canvas : new Recorded[]{preview, export}) {
                    assertEquals(viewport.centerX(), canvas.visible.left, .001);
                    assertEquals(viewport.right, canvas.visible.right, .001);
                    assertEquals(viewport.width() / 2, canvas.visible.width(), .001);
                }
            }
        } finally { renderer.release(); }
    }
}
