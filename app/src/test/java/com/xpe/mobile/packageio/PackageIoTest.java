package com.xpe.mobile.packageio;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PackageIoTest extends PackageIoTestSupport {
    @Test
    public void importsNormalPackageByRpeStructureWithoutJsonExtension() throws Exception {
        ChartPackage chartPackage = importPackage(zip(normalEntries()), PackageLimits.DEFAULT);

        assertEquals("chart.data", chartPackage.getChartPath());
        assertEquals("music/song.ogg", chartPackage.getAudioPath());
        assertEquals("art/cover.png", chartPackage.getIllustrationPath());
        assertEquals("Unit package", chartPackage.getProjectName());
        assertEquals("Unit chart", chartPackage.getChart().name);
        assertFalse(chartPackage.isUseRpe170Speed());
        assertTrue(new File(chartPackage.getWorkspace(), "extras/unknown.bin").isFile());
    }

    @Test
    public void manifestSelectsAndLoadsOneOfMultipleRpeCandidates() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: Selected package\nchart: charts/second.json\n"));
        entries.put("charts/first.json", bytes(new String(
                rpeJson(), StandardCharsets.UTF_8).replace("Unit chart", "First chart")));
        entries.put("charts/second.json", bytes(new String(
                rpeJson(), StandardCharsets.UTF_8).replace("Unit chart", "Second chart")));

        ChartPackage chartPackage = importPackage(zip(entries), PackageLimits.DEFAULT);

        assertEquals("charts/second.json", chartPackage.getChartPath());
        assertEquals("Second chart", chartPackage.getChart().name);
    }

    @Test
    public void convertsOfficialPhigrosV3IntoEditableRpe() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: Official\ncomposer: Composer\n"
                + "charter: Charter\nlevel: IN 15\nchart: official.json\n"
                + "music: song.ogg\nillustration: cover.png\n"));
        entries.put("official.json", bytes(officialV3Json()));
        entries.put("song.ogg", new byte[]{1, 2, 3});
        entries.put("cover.png", new byte[]{4, 5});

        ChartPackage chartPackage = importPackage(zip(entries), PackageLimits.DEFAULT);

        assertEquals("Official", chartPackage.getChart().name);
        assertEquals("Composer", chartPackage.getChart().composer);
        assertEquals("Charter", chartPackage.getChart().charter);
        assertEquals("IN 15", chartPackage.getChart().level);
        assertEquals(125, chartPackage.getChart().offsetMs);
        assertEquals(1, chartPackage.getChart().judgeLines.size());
        assertEquals(2, chartPackage.getChart().judgeLines.get(0).notes.size());
        assertEquals(151.875,
                chartPackage.getChart().judgeLines.get(0).notes.get(0).positionX, 0.0001);
        assertEquals(-10.0, chartPackage.getChart().judgeLines.get(0)
                .eventLayers.get(0).events(com.xpe.mobile.model.EventType.ROTATE)
                .get(0).start, 0.0001);
        assertEquals(9.0, chartPackage.getChart().judgeLines.get(0)
                .eventLayers.get(0).events(com.xpe.mobile.model.EventType.SPEED)
                .get(0).start, 0.0001);
        assertEquals(ChartJsonFormat.RPE, ChartJsonFormatDetector.detect(new JSONObject(
                new String(Files.readAllBytes(new File(chartPackage.getWorkspace(),
                        "official.json").toPath()), StandardCharsets.UTF_8))));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(
                new File(chartPackage.getWorkspace(), "song.ogg").toPath()));
    }

    @Test
    public void convertsLegacyPecContentEvenWhenNamedJson() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: Legacy\nchart: chart.json\n"
                + "music: song.ogg\nillustration: cover.png\n"));
        entries.put("chart.json", bytes("195\r\n"
                + "bp 0.000 210.000\r\n"
                + "cp 0 0.000 1024.00 700.00\r\n"
                + "cm 0 0.000 4.000 2048.00 1400.00 2\r\n"
                + "cv 0 0.000 7.000\r\n"
                + "n1 0 1.000 512.00 1 0 # 1.5 & 0.8\r\n"));
        entries.put("song.ogg", new byte[]{1});
        entries.put("cover.png", new byte[]{2});

        ChartPackage chartPackage = importPackage(zip(entries), PackageLimits.DEFAULT);

        assertEquals("Legacy", chartPackage.getChart().name);
        assertEquals(45, chartPackage.getChart().offsetMs);
        assertEquals(210.0, chartPackage.getChart().bpmChanges.get(0).bpm, 0.0);
        assertEquals(1, chartPackage.getChart().judgeLines.get(0).notes.size());
        assertEquals(337.5,
                chartPackage.getChart().judgeLines.get(0).notes.get(0).positionX, 0.0001);
        assertEquals(1.5, chartPackage.getChart().judgeLines.get(0).notes.get(0).speed, 0.0);
        // Independent full upstream PEC dump: f32 size .8 and cv7 native speed.
        assertEquals(0.800000011920929,
                chartPackage.getChart().judgeLines.get(0).notes.get(0).size, 0.0);
        float rpeSpeed = (float) chartPackage.getChart().judgeLines.get(0).eventLayers.get(0)
                .events(com.xpe.mobile.model.EventType.SPEED).get(0).start;
        assertEquals(1.196581244468689,
                rpeSpeed * (float) (10.0 / 45.0 / .83175), 0.0);
        assertEquals(675.0, chartPackage.getChart().judgeLines.get(0).eventLayers.get(0)
                .events(com.xpe.mobile.model.EventType.MOVE_X).get(0).end, 0.0001);
        assertEquals(ChartJsonFormat.RPE, ChartJsonFormatDetector.detect(new JSONObject(
                new String(Files.readAllBytes(new File(chartPackage.getWorkspace(),
                        "chart.json").toPath()), StandardCharsets.UTF_8))));
    }

    @Test
    public void importsPackageWithMissingAudio() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("chart: chart.data\nmusic: absent.ogg\nillustration: art.png\n"));
        entries.put("chart.data", rpeJson());
        entries.put("art.png", new byte[]{4, 5});

        ChartPackage chartPackage = importPackage(zip(entries), PackageLimits.DEFAULT);

        assertNull(chartPackage.getAudioPath());
        assertEquals("art.png", chartPackage.getIllustrationPath());
    }

    @Test
    public void importsPackageWithMissingIllustration() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.txt", bytes("Chart: chart.data\nSong: song.ogg\nPicture: absent.png\n"));
        entries.put("chart.data", rpeJson());
        entries.put("song.ogg", new byte[]{6, 7});

        ChartPackage chartPackage = importPackage(zip(entries), PackageLimits.DEFAULT);

        assertEquals("song.ogg", chartPackage.getAudioPath());
        assertNull(chartPackage.getIllustrationPath());
    }

    @Test
    public void supportsUnicodeEntryNames() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: Юникод\nchart: данные/чарт.rpe\nmusic: музыка/песня.ogg\n"));
        entries.put("данные/чарт.rpe", rpeJson());
        entries.put("музыка/песня.ogg", new byte[]{8, 9});

        ChartPackage chartPackage = importPackage(zip(entries), PackageLimits.DEFAULT);

        assertEquals("данные/чарт.rpe", chartPackage.getChartPath());
        assertEquals("музыка/песня.ogg", chartPackage.getAudioPath());
        assertTrue(new File(chartPackage.getWorkspace(), "данные/чарт.rpe").isFile());
    }
}
