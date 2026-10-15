package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.ChartEvaluator;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PackageDisplayMetadataTest extends PackageIoTestSupport {
    @Test public void yamlDisplayOverridesLeaveAuthoredRpeMetaAndAssetsIntact() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        ChartDocument chart = pack.getChart();
        assertEquals("Manifest title", chart.getDisplayName());
        assertEquals("AT Lv.17", chart.getDisplayLevel());
        assertEquals("Manifest composer", chart.getDisplayComposer());
        assertEquals("Manifest charter", chart.getDisplayCharter());
        assertEquals("Manifest title", ChartEvaluator.evaluate(chart, 0, false).hud.name);
        assertEquals("AT Lv.17", ChartEvaluator.evaluate(chart, 0, false).hud.level);
        assertAuthoredMetadata(chart);
        assertEquals("rpe-id", chart.id); // server ChartInfo.id is not the authored RPE identifier
        PackageWorkspaceWriter.writeChart(pack, chart);
        assertEquals(new String(entries.get("info.yml"), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(pack.getWorkspace(), "info.yml").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip");
        assertEquals("AT Lv.17", reopened.getChart().getDisplayLevel());
        assertAuthoredMetadata(reopened.getChart());
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals("AT Lv.17", exported.getChart().getDisplayLevel());
        assertAuthoredMetadata(exported.getChart());
        JSONObject meta = new JSONObject(exported.getChart().toJsonString()).getJSONObject("META");
        assertEquals("OL Lv.13", meta.getString("level"));
        assertEquals("keep-meta", meta.getString("futureMeta"));
    }

    @Test public void blankNullAndMissingYamlFallBackToTxtThenAuthoredMeta() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        entries.put("info.yml", bytes("name: ''\nlevel: null # absent\ncomposer: \"  \"\n"
                + "chart: chart.data\nintro: |-\n  charter: Nested must not override\n"));
        entries.put("info.txt", bytes("#\nName: TXT title\nLevel: IN Lv.15\nComposer: \n"));
        ChartDocument chart = importPackage(zip(entries), PackageLimits.DEFAULT).getChart();
        assertEquals("TXT title", chart.getDisplayName());
        assertEquals("IN Lv.15", chart.getDisplayLevel());
        assertEquals("Composer", chart.getDisplayComposer());
        assertEquals("Authored charter", chart.getDisplayCharter());
        assertAuthoredMetadata(chart);
        entries.remove("info.txt");
        chart = importPackage(zip(entries), PackageLimits.DEFAULT).getChart();
        assertEquals("Unit chart", chart.getDisplayName());
        assertEquals("OL Lv.13", chart.getDisplayLevel());
    }

    @Test public void metadataEditsUpdateOnlyThoseYamlFieldsAndPersistAcrossReopenAndExport() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        String manifest = "# retain comment\r\nname: Manifest title # name comment\r\n"
                + "level: AT Lv.17\r\ncomposer: Manifest composer\r\ncharter: Manifest charter\r\n"
                + "chart: chart.data\r\nfutureField: {opaque: true}\r\n";
        entries.put("info.yml", bytes(manifest));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        pack.getChart().name = "Edited \"title\" # value";
        pack.getChart().level = "HD Lv.10";
        assertEquals(pack.getChart().name, pack.getChart().getDisplayName());
        assertEquals("HD Lv.10", pack.getChart().getDisplayLevel());
        assertTrue(pack.getChart().isPackageMetadataEdited("name"));
        assertFalse(pack.getChart().isPackageMetadataEdited("composer"));
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        String changed = new String(Files.readAllBytes(new File(pack.getWorkspace(), "info.yml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(changed.contains("name: \"Edited \\\"title\\\" # value\" # name comment\r\n"));
        assertTrue(changed.contains("level: \"HD Lv.10\"\r\n"));
        assertTrue(changed.contains("composer: Manifest composer\r\ncharter: Manifest charter\r\n"));
        assertTrue(changed.contains("futureField: {opaque: true}\r\n"));
        for (ChartPackage result : new ChartPackage[]{
                new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip"),
                importPackage(export(pack), PackageLimits.DEFAULT)}) {
            assertEquals(pack.getChart().name, result.getChart().name);
            assertEquals(pack.getChart().name, result.getChart().getDisplayName());
            assertEquals("HD Lv.10", result.getChart().getDisplayLevel());
            assertEquals("Manifest composer", result.getChart().getDisplayComposer());
            assertEquals("Composer", result.getChart().composer);
        }
    }

    @Test public void unsavedEditsAreAppliedByExportAndCanExplicitlyClearAField() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        entries.put("info.txt", bytes("#\nComposer: old TXT composer\nChart: chart.data\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        pack.getChart().composer = "";
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals("", exported.getChart().composer);
        assertEquals("", exported.getChart().getDisplayComposer());
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        assertEquals("", new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip")
                .getChart().getDisplayComposer());
    }

    @Test public void txtOnlyDisplayAndEditsSurviveBothExportAndWorkspaceReopen() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        entries.remove("info.yml");
        entries.put("info.txt", bytes("#\nName: TXT title\nLevel: TXT level\nChart: chart.data\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals("TXT title", exported.getChart().getDisplayName());
        assertEquals("Unit chart", exported.getChart().name);
        pack.getChart().level = "Edited level";
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip");
        assertEquals("Edited level", reopened.getChart().getDisplayLevel());
        assertEquals("TXT title", reopened.getChart().getDisplayName());
    }

    @Test public void creatingYamlForDimKeepsTxtDisplayMetadata() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        entries.remove("info.yml");
        entries.put("info.txt", bytes("#\nName: TXT title\nLevel: TXT level\nChart: chart.data\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        pack.getChart().backgroundDim = .25;
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals("TXT title", exported.getChart().getDisplayName());
        assertEquals("TXT level", exported.getChart().getDisplayLevel());
        assertEquals("Unit chart", exported.getChart().name);
    }

    @Test public void convertedPecMetadataCanStillBeEditedAndReopened() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.put("info.yml", bytes("name: Legacy title\nlevel: AT Lv.17\nchart: chart.data\n"));
        entries.put("chart.data", bytes("195\nbp 0 120\ncp 0 0 1024 700\nn1 0 1 512 1 0\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        assertEquals("Legacy title", pack.getChart().name);
        assertEquals("AT Lv.17", pack.getChart().getDisplayLevel());
        pack.getChart().name = "Edited legacy";
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip");
        assertEquals("Edited legacy", reopened.getChart().name);
        assertEquals("Edited legacy", reopened.getChart().getDisplayName());
    }

    @Test public void revertingMetadataAfterAutosaveRestoresOriginalPackageDisplayValue() throws Exception {
        ChartPackage pack = importPackage(zip(entriesWithConflictingMetadata()), PackageLimits.DEFAULT);
        pack.getChart().level = "HD Lv.10";
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        pack.getChart().level = "OL Lv.13"; // undo the edit to authored META
        assertEquals("AT Lv.17", pack.getChart().getDisplayLevel());
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip");
        assertEquals("OL Lv.13", reopened.getChart().level);
        assertEquals("AT Lv.17", reopened.getChart().getDisplayLevel());
    }

    @Test public void standaloneRpeHasNoPackageOverrideAndUsesCurrentEdits() throws Exception {
        ChartDocument chart = ChartDocument.fromJson(new String(rpeJson(), StandardCharsets.UTF_8));
        assertEquals("Unit chart", chart.getDisplayName());
        assertEquals("", chart.getDisplayLevel());
        chart.level = "Edited level";
        assertEquals("Edited level", ChartEvaluator.evaluate(chart, 0, false).hud.level);
        assertFalse(chart.isPackageMetadataEdited("level"));
    }

    @Test public void yamlCommentsAndQuotedHashesAreHandledWithoutChangingTxt() {
        PackageManifest yaml = PackageManifest.parse("info.yml", PackageManifest.Kind.YAML,
                "name: \"Quoted # hash\" # comment\nlevel: AT Lv.17 # comment\n"
                        + "composer: 'It''s # quoted' # comment\ncharter: abc#def\nartist: It's plain # comment\n"
                        + "intro: |-\n  name: hidden\n  music: hidden.ogg\n");
        assertEquals("Quoted # hash", yaml.get("name"));
        assertEquals("AT Lv.17", yaml.get("level"));
        assertEquals("It's # quoted", yaml.get("composer"));
        assertEquals("abc#def", yaml.get("charter"));
        assertEquals("It's plain", yaml.get("artist"));
        assertEquals(null, yaml.get("music"));
        assertEquals("TXT # data", PackageManifest.parse("info.txt", PackageManifest.Kind.TEXT,
                "#\nName: TXT # data\n").get("name"));
    }

    @Test public void indentedYamlAndEscapedScalarsSurviveMetadataEdits() throws Exception {
        Map<String, byte[]> entries = entriesWithConflictingMetadata();
        entries.put("info.yml", bytes("  name: \"title\\\\n\\u266b\" # comment\n"
                + "  level: AT Lv.17\n  chart: chart.data\n  intro: |-\n    name: hidden\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        assertEquals("title\\n♫", pack.getChart().getDisplayName());
        pack.getChart().level = "Edited \\n level";
        pack.getChart().backgroundDim = .25;
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip");
        assertEquals("title\\n♫", reopened.getChart().getDisplayName());
        assertEquals("Edited \\n level", reopened.getChart().getDisplayLevel());
        assertEquals("chart.data", reopened.getChartPath());
        assertEquals(.25, reopened.getBackgroundDim(), 0);
    }

    private static Map<String, byte[]> entriesWithConflictingMetadata() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        JSONObject root = new JSONObject(new String(rpeJson(), StandardCharsets.UTF_8));
        root.getJSONObject("META").put("level", "OL Lv.13").put("charter", "Authored charter")
                .put("id", "rpe-id");
        entries.put("chart.data", bytes(root.toString()));
        entries.put("info.yml", bytes("name: Manifest title\nlevel: AT Lv.17\n"
                + "composer: Manifest composer\ncharter: Manifest charter\nid: 53239\nchart: chart.data\n"));
        return entries;
    }

    private static void assertAuthoredMetadata(ChartDocument chart) {
        assertEquals("Unit chart", chart.name);
        assertEquals("OL Lv.13", chart.level);
        assertEquals("Composer", chart.composer);
        assertEquals("Authored charter", chart.charter);
    }
}
