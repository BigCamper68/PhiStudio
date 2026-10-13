package com.xpe.mobile.preview;

import android.graphics.*;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import java.util.Collections;
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
public class HiddenLinePlaybackTest {
    private static final class Recorded extends Canvas {
        int sprites, circles;
        @Override public void drawBitmap(Bitmap image, Rect source, RectF destination, Paint paint) { sprites++; }
        @Override public void drawOval(float left, float top, float right, float bottom, Paint paint) { circles++; }
    }
    private PreviewRenderer renderer() {
        var app = RuntimeEnvironment.getApplication();
        return new PreviewRenderer(new NoteTextureSet(app.getResources()), 1, "", app.getAssets());
    }
    private ChartDocument chart() {
        ChartDocument chart = new ChartDocument(); chart.bpmChanges.add(new BpmChange());
        JudgeLine line = new JudgeLine(); chart.judgeLines.add(line);
        LineEvent alpha = line.layer(0).events(EventType.ALPHA).get(0); alpha.start = alpha.end = 0;
        return chart;
    }
    @Test public void hiddenLineSkipsItsArtworkLookupButKeepsItsNotes() {
        ChartDocument chart = chart();
        Note note = new Note(); note.type = NoteType.TAP; note.startTime = new BeatTime(1, 0, 1);
        note.endTime = note.startTime; chart.judgeLines.get(0).notes.add(note);
        chart.judgeLines.get(0).texture = "unused.png";
        PreviewRenderer renderer = renderer();
        renderer.setLineTextures(new java.util.AbstractMap<>() {
            @Override public java.util.Set<Entry<String, PreviewTextureDecoder.Texture>> entrySet() { return Collections.emptySet(); }
            @Override public PreviewTextureDecoder.Texture get(Object name) { fail("A hidden line must not look up artwork"); return null; }
        });
        Recorded canvas = new Recorded();
        renderer.drawGameplayOverlay(canvas, ChartEvaluator.evaluate(chart, 0, false),
                new RectF(0, 0, 1350, 900), new EditorSettings(), -1);
        assertEquals(1, canvas.sprites);
    }
    @Test public void zeroAlphaPaintStillDrawsItsRetainedOrReconstructedStrokes() {
        ChartDocument chart = chart();
        var brush = new ExtendedLineEvents.NumericEvent(); brush.start = brush.end = 8;
        chart.judgeLines.get(0).extended.paintEvents.add(brush);
        Recorded canvas = new Recorded();
        renderer().drawGameplayOverlay(canvas, ChartEvaluator.evaluate(chart, 0, false),
                new RectF(0, 0, 1350, 900), new EditorSettings(), -1);
        assertEquals(1, canvas.circles);
    }
}
