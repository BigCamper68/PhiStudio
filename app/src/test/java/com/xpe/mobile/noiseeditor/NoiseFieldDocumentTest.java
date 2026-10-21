package com.xpe.mobile.noiseeditor;

import com.xpe.mobile.editor.EditHistory;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import com.xpe.mobile.packageio.*;
import com.xpe.mobile.preview.NoiseFieldEvaluator;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;

public class NoiseFieldDocumentTest {
    ChartDocument chart;
    NoiseFieldDocument doc;
    EditHistory history;
    boolean valid;
    @Before public void setup() throws Exception {
        chart = ChartDocument.fromJson("{}"); valid = true;
        history = new EditHistory(300, chart::markEdited);
        doc = new NoiseFieldDocument(chart, history, () -> valid);
    }
    @Test public void fieldsAndKeysShareHistoryAndSurviveSaveReload() throws Exception {
        doc.add(-2, 10, true);
        NoiseField original = doc.field();
        for (Channel channel : Channel.values()) {
            doc.channel = channel; doc.addKey(1.25);
            JSONObject key = NoiseFieldEdits.copy(NoiseFieldEdits.key(doc.field(), channel, 0));
            key.put("future", "keep");
            if (channel == Channel.ROTATE) key.put("rotation", -720.5).put("easeType", 14);
            else key.getJSONObject(channel.vector).put("x", -2.5).put("y", 0);
            if (channel.anchored()) key.getJSONObject("anchor").put("x", 1.8).put("y", -.2).put("futureAnchor", true);
            doc.replace(doc.field(), NoiseFieldEdits.withKey(doc.field(), channel, 0, key));
        }
        String edited = doc.field().toJson().toString();
        ChartDocument loaded = ChartDocument.fromJson(chart.toJsonString());
        assertEquals(edited, loaded.noiseFields.get(0).toJson().toString());
        assertTrue(loaded.noiseFields.get(0).subtract);
        for (int i = 0; i < 6; i++) doc.undo();
        assertSame(original, doc.field()); doc.undo(); assertTrue(chart.noiseFields.isEmpty());
        for (int i = 0; i < 7; i++) doc.redo();
        assertEquals(edited, doc.field().toJson().toString());
        assertTrue(chart.revision() >= 21);
    }
    @Test public void duplicateMovesAllNativeSecondTimesAndRetainsExtensions() throws Exception {
        doc.add(5, 8, false); doc.channel = Channel.SCALE; doc.addKey(4); doc.addKey(10);
        JSONObject json = NoiseFieldEdits.copy(doc.field().toJson()).put("futureField", new JSONArray().put("keep"));
        json.getJSONObject("bottomLeftPercentage").put("futureVector", 42);
        json.getJSONArray("scaleEvents").getJSONObject(1).put("futureKey", true);
        doc.replace(doc.field(), NoiseField.fromJson(json));
        NoiseField original = doc.field();
        doc.duplicate(20);
        assertEquals(2, chart.noiseFields.size()); assertSame(original, chart.noiseFields.get(0));
        assertEquals(20, doc.field().appear, 0); assertEquals(28, doc.field().disappear, 0);
        assertEquals(19, doc.field().scale.get(0).time, 0); assertEquals(25, doc.field().scale.get(1).time, 0);
        assertTrue(NoiseFieldEdits.key(doc.field(), Channel.SCALE, 1).getBoolean("futureKey"));
        assertEquals(42, doc.field().toJson().getJSONObject("bottomLeftPercentage").getInt("futureVector"));
        doc.undo(); assertEquals(0, doc.selected); assertEquals(Channel.SCALE, doc.channel); assertEquals(1, doc.selectedKey);
        doc.redo(); assertEquals(1, doc.selected); assertNull(doc.channel);
    }
    @Test public void unsortedAndDuplicateKeysKeepRawIdentityAndLastDuplicateWins() throws Exception {
        NoiseField field = NoiseFieldEdits.create(0, 10, false);
        for (double[] key : new double[][]{{5,.8},{1,.2},{1,.4}})
            field = NoiseFieldEdits.withKey(field, Channel.MOVE, -1,
                    new JSONObject().put("time", key[0]).put("endPosition", NoiseFieldEdits.vector(key[1], .5)));
        assertEquals(Arrays.asList(1,2,0), NoiseFieldEdits.sortedKeys(field, Channel.MOVE));
        assertEquals(.4, NoiseFieldEdits.sampledKey(field, Channel.MOVE, 1).getJSONObject("endPosition").getDouble("x"), 1e-7);
        NoiseField edited = NoiseFieldEdits.withKey(field, Channel.MOVE, 2,
                NoiseFieldEdits.copy(NoiseFieldEdits.key(field, Channel.MOVE, 2)).put("time", 7));
        assertEquals(Arrays.asList(1,0,2), NoiseFieldEdits.sortedKeys(edited, Channel.MOVE));
        assertEquals(5, NoiseFieldEdits.key(edited, Channel.MOVE, 0).getDouble("time"), 0);
        assertEquals(1, NoiseFieldEdits.key(field, Channel.MOVE, 2).getDouble("time"), 0);
    }
    @Test public void allFifteenOutgoingEasingsAreUsedWhenInsertingKeys() throws Exception {
        for (int ease = 0; ease < 15; ease++) {
            NoiseField field = NoiseFieldEdits.create(0, 10, false);
            JSONObject first = new JSONObject().put("time", 1).put("endPosition", NoiseFieldEdits.vector(0, 0))
                    .put("easeTypeX", ease).put("easeTypeY", 14-ease);
            JSONObject last = new JSONObject().put("time", 3).put("endPosition", NoiseFieldEdits.vector(1, 1));
            field = NoiseFieldEdits.withKey(NoiseFieldEdits.withKey(field, Channel.MOVE, -1, first), Channel.MOVE, -1, last);
            JSONObject sampled = NoiseFieldEdits.sampledKey(field, Channel.MOVE, 1.25).getJSONObject("endPosition");
            assertEquals(NoiseFieldEvaluator.ease(ease, .125f), sampled.getDouble("x"), 0);
            assertEquals(NoiseFieldEvaluator.ease(14-ease, .125f), sampled.getDouble("y"), 0);
        }
    }
    @Test public void phaseEditsRespectNativePairOrderingWithoutImposingExtraOrdering() throws Exception {
        NoiseField field = NoiseFieldEdits.create(2, 4, false);
        field = NoiseFieldEdits.phase(field, 1, -4); field = NoiseFieldEdits.phase(field, 2, 10);
        assertEquals(-4, field.enable, 0); assertEquals(10, field.disable, 0);
        try { NoiseFieldEdits.phase(field, 0, 7); fail(); } catch (JSONException expected) { }
        try { NoiseFieldEdits.phase(field, 2, -5); fail(); } catch (JSONException expected) { }
        try { NoiseFieldEdits.phase(field, 2, Double.NaN); fail(); } catch (JSONException expected) { }
    }
    @Test public void duplicateAndDeleteKeysRestoreSelectionOnUndo() throws Exception {
        doc.add(0, 10, false); doc.channel = Channel.ROTATE; doc.addKey(1);
        NoiseField initial = doc.field();
        doc.duplicateKey(2); assertEquals(1, doc.selectedKey); assertEquals(2, doc.field().rotate.size());
        doc.deleteKey(); assertEquals(-1, doc.selectedKey); assertEquals(1, doc.field().rotate.size());
        doc.undo(); assertEquals(1, doc.selectedKey); doc.undo(); assertEquals(0, doc.selectedKey); assertSame(initial, doc.field());
    }
    @Test public void staleFormAndStaleProjectCannotWriteIntoNewChart() throws Exception {
        doc.add(1, 2, false); NoiseField stale = doc.field(); doc.add(4, 2, true);
        assertFalse(doc.replace(stale, NoiseFieldEdits.shifted(stale, 20)));
        int count = doc.count(); valid = false; doc.add(2, 2, false); doc.delete(); doc.undo();
        assertEquals(count, doc.count()); assertNull(doc.field());
    }
    @Test public void copyIsImmutableAndPastesAfterSourceWasDeleted() throws Exception {
        doc.add(1, 3, true); doc.copy(); doc.delete(); doc.paste(5);
        assertEquals(1, doc.count()); assertEquals(5, doc.field().appear, 0); assertTrue(doc.field().subtract);
        doc.replace(doc.field(), NoiseFieldEdits.bounds(doc.field(), -2, -3, 2, 3)); doc.paste(7);
        assertEquals(.25, doc.field().left, 0); assertEquals(7, doc.field().appear, 0);
    }
    @Test public void liveInputCannotMergeAcrossAnotherChartCommandOrUndoRedo() throws Exception {
        doc.add(0, 10, false); NoiseField original = doc.field(); Object input = new Object();
        doc.replaceLive(doc.field(), NoiseFieldEdits.bounds(doc.field(), .1, .25, .75, .75), input);
        doc.replaceLive(doc.field(), NoiseFieldEdits.bounds(doc.field(), .2, .25, .75, .75), input);
        NoiseField firstEdit = doc.field();
        int[] other = {0};
        history.execute(new EditHistory.Command() {
            public void apply() { other[0] = 1; }
            public void revert() { other[0] = 0; }
        });
        doc.replaceLive(doc.field(), NoiseFieldEdits.bounds(doc.field(), .3, .25, .75, .75), input);
        doc.undo(); assertSame(firstEdit, doc.field()); assertEquals(1, other[0]);
        doc.undo(); assertSame(firstEdit, doc.field()); assertEquals(0, other[0]);
        doc.undo(); assertSame(original, doc.field());
        doc.redo(); assertSame(firstEdit, doc.field());
        doc.replaceLive(doc.field(), NoiseFieldEdits.bounds(doc.field(), .4, .25, .75, .75), input);
        assertFalse(doc.canRedo()); doc.undo(); assertSame(firstEdit, doc.field());
    }
    @Test public void editingSuppliedDesultoryRoundTripsAllOtherFieldsAndAudioThroughPackage() throws Exception {
        String archive = System.getenv("PHISTUDIO_NOISE_FIELD_CHART"); Assume.assumeNotNull(archive);
        Path temp = Files.createTempDirectory("noise-authoring");
        try {
            ChartPackage pack;
            try (var input = new FileInputStream(archive)) { pack = new PackageImporter().importPackage(input, temp.resolve("first").toFile(), "Desultory.pez"); }
            ChartDocument chart = pack.getChart(); assertEquals(160, chart.noiseFields.size());
            List<String> original = new ArrayList<>(); for (NoiseField field : chart.noiseFields) original.add(field.toJson().toString());
            NoiseFieldDocument doc = new NoiseFieldDocument(chart, new EditHistory(300, chart::markEdited), () -> true);
            doc.select(80); doc.replace(doc.field(), NoiseFieldEdits.shifted(doc.field(), .125));
            doc.channel = Channel.ROTATE; doc.addKey(83.25);
            doc.replace(doc.field(), NoiseField.fromJson(NoiseFieldEdits.copy(doc.field().toJson())
                    .put(NoiseField.COLOR, new JSONArray("[40,180,255]"))));
            String expected = doc.field().toExportJson().toString();
            ByteArrayOutputStream output = new ByteArrayOutputStream(); new PackageExporter().exportPackage(pack, chart, output);
            ChartPackage reopened = new PackageImporter().importPackage(new ByteArrayInputStream(output.toByteArray()), temp.resolve("second").toFile(), "edited.pez");
            assertEquals(160, reopened.getChart().noiseFields.size()); assertEquals(2026, reopened.getChart().totalNotes());
            assertTrue(reopened.getAudioFile().isFile());
            assertArrayEquals(Files.readAllBytes(pack.getAudioFile().toPath()), Files.readAllBytes(reopened.getAudioFile().toPath()));
            assertEquals(0xff28b4ff, reopened.getChart().noiseFields.get(80).color);
            for (int i = 0; i < 160; i++) assertEquals(i == 80 ? expected : original.get(i), reopened.getChart().noiseFields.get(i).toJson().toString());
            doc.undo(); doc.undo(); doc.undo(); assertEquals(original.get(80), doc.field().toJson().toString());
        } finally { try (var files = Files.walk(temp)) { files.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException e) { throw new UncheckedIOException(e); } }); } }
    }
}
