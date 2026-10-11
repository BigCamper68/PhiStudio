package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PreviewCompatibilityRegressionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void decodesMoreThan32TexturesAndSamplesLargeImagesWithoutChangingGeometry() throws Exception {
        File workspace = temporary.newFolder();
        List<String> names = new ArrayList<>();
        Bitmap small = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
        small.eraseColor(Color.WHITE);
        for (int i = 0; i < 61; i++) {
            String name = "texture-" + i + ".png";
            names.add(name);
            write(small, new File(workspace, name));
        }
        small.recycle();
        Bitmap large = Bitmap.createBitmap(1062, 4000, Bitmap.Config.ARGB_8888);
        large.eraseColor(Color.WHITE);
        names.add("hd2.png");
        write(large, new File(workspace, "hd2.png"));
        large.recycle();
        Map<String, PreviewTextureDecoder.Texture> textures = PreviewTextureDecoder.decode(workspace, names);
        try {
            assertEquals(62, textures.size());
            PreviewTextureDecoder.Texture hold = textures.get("hd2.png");
            assertEquals(1062, hold.width);
            assertEquals(4000, hold.height);
            assertTrue(hold.bitmap.getWidth() < hold.width);
            assertTrue(hold.bitmap.getHeight() < hold.height);
            Bitmap result = render(Collections.singletonList(line("hd2.png", 255, .1, .1,
                    Collections.emptyList())), textures);
            assertTrue(Color.alpha(result.getPixel(960, 540)) > 0);
            assertTrue(Color.alpha(result.getPixel(960, 780)) > 0);
            result.recycle();
        } finally { PreviewTextureDecoder.recycleAll(textures); }
    }

    @Test public void negativeHoldBodyDoesNotBecomeAPositiveStripButCapsRemainIndependent() {
        RenderScene.RenderNote active = new RenderScene.RenderNote(NoteType.HOLD,
                0, 0, -10000, true, 1, 255, false, false, false, 0xFFFFFF);
        Bitmap absent = render(Collections.singletonList(line("line.png", 0, 1, 1,
                Collections.singletonList(active))), Collections.emptyMap());
        assertEquals(0, opaquePixels(absent, 0, 0, absent.getWidth(), absent.getHeight()));
        absent.recycle();
        RenderScene.RenderNote tail = new RenderScene.RenderNote(NoteType.HOLD,
                0, 0, -100, true, 1, 255, false, false, false, 0xFFFFFF);
        Bitmap caps = render(Collections.singletonList(line("line.png", 0, 1, 1,
                Collections.singletonList(tail))), Collections.emptyMap());
        assertEquals(0, opaquePixels(caps, 910, 565, 100, 80));
        int tailY = (int) (caps.getHeight() / 2.0
                - tail.endDistance * PreviewCoordinates.scaleY(caps.getHeight()));
        assertTrue(opaquePixels(caps, 880, tailY, 160, 45) > 0);
        caps.recycle();
    }

    @Test public void missingCustomTextureDoesNotDrawAnOrdinaryLine() {
        Bitmap result = render(Collections.singletonList(line("missing.png", 255, 1, 1,
                Collections.emptyList())), Collections.emptyMap());
        assertEquals(0, opaquePixels(result, 0, 0, result.getWidth(), result.getHeight()));
        result.recycle();
    }

    @Test public void introductionLoadsActualHoldArtworkAtEveryReportedSection() throws Exception {
        File root = reference("Introduction");
        ChartDocument chart = load(root, "4432912793951490.json");
        Map<String, PreviewTextureDecoder.Texture> textures = decode(root, chart);
        try {
            assertNotNull(textures.get("hd2.png"));
            for (double beat : new double[]{104, 112, 120, 266, 270}) {
                List<RenderScene.RenderLine> artwork = new ArrayList<>();
                for (RenderScene.RenderLine line : ChartEvaluator.evaluate(chart, beat, true).lines) {
                    if ("hd2.png".equals(line.textureName)) {
                        artwork.add(new RenderScene.RenderLine(line.sourceIndex, line.zOrder,
                                line.x, line.y, line.rotationDegrees, line.alpha, line.colorRgb,
                                line.scaleX, line.scaleY, line.inclineDegrees, line.text,
                                line.textureName, false, line.cover, false, false, 0, 0,
                                Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
                    }
                }
                Bitmap image = render(artwork, textures);
                assertTrue("hold artwork at " + beat,
                        opaquePixels(image, 0, 0, image.getWidth(), image.getHeight()) > 100);
                save(image, "Introduction-" + (int) beat + ".png");
                image.recycle();
            }
        } finally { PreviewTextureDecoder.recycleAll(textures); }
    }

    @Test public void algebraLoadsEveryDeclaredImageWithinTheMemoryBudget() throws Exception {
        File root = reference("Algebra");
        ChartDocument chart = load(root, "19919062.json");
        Map<String, PreviewTextureDecoder.Texture> textures = decode(root, chart);
        try {
            assertEquals(61, textures.size());
            long pixels = 0;
            for (JudgeLine line : chart.judgeLines) {
                if (!"line.png".equals(line.texture)) assertNotNull(line.texture, textures.get(line.texture));
            }
            for (PreviewTextureDecoder.Texture texture : textures.values()) {
                pixels += (long) texture.bitmap.getWidth() * texture.bitmap.getHeight();
            }
            assertTrue("decoded pixel budget: " + pixels, pixels <= 12_000_000L);
            for (double beat : new double[]{584, 612, 620, 636, 643}) {
                Bitmap image = render(ChartEvaluator.evaluate(chart, beat, true).lines, textures);
                save(image, "Algebra-" + (int) beat + ".png");
                image.recycle();
            }
        } finally { PreviewTextureDecoder.recycleAll(textures); }
    }

    @Test public void xenophobiaActiveHoldsDisappearWhenTheirTailGoesBehindTheHead() throws Exception {
        ChartDocument chart = load(reference("Xenophobia"), "79078192.json");
        for (double[] sample : new double[][]{{0, 36}, {0, 37}, {16, 44}, {16, 45}}) {
            RenderScene.RenderLine source = ChartEvaluator.evaluate(chart, sample[1], false).lines.stream()
                    .filter(l -> l.sourceIndex == (int) sample[0]).findFirst().get();
            List<RenderScene.RenderNote> holds = new ArrayList<>();
            for (RenderScene.RenderNote note : source.notes) {
                if (note.isHold() && !note.holdHeadVisible) {
                    assertTrue(note.endDistance < note.startDistance);
                    holds.add(note);
                }
            }
            assertEquals(1, holds.size());
            RenderScene.RenderLine isolated = new RenderScene.RenderLine(source.sourceIndex, 0,
                    source.x, source.y, source.rotationDegrees, 0, 0xFFFFFF, 1, 1, 0,
                    null, "line.png", false, true, false, false, 0, 0,
                    holds, Collections.emptyList(), Collections.emptyList());
            Bitmap image = render(Collections.singletonList(isolated), Collections.emptyMap());
            assertEquals(0, opaquePixels(image, 0, 0, image.getWidth(), image.getHeight()));
            image.recycle();
            assertFalse("real hold still emits hit pulses", source.hitEffects.isEmpty());
        }
    }

    @Test public void alterStrifeHasNoStackOfDragsAtTheScreenCenterBeforeBeatOneAndAHalf() throws Exception {
        ChartDocument chart = load(reference("AlterStrife"), "2586918600786450.json");
        for (double beat : new double[]{0, .25, .5, 1, 1.49}) {
            RenderScene scene = ChartEvaluator.evaluate(chart, beat, true);
            Bitmap image = render(scene.lines, Collections.emptyMap());
            assertEquals("unexpected center geometry at " + beat,
                    0, opaquePixels(image, 930, 515, 60, 50));
            if (beat == 0) {
                assertEquals("pre-roll must not relocate the stray note to an edge",
                        0, opaquePixels(image, 0, 0, image.getWidth(), image.getHeight()));
                save(image, "AlterStrife-0.png");
            }
            image.recycle();
        }
        ChartDocument reopened = ChartDocument.fromJson(chart.toJsonString());
        RenderScene before = ChartEvaluator.evaluate(chart, 0, false);
        RenderScene after = ChartEvaluator.evaluate(reopened, 0, false);
        for (int id : new int[]{32, 33, 34}) {
            assertFalse(before.lines.stream().anyMatch(l -> l.sourceIndex == id));
            assertFalse(after.lines.stream().anyMatch(l -> l.sourceIndex == id));
        }
    }

    @Test public void noteTypePriorityAppliesAcrossLines() {
        List<RenderScene.RenderLine> backwards = new ArrayList<>();
        for (NoteType type : new NoteType[]{NoteType.FLICK, NoteType.TAP, NoteType.DRAG, NoteType.HOLD}) {
            RenderScene.RenderNote note = new RenderScene.RenderNote(type, 0,
                    type == NoteType.HOLD ? -100 : 0, 400,
                    true, 1, 255, false, false, true, 0xFFFFFF);
            backwards.add(line("line.png", 0, 1, 1, Collections.singletonList(note)));
        }
        Bitmap expected = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        for (int i = backwards.size() - 1; i >= 0; i--) {
            renderOnto(expected, Collections.singletonList(backwards.get(i)), Collections.emptyMap());
        }
        Bitmap actual = render(backwards, Collections.emptyMap());
        assertSamePixels("Hold < Drag < Tap < Flick across judge lines", expected, actual);
        expected.recycle();
        actual.recycle();
    }

    @Test public void laterCustomTextureCannotCoverNotesOrHitEffects() {
        Bitmap texture = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        texture.eraseColor(Color.rgb(40, 50, 60));
        Map<String, PreviewTextureDecoder.Texture> textures = Collections.singletonMap(
                "opaque.png", PreviewTextureDecoder.Texture.still(texture));
        RenderScene.RenderLine artwork = line("opaque.png", 255, 100, 100, Collections.emptyList());
        RenderScene.RenderNote drag = new RenderScene.RenderNote(NoteType.DRAG, 0, 0, 0,
                true, 1, 255, false, false, true, 0xFFFFFF);
        RenderScene.RenderLine notes = line("line.png", 0, 1, 1, Collections.singletonList(drag));
        RenderScene.RenderLine effects = parts(notes, false, Collections.emptyList(),
                Collections.singletonList(new RenderScene.HitEffect(200, 0, 0, 0, .2, 0xFFECAA, 7)));
        Bitmap expected = render(Collections.singletonList(artwork), textures);
        Bitmap noteImage = render(Collections.singletonList(notes), textures);
        Bitmap effectImage = render(Collections.singletonList(effects), textures);
        assertTrue(opaquePixels(noteImage, 0, 0, 1920, 1080) > 100);
        assertTrue(opaquePixels(effectImage, 0, 0, 1920, 1080) > 100);
        renderOnto(expected, Collections.singletonList(notes), textures);
        renderOnto(expected, Collections.singletonList(effects), textures);
        Bitmap actual = render(Arrays.asList(notes, effects, artwork), textures);
        assertSamePixels("custom textures are below notes and hit effects", expected, actual);
        for (Bitmap image : new Bitmap[]{expected, noteImage, effectImage, actual}) image.recycle();
        texture.recycle();
    }

    @Test public void xenophobiaDragsStayAboveHoldsAcrossBothLayerTransitions() throws Exception {
        ChartDocument chart = load(reference("Xenophobia"), "79078192.json");
        Map<String, PreviewTextureDecoder.Texture> textures = decode(reference("Xenophobia"), chart);
        try {
            for (double beat : new double[]{34.75, 35.874, 35.876, 35.99, 42, 43.749, 43.751, 43.876, 43.99}) {
                RenderScene scene = ChartEvaluator.evaluate(chart, beat, true);
                List<RenderScene.RenderLine> combined = new ArrayList<>();
                List<RenderScene.RenderLine> underneath = new ArrayList<>();
                List<RenderScene.RenderLine> drags = new ArrayList<>();
                for (RenderScene.RenderLine line : scene.lines) {
                    List<RenderScene.RenderNote> holds = new ArrayList<>();
                    List<RenderScene.RenderNote> targetDrags = new ArrayList<>();
                    for (RenderScene.RenderNote note : line.notes) {
                        if (note.isHold()) holds.add(note);
                        if ((line.sourceIndex == 21 || line.sourceIndex == 22)
                                && note.type == NoteType.DRAG) targetDrags.add(note);
                    }
                    underneath.add(parts(line, true, holds, Collections.emptyList()));
                    List<RenderScene.RenderNote> selected = new ArrayList<>(holds);
                    selected.addAll(targetDrags);
                    combined.add(parts(line, true, selected, Collections.emptyList()));
                    if (!targetDrags.isEmpty()) drags.add(parts(line, false, targetDrags, Collections.emptyList()));
                }
                assertEquals("both drag lines at " + beat, 2, drags.size());
                Bitmap expected = render(underneath, textures);
                Bitmap dragImage = render(drags, textures);
                assertTrue("visible drags at " + beat, opaquePixels(dragImage, 0, 0, 1920, 1080) > 100);
                renderOnto(expected, drags, textures);
                Bitmap actual = render(combined, textures);
                assertSamePixels("drag overlay at beat " + beat, expected, actual);
                for (Bitmap image : new Bitmap[]{expected, dragImage, actual}) image.recycle();
                Bitmap image = render(scene.lines, textures);
                save(image, "Xenophobia-layers-" + beat + ".png");
                image.recycle();
            }
        } finally { PreviewTextureDecoder.recycleAll(textures); }
    }

    @Test public void algebraHitEffectsStayAboveAllArtwork() throws Exception {
        File root = reference("Algebra");
        ChartDocument chart = load(root, "19919062.json");
        Map<String, PreviewTextureDecoder.Texture> textures = decode(root, chart);
        try {
            for (double beat : new double[]{584.1, 612.1, 620.1, 636.1, 643.1}) {
                RenderScene scene = ChartEvaluator.evaluate(chart, beat, true);
                List<RenderScene.RenderLine> underneath = new ArrayList<>();
                List<RenderScene.RenderLine> effects = new ArrayList<>();
                for (RenderScene.RenderLine line : scene.lines) {
                    underneath.add(parts(line, true, line.notes, Collections.emptyList()));
                    effects.add(parts(line, false, Collections.emptyList(), line.hitEffects));
                }
                Bitmap expected = render(underneath, textures);
                Bitmap effectImage = render(effects, textures);
                assertTrue("visible hit effects at " + beat, opaquePixels(effectImage, 0, 0, 1920, 1080) > 100);
                renderOnto(expected, effects, textures);
                Bitmap actual = render(scene.lines, textures);
                save(actual, "Algebra-effects-" + beat + ".png");
                assertSamePixels("hit effects above artwork at " + beat, expected, actual);
                for (Bitmap image : new Bitmap[]{expected, effectImage, actual}) image.recycle();
            }
        } finally { PreviewTextureDecoder.recycleAll(textures); }
    }

    private static RenderScene.RenderLine parts(RenderScene.RenderLine source, boolean visuals,
                                                List<RenderScene.RenderNote> notes,
                                                List<RenderScene.HitEffect> effects) {
        return new RenderScene.RenderLine(source.sourceIndex, source.zOrder,
                source.x, source.y, source.rotationDegrees, visuals ? source.alpha : 0,
                source.colorRgb, source.scaleX, source.scaleY, source.inclineDegrees,
                source.text, source.textureName, source.paintMode, source.cover,
                source.gifEnabled, source.gifControlled, source.gifProgress, source.gifAnchorTimeMs,
                notes, effects, visuals ? source.paintStrokes : Collections.emptyList());
    }

    private static void assertSamePixels(String message, Bitmap expected, Bitmap actual) {
        int[] a = new int[1920 * 1080], b = new int[a.length];
        expected.getPixels(a, 0, 1920, 0, 0, 1920, 1080);
        actual.getPixels(b, 0, 1920, 0, 0, 1920, 1080);
        assertArrayEquals(message, a, b);
    }

    private static File reference(String chart) {
        String root = System.getenv("PHISTUDIO_REFERENCE_CHARTS");
        assumeTrue("Set PHISTUDIO_REFERENCE_CHARTS to the four extracted chart folders", root != null);
        File folder = new File(root, chart);
        assertTrue("missing reference " + folder, folder.isDirectory());
        return folder;
    }
    private static ChartDocument load(File root, String filename) throws Exception {
        return ChartDocument.fromJson(new String(Files.readAllBytes(new File(root, filename).toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
    }
    private static Map<String, PreviewTextureDecoder.Texture> decode(File root, ChartDocument chart) {
        List<String> names = new ArrayList<>();
        for (JudgeLine line : chart.judgeLines) names.add(line.texture);
        return PreviewTextureDecoder.decode(root, names);
    }
    private static RenderScene.RenderLine line(String texture, int alpha, double sx, double sy,
                                               List<RenderScene.RenderNote> notes) {
        return new RenderScene.RenderLine(0, 0, 0, 0, 0, alpha, 0xFFFFFF, sx, sy, 0,
                null, texture, false, true, false, false, 0, 0,
                notes, Collections.emptyList(), Collections.emptyList());
    }
    private static Bitmap render(List<RenderScene.RenderLine> lines,
                                  Map<String, PreviewTextureDecoder.Texture> textures) {
        Bitmap image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        renderOnto(image, lines, textures);
        return image;
    }

    private static void renderOnto(Bitmap image, List<RenderScene.RenderLine> lines,
                                   Map<String, PreviewTextureDecoder.Texture> textures) {
        PreviewRenderer renderer = new PreviewRenderer(
                new NoteTextureSet(RuntimeEnvironment.getApplication().getResources()), 1f, "",
                RuntimeEnvironment.getApplication().getAssets());
        renderer.setLineTextures(textures);
        EditorSettings settings = new EditorSettings();
        settings.markLineId = false;
        renderer.drawGameplayOverlay(new Canvas(image), new RenderScene(0, 0, lines, null),
                new RectF(0, 0, 1920, 1080), settings, -1);
    }
    private static int opaquePixels(Bitmap image, int x, int y, int width, int height) {
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, x, y, width, height);
        int count = 0;
        for (int pixel : pixels) if (Color.alpha(pixel) > 0) count++;
        return count;
    }
    private static void write(Bitmap image, File file) throws Exception {
        try (OutputStream output = new FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
    private static void save(Bitmap image, String name) throws Exception {
        File directory = new File("build/reports/preview-compatibility");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        write(image, new File(directory, name));
    }
}
