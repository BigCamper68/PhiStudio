package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.EventType;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RenderingProportionsTest {
    @Test
    public void customCircleKeepsItsShapeAcrossAspectsRotationAndMirroring() throws Exception {
        for (int[] size : new int[][]{{1920, 1080}, {2100, 1400}, {2560, 1080}, {900, 1200}}) {
            for (double rotation : new double[]{0, 45, 90}) {
                Bitmap bitmap = render(size[0], size[1], rotation, -1, 1, false, 0, 0);
                Rect bounds = opaqueBounds(bitmap);
                assertEquals("circle at " + size[0] + "x" + size[1],
                        bounds.width(), bounds.height(), 2);
                assertTrue(bounds.width() > 100);
                if (size[0] == 1920 && rotation == 0) save(bitmap, "texture-circle.png");
                bitmap.recycle();
            }
        }
    }

    @Test
    public void explicitAnisotropicScaleStillStretchesTheTexture() {
        Bitmap bitmap = render(1920, 1080, 0, 2, 1, false, 0, 0);
        Rect bounds = opaqueBounds(bitmap);
        assertEquals(2.0, bounds.width() / (double) bounds.height(), 0.02);
        bitmap.recycle();
    }

    @Test
    public void paintCircleKeepsItsShapeAndExplicitScale() throws Exception {
        for (int[] size : new int[][]{{1920, 1080}, {2100, 1400}, {2560, 1080}}) {
            Bitmap bitmap = render(size[0], size[1], 45, 1, 1, true, 0, 0);
            Rect bounds = opaqueBounds(bitmap);
            assertEquals(bounds.width(), bounds.height(), 2);
            if (size[0] == 1920) save(bitmap, "paint-circle.png");
            bitmap.recycle();
        }
        Bitmap stretched = render(1920, 1080, 0, 2, 1, true, 0, 0);
        Rect bounds = opaqueBounds(stretched);
        assertEquals(2.0, bounds.width() / (double) bounds.height(), 0.02);
        stretched.recycle();
    }

    @Test
    public void positionStillUsesFullChartAreaWithoutResizingTheTexture() {
        Bitmap centered = render(1920, 1080, 0, 1, 1, false, 0, 0);
        Bitmap moved = render(1920, 1080, 0, 1, 1, false, 337.5, 225);
        Rect a = opaqueBounds(centered);
        Rect b = opaqueBounds(moved);
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        assertEquals(480, b.centerX() - a.centerX(), 1);
        assertEquals(-270, b.centerY() - a.centerY(), 1);
        centered.recycle();
        moved.recycle();
    }

    @Test
    public void ordinaryLineLengthAndAngleDoNotDependOnRotation() {
        for (int[] size : new int[][]{{1920, 1080}, {2100, 1400}, {2560, 1080}}) {
            Rect horizontal = boundsOf(lineScene(0, 255, Collections.emptyList()), size);
            Rect vertical = boundsOf(lineScene(90, 255, Collections.emptyList()), size);
            Rect diagonal = boundsOf(lineScene(45, 255, Collections.emptyList()), size);
            assertEquals(horizontal.width(), vertical.height(), 2);
            assertEquals(horizontal.height(), vertical.width(), 2);
            assertEquals(diagonal.width(), diagonal.height(), 2);
            assertEquals(243 * size[0] / 1350.0, horizontal.width(), 2);
        }
    }

    @Test
    public void allNoteTypesKeepTheirDimensionsWhenTheLineRotates() {
        for (int[] size : new int[][]{{1920, 1080}, {2100, 1400}, {2560, 1080}}) {
            for (NoteType type : NoteType.values()) {
                for (boolean above : new boolean[]{true, false}) {
                    RenderScene.RenderNote note = new RenderScene.RenderNote(type,
                            0, 0, 200, above, 1, 255, false, false, true, 0xFFFFFF);
                    Rect a = boundsOf(lineScene(0, 0, Collections.singletonList(note)), size);
                    Rect b = boundsOf(lineScene(90, 0, Collections.singletonList(note)), size);
                    assertEquals(type + " width after quarter turn", a.width(), b.height(), 2);
                    assertEquals(type + " height after quarter turn", a.height(), b.width(), 2);
                }
            }
        }
    }

    @Test
    public void noteOffsetsFormACircleAndHoldsStayPerpendicularToTheLine() {
        int[] size = {1920, 1080};
        RenderScene.RenderNote tap = new RenderScene.RenderNote(NoteType.TAP,
                180, 0, 0, true, 1, 255, false, false, true, 0xFFFFFF);
        for (int rotation = 0; rotation < 360; rotation += 30) {
            Rect bounds = boundsOf(lineScene(rotation, 0, Collections.singletonList(tap)), size);
            double radians = Math.toRadians(rotation);
            assertEquals(960 + 180 * Math.cos(radians) * 1920 / 1350.0,
                    bounds.exactCenterX(), 2);
            assertEquals(540 - 180 * Math.sin(radians) * 1920 / 1350.0,
                    bounds.exactCenterY(), 2);
        }
        RenderScene.RenderNote hold = new RenderScene.RenderNote(NoteType.HOLD,
                0, 0, 200, true, 1, 255, false, false, true, 0xFFFFFF);
        Rect diagonal = boundsOf(lineScene(45, 0, Collections.singletonList(hold)), size);
        assertEquals(diagonal.width(), diagonal.height(), 2);
    }

    @Test
    public void hitEffectUsesTheHitTimeLineOriginAndMatchesTheNotePosition() {
        ChartDocument chart = new ChartDocument();
        JudgeLine line = new JudgeLine();
        line.layer(0).events(EventType.MOVE_X).get(0).start = 100;
        line.layer(0).events(EventType.MOVE_X).get(0).end = 100;
        line.layer(0).events(EventType.MOVE_Y).get(0).start = 200;
        line.layer(0).events(EventType.MOVE_Y).get(0).end = 200;
        line.layer(0).events(EventType.ROTATE).get(0).start = -45;
        line.layer(0).events(EventType.ROTATE).get(0).end = -45;
        Note note = new Note();
        note.type = NoteType.TAP;
        note.positionX = 100;
        note.yOffset = 50;
        note.speed = 1;
        note.above = 1;
        note.startTime = com.xpe.mobile.model.BeatTime.zero();
        line.notes.add(note);
        chart.judgeLines.add(line);
        RenderScene.RenderLine evaluated = ChartEvaluator.evaluate(chart, 0, false).lines.get(0);
        RenderScene.HitEffect effect = evaluated.hitEffects.get(0);
        assertEquals(100, effect.lineX, 0);
        assertEquals(200, effect.lineY, 0);
        RenderScene.RenderLine effectOnly = new RenderScene.RenderLine(0, 0,
                -300, -300, 0, 0, 0xFFFFFF, 1, 1, 0, null, "line.png",
                false, false, false, false, 0, 0, Collections.emptyList(),
                evaluated.hitEffects, Collections.emptyList());
        Rect bounds = boundsOf(effectOnly, new int[]{1920, 1080});
        assertEquals(960 + 100 * 1920 / 1350.0
                        + (100 * 1920 / 1350.0 - 50 * 1080 / 900.0) / Math.sqrt(2),
                bounds.exactCenterX(), 3);
        assertEquals(540 - 200 * 1080 / 900.0
                        - (100 * 1920 / 1350.0 + 50 * 1080 / 900.0) / Math.sqrt(2),
                bounds.exactCenterY(), 3);
    }

    private static RenderScene.RenderLine lineScene(double rotation, int alpha,
                                                     java.util.List<RenderScene.RenderNote> notes) {
        return new RenderScene.RenderLine(0, 0, 0, 0, rotation, alpha, 0xFFFFFF,
                0.03, 1, 0, null, "line.png", false, false,
                false, false, 0, 0, notes, Collections.emptyList(), Collections.emptyList());
    }

    private static Rect boundsOf(RenderScene.RenderLine line, int[] size) {
        PreviewRenderer renderer = new PreviewRenderer(
                new NoteTextureSet(RuntimeEnvironment.getApplication().getResources()), 1f, "",
                RuntimeEnvironment.getApplication().getAssets());
        EditorSettings settings = new EditorSettings();
        settings.markLineId = false;
        Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        renderer.drawGameplayOverlay(new Canvas(bitmap),
                new RenderScene(0, 0, Collections.singletonList(line), null),
                new RectF(0, 0, size[0], size[1]), settings, -1);
        Rect result = opaqueBounds(bitmap);
        bitmap.recycle();
        return result;
    }

    private static Bitmap render(int width, int height, double rotation, double sx, double sy,
                                 boolean brush, double x, double y) {
        Bitmap source = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        new Canvas(source).drawCircle(120, 120, 100, paint);
        PreviewRenderer renderer = new PreviewRenderer(
                new NoteTextureSet(RuntimeEnvironment.getApplication().getResources()), 1f, "",
                RuntimeEnvironment.getApplication().getAssets());
        renderer.setLineTextures(Collections.singletonMap("circle.png",
                PreviewTextureDecoder.Texture.still(source)));
        RenderScene.RenderLine line = new RenderScene.RenderLine(
                0, 0, x, y, rotation, 255, 0xFFFFFF, sx, sy, 0,
                null, "circle.png", brush, false, false, false, 0, 0,
                Collections.emptyList(), Collections.emptyList(), brush
                ? Collections.singletonList(new RenderScene.PaintStroke(
                        x, y, 100, rotation, sx, sy, 0xFFFFFF, 255))
                : Collections.emptyList());
        RenderScene scene = new RenderScene(0, 0, Collections.singletonList(line), null);
        EditorSettings settings = new EditorSettings();
        settings.markLineId = false;
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        renderer.drawGameplayOverlay(new Canvas(result), scene, new RectF(0, 0, width, height),
                settings, -1);
        source.recycle();
        return result;
    }

    private static Rect opaqueBounds(Bitmap image) {
        Rect bounds = new Rect(image.getWidth(), image.getHeight(), 0, 0);
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (Color.alpha(pixels[y * image.getWidth() + x]) < 128) continue;
                bounds.left = Math.min(bounds.left, x);
                bounds.top = Math.min(bounds.top, y);
                bounds.right = Math.max(bounds.right, x + 1);
                bounds.bottom = Math.max(bounds.bottom, y + 1);
            }
        }
        assertTrue("renderer produced pixels", bounds.width() > 0 && bounds.height() > 0);
        return bounds;
    }

    private static void save(Bitmap image, String name) throws Exception {
        File directory = new File("build/reports/preview-proportions");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
}
