package com.xpe.mobile.packageio;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Expected strings were checked with a full YAML parser, as used by Phira's ChartInfo. */
public final class PackageYamlBlockScalarTest extends PackageIoTestSupport {
    @Test public void literalAndFoldedScalarsRespectChompingAndLineBreaks() {
        String[][] cases = {
                {"|\n  a\n  b\n", "a\nb\n"},
                {"|-\n  a\n  b\n\n", "a\nb"},
                {"|+\n  a\n\n", "a\n\n"},
                {">\n  a\n  b\n", "a b\n"},
                {">-\n  a\n\n  b\n", "a\nb"},
                {">+\n  a\n\n\n", "a\n\n\n"},
                {">\n\n  a\n", "\na\n"},
                {">\n  a\n    indented\n  b\n", "a\n  indented\nb\n"},
                {">\n  a\n\n    indented\n\n  b\n", "a\n\n  indented\n\nb\n"},
                {"|\n  a", "a"},
                {"|\n\n", ""},
                {"|+\n\n", "\n"}
        };
        for (String[] item : cases) {
            assertEquals(item[0], item[1], manifest("name: " + item[0]).get("name"));
        }
    }

    @Test public void tabsAndUnicodeWhitespaceAreContentInsteadOfIndentation() {
        assertEquals("\ttab\nnext", manifest("name: |-\n  \ttab\n  next\n").get("name"));
        assertEquals("first \u2003next", manifest("name: >-\n  first\n  \u2003next\n").get("name"));
        assertEquals("", manifest("name: |\n    \n  # outside scalar\nchart: chart.data\n").get("name"));
    }

    @Test public void blockIndentIndicatorsCommentsAndCrLfPreserveScalarContent() {
        String source = "\ufeff  name: |2- # literal title\r\n"
                + "      first\r\n    second # data\r\n"
                + "  level: >-2 # folded level\r\n    AT\r\n    Lv.17\r\n"
                + "  intro: |-\r\n    name: hidden\r\n    level: hidden\r\n";
        PackageManifest manifest = manifest(source);
        assertEquals("  first\nsecond # data", manifest.get("name"));
        assertEquals("AT Lv.17", manifest.get("level"));
        assertEquals("name: hidden\nlevel: hidden", manifest.get("intro"));
    }

    @Test public void packageDisplayRetainsQuotedSpacesAndBlockChomping() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.put("info.yml", bytes("name: |+\n  Manifest title\n\n"
                + "level: >\n  AT\n  Lv.17\ncomposer: ' Composer '\nchart: chart.data\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        assertEquals("Manifest title\n\n", pack.getChart().getDisplayName());
        assertEquals("AT Lv.17\n", pack.getChart().getDisplayLevel());
        assertEquals(" Composer ", pack.getChart().getDisplayComposer());
        assertEquals("Unit chart", pack.getChart().name);
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        for (ChartPackage result : new ChartPackage[]{
                new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip"),
                importPackage(export(pack), PackageLimits.DEFAULT)}) {
            assertEquals("Manifest title\n\n", result.getChart().getDisplayName());
            assertEquals("AT Lv.17\n", result.getChart().getDisplayLevel());
            assertEquals(" Composer ", result.getChart().getDisplayComposer());
            assertEquals("Unit chart", result.getChart().name);
        }
    }

    @Test public void editedBlockRemovesItsBodyAndKeepsOtherManifestText() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        String intro = "  intro: |-\r\n    Intro\r\n    name: hidden\r\n";
        String tail = "  futureField: {opaque: true}";
        String source = "\ufeff  name: |2-  # title comment\r\n    Old title\r\n"
                + "  level: >-\r\n    AT\r\n    Lv.17\r\n"
                + intro + "  chart: chart.data\r\n  # retained comment\r\n" + tail;
        entries.put("info.yml", bytes(source));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        pack.getChart().name = "Edited title";
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        String changed = new String(Files.readAllBytes(new File(pack.getWorkspace(), "info.yml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("\ufeff  name: \"Edited title\"  # title comment\r\n"
                + "  level: >-\r\n    AT\r\n    Lv.17\r\n"
                + intro + "  chart: chart.data\r\n  # retained comment\r\n" + tail, changed);
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip");
        assertEquals("Edited title", reopened.getChart().getDisplayName());
        assertEquals("AT Lv.17", reopened.getChart().getDisplayLevel());
    }

    @Test public void blockMetadataClearAndUndoSurviveWorkspaceAndExport() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.put("info.yml", bytes("name: Unit package\ncomposer: >+ # composer comment\n"
                + "  Manifest\n  composer\n\nchart: chart.data\nintro: |-\n  untouched\n"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        assertEquals("Manifest composer\n\n", pack.getChart().getDisplayComposer());
        pack.getChart().composer = "";
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals("", exported.getChart().getDisplayComposer());
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        String cleared = new String(Files.readAllBytes(new File(pack.getWorkspace(), "info.yml").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(cleared.contains("composer: \"\" # composer comment\nchart: chart.data\n"));
        assertFalse(cleared.contains("  Manifest\n"));
        pack.getChart().composer = "Composer"; // undo to authored META restores the package display.
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        for (ChartPackage result : new ChartPackage[]{
                new PackageWorkspaceLoader().load(pack.getWorkspace(), "fixture.zip"),
                importPackage(export(pack), PackageLimits.DEFAULT)}) {
            assertEquals("Composer", result.getChart().composer);
            assertEquals("Manifest composer\n\n", result.getChart().getDisplayComposer());
        }
        assertTrue(new String(Files.readAllBytes(new File(pack.getWorkspace(), "info.yml").toPath()), java.nio.charset.StandardCharsets.UTF_8)
                .endsWith("intro: |-\n  untouched\n"));
    }

    private static PackageManifest manifest(String source) {
        return PackageManifest.parse("info.yml", PackageManifest.Kind.YAML, source);
    }
}
