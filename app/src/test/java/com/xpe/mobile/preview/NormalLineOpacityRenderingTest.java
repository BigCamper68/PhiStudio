package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NormalLineOpacityRenderingTest {
    @Test public void ordinaryCanvasLineMultipliesBeforeSaturatingAndTruncatesTheFinalByte() {
        for (double[] test : new double[][]{{300,255},{128,112},{254.5,224}}) {
            Bitmap image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            PreviewRenderer renderer = renderer();
            try {
                renderer.drawGameplayOverlay(new Canvas(image), scene(test[0]),
                        new RectF(0, 0, 1920, 1080), settings(), -1);
                assertEquals("raw alpha" + test[0], (int) test[1], Color.alpha(image.getPixel(960, 540)));
            } finally {
                renderer.release(); image.recycle();
            }
        }
    }

    @Test public void shaderAndExportCanvasLayerReceivesTheSameSaturatedLineColor() {
        Bitmap gameplay = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        Bitmap overlay = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        PreviewRenderer renderer = renderer();
        try {
            RectF viewport = new RectF(0, 0, 1920, 1080);
            renderer.drawShaderLayers(new Canvas(gameplay), new Canvas(overlay), scene(300),
                    viewport, viewport, null, settings(), -1, .6);
            assertEquals(0xFFFFEC9F, gameplay.getPixel(960, 540));
        } finally {
            renderer.release(); gameplay.recycle(); overlay.recycle();
        }
    }

    private RenderScene scene(double rawAlpha) {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 120; chart.bpmChanges.add(bpm);
        JudgeLine line = new JudgeLine(); chart.judgeLines.add(line);
        LineEvent event = line.layer(0).events(EventType.ALPHA).get(0);
        event.start = event.end = rawAlpha;
        return ChartEvaluator.evaluateAtSeconds(chart, .25, false);
    }

    private PreviewRenderer renderer() {
        PreviewRenderer renderer = new PreviewRenderer(new NoteTextureSet(
                RuntimeEnvironment.getApplication().getResources()), 1f, "",
                RuntimeEnvironment.getApplication().getAssets());
        renderer.setShowHud(false);
        return renderer;
    }

    private EditorSettings settings() {
        EditorSettings settings = new EditorSettings();
        settings.markLineId = false;
        settings.highlightCurrentLine = false;
        return settings;
    }
}
