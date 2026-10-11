package com.xpe.mobile.shadereditor;

import static org.junit.Assert.*;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.ShaderTimeline;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

public class ShaderDocumentTest {
    private static final String GLSL = "uniform float power; // %0.5%\n"
        + "uniform vec2 offset; // %0.1,-0.2%\nuniform vec4 color; // %1,0.5,0,1%\n"
        + "uniform float time; uniform vec2 screenSize; uniform vec2 UVScale;\nvoid main(){}";
    private ShaderDocument fresh() throws Exception {
        return new ShaderDocument(null, ChartDocument.fromJson("{}"), name -> GLSL);
    }
    private ShaderDocument original() throws Exception {
        return new ShaderDocument("{\"bpm\":60,\"futureRoot\":{\"data\":42},\"effects\":["
                + "{\"shader\":\"noise\",\"start\":[1,0,1],\"end\":[5,0,1],\"futureEffect\":7,"
                + "\"vars\":{\"power\":2,\"custom\":[1,2]}}]}",
            ChartDocument.fromJson("{}"), name -> GLSL);
    }
    @Test
    public void newDocumentCopiesChartTempoAndRemainsCleanUntilEdit() throws Exception {
        ChartDocument chart =
            ChartDocument.fromJson("{\"BPMList\":[{\"startTime\":[0,0,1],\"bpm\":180}]}");
        ShaderDocument doc = new ShaderDocument(null, chart, name -> GLSL);
        assertEquals(1, doc.tempo().seconds(3), 1e-8);
        assertFalse(doc.dirty());
        assertEquals(10, doc.shaderNames().size());
        chart.bpmChanges.get(0).bpm = 60;
        assertEquals(1, doc.tempo().seconds(3), 1e-8);
        doc.add("noise", 2);
        assertTrue(doc.dirty());
        assertEquals(2, doc.start(0), 1e-8);
        assertEquals(4.0 / 3, doc.end(0) - doc.start(0), 1e-8);
    }
    @Test
    public void tempoConvertsBothDirectionsAcrossChangesAndNegativeBeats() throws Exception {
        ShaderDocument doc = fresh();
        doc.bpm(new JSONArray("[{\"time\":[0,0,1],\"bpm\":120},{\"time\":[4,0,1],\"bpm\":60},{"
                              + "\"time\":[8,0,1],\"bpm\":180}]"));
        for (double beat : new double[] {-2, 0, 1.5, 3.9999, 4, 5, 8, 10, 200})
            assertEquals(beat, doc.tempo().beatAt(doc.tempo().seconds(beat)), 1e-10);
        assertEquals(6, doc.tempo().seconds(8), 1e-10);
    }
    @Test
    public void editsPreserveUnknownRootEffectAndUniformFields() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        doc.range(0, 2, 6);
        doc.global(true);
        doc.value("power", 3.0);
        JSONObject raw = new JSONObject(doc.json());
        assertEquals(42, raw.getJSONObject("futureRoot").getInt("data"));
        assertEquals(7, doc.selected().getInt("futureEffect"));
        assertEquals(
            "[1,2]", doc.selected().getJSONObject("vars").getJSONArray("custom").toString());
        assertEquals(3, doc.compile(doc.json()).effects.get(0).uniforms.get("power").at(4)[0], 0);
    }
    @Test
    public void copyDeleteAndHistoryRestoreSelectionAndUnknownData() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        String initial = doc.json();
        doc.duplicate();
        assertEquals(2, doc.count());
        assertEquals(1, doc.selected);
        doc.value("power", 8);
        assertEquals(2, doc.effect(0).getJSONObject("vars").getInt("power"));
        doc.delete();
        assertEquals(1, doc.count());
        doc.undo();
        assertEquals(2, doc.count());
        assertEquals(8, doc.selected().getJSONObject("vars").getInt("power"));
        doc.undo();
        doc.undo();
        assertEquals(initial, doc.json());
        assertEquals(0, doc.selected);
        assertFalse(doc.dirty());
        doc.redo();
        assertEquals(2, doc.count());
    }
    @Test
    public void reorderingSkipsOppositePhaseAndChangesActualApplicationOrder() throws Exception {
        ShaderDocument doc = fresh();
        doc.add("noise", 0);
        doc.add("glitch", 0);
        doc.global(true);
        doc.add("pixel", 0);
        doc.reorder(-1);
        assertEquals(0, doc.selected);
        assertEquals("pixel", doc.effect(0).getString("shader"));
        assertEquals("glitch", doc.effect(1).getString("shader"));
        assertEquals("noise", doc.effect(2).getString("shader"));
        doc.undo();
        assertEquals("noise", doc.effect(0).getString("shader"));
    }
    @Test
    public void gestureHasOneUndoAndCancelRollsBackWithoutHistoryEntry() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        String initial = doc.json();
        doc.beginGesture();
        for (int i = 0; i < 20; i++) doc.range(0, 1 + i * .1, 5 + i * .1);
        doc.endGesture();
        doc.undo();
        assertEquals(initial, doc.json());
        assertFalse(doc.canUndo());
        doc.redo();
        String moved = doc.json();
        doc.beginGesture();
        doc.value("power", 99);
        doc.cancelGesture();
        assertEquals(moved, doc.json());
    }
    @Test
    public void invalidTimeTempoAndAnimationLeaveDocumentAndHistoryIntact() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        String initial = doc.json();
        rejects(() -> doc.range(0, 5, 1));
        rejects(() -> doc.bpm(0));
        rejects(() -> doc.value("color", new JSONArray().put(0).put(256).put(0).put(255)));
        JSONArray invalid = new JSONArray(
            "[{\"startTime\":[3,0,1],\"endTime\":[1,0,1],\"start\":0,\"end\":1,\"easingType\":1}]");
        rejects(() -> doc.value("power", invalid));
        assertEquals(initial, doc.json());
        assertFalse(doc.canUndo());
    }
    @Test
    public void defaultsExposeOnlyUserUniformsAndColorsUseBytes() throws Exception {
        ShaderDocument doc = fresh();
        doc.add("noise", 0);
        assertEquals(3, doc.parameters().size());
        assertEquals(.5, ((Number) doc.value(doc.parameters().get(0))).doubleValue(), 0);
        assertArrayEquals(
            new double[] {.1, -.2}, doc.components(doc.value(doc.parameters().get(1)), 2), 0);
        assertArrayEquals(new double[] {255, 128, 0, 255},
            doc.components(doc.value(doc.parameters().get(2)), 4), 0);
        rejects(() -> doc.valueFrom(new double[] {255, .5, 0, 255}, 4));
        rejects(() -> doc.valueFrom(new double[] {Double.POSITIVE_INFINITY}, 1));
    }
    @Test
    public void constantAnimationUsesEffectIntervalAndKeepsValueType() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        for (ShaderDocument.Parameter param : doc.parameters()) {
            JSONArray pairs = doc.animate(param);
            doc.value(param.name, pairs);
            assertEquals(doc.selected().getJSONArray("start").toString(),
                pairs.getJSONObject(0).getJSONArray("startTime").toString());
            assertEquals(
                param.size, doc.compile(doc.json()).effects.get(0).uniforms.get(param.name).size);
        }
    }
    @Test
    public void tempoEditsKeepRawEffectAndEventBeatPositions() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        ShaderDocument.Parameter param = doc.parameters().get(0);
        doc.value(param.name, doc.animate(param));
        String before = doc.selected().toString();
        doc.bpm(120);
        assertEquals(before, doc.selected().toString());
        assertEquals(.5, doc.start(0), 0);
        assertEquals(.5, doc.compile(doc.json()).effects.get(0).start, 0);
    }
    @Test
    public void savedSnapshotAndUndoAreIndependentOfCompiledPreview() throws Exception {
        ShaderDocument doc = original();
        doc.selected = 0;
        String snapshot = doc.json();
        ShaderTimeline preview = doc.compile(snapshot);
        doc.value("power", 10);
        doc.markSaved(snapshot);
        assertTrue(doc.dirty());
        assertEquals(2, preview.effects.get(0).uniforms.get("power").at(4)[0], 0);
        doc.markSaved(doc.json());
        assertFalse(doc.dirty());
        doc.undo();
        assertTrue(doc.dirty());
        doc.redo();
        assertFalse(doc.dirty());
    }
    @Test
    public void realShaderChartsSurviveAnUndoCycleWithoutLosingAnyRawFields() throws Exception {
        String directory = System.getenv("PHISTUDIO_SHADER_CHART_DIR");
        Assume.assumeNotNull(directory);
        int total = 0;
        for (String name : new String[] {"★ Ascension To Heaven.zip", "ANTINEUTRINO WITCHCRAFT.zip",
                 "Hello (BPM) 2025.zip"})
            try (ZipFile zip = new ZipFile(new File(directory, name))) {
                String extra =
                    new String(zip.getInputStream(zip.getEntry("extra.json")).readAllBytes(),
                        StandardCharsets.UTF_8);
                ShaderDocument doc =
                    new ShaderDocument(extra, ChartDocument.fromJson("{}"), shader -> {
                        if (shader.startsWith("/"))
                            return new String(zip.getInputStream(zip.getEntry(shader.substring(1)))
                                                  .readAllBytes(),
                                StandardCharsets.UTF_8);
                        String asset = shader.equals("circleBlur") ? "circle_blur"
                            : shader.equals("radialBlur")          ? "radial_blur"
                                                                   : shader;
                        return new String(
                            Files.readAllBytes(
                                new File("src/main/assets/shaders/" + asset + ".glsl").toPath()),
                            StandardCharsets.UTF_8);
                    });
                String snapshot = doc.json();
                assertFalse(doc.dirty());
                doc.selected = doc.count() - 1;
                doc.duplicate();
                doc.undo();
                assertEquals(snapshot, doc.json());
                assertFalse(doc.dirty());
                assertEquals(doc.count(), doc.compile(snapshot).effects.size());
                total += doc.count();
            }
        assertEquals(819, total);
    }
    private static void rejects(Runnable action) {
        try {
            action.run();
            fail("Invalid edit accepted");
        } catch (IllegalArgumentException expected) {
        }
    }
}
