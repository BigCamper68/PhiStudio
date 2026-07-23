package com.xpe.mobile.packageio;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PackageIoTest {
    private File temporaryRoot;
    private int workspaceCounter;

    @Before
    public void setUp() throws Exception {
        temporaryRoot = Files.createTempDirectory("xpe-package-tests-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(temporaryRoot);
    }

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
    public void exportsAndReimportsPackageWithEditedChart() throws Exception {
        ChartPackage first = importPackage(zip(normalEntries()), PackageLimits.DEFAULT);
        first.getChart().name = "Edited name";

        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);

        assertEquals("Edited name", second.getChart().name);
        assertEquals(first.getChartPath(), second.getChartPath());
        assertEquals(first.getEntries().size(), second.getEntries().size());
    }

    @Test
    public void preservesUnknownPackageFile() throws Exception {
        byte[] expected = new byte[]{0, 1, 2, 3, (byte) 0xff, 17};
        Map<String, byte[]> entries = normalEntries();
        entries.put("extras/future.resource", expected);
        ChartPackage first = importPackage(zip(entries), PackageLimits.DEFAULT);

        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);

        assertArrayEquals(expected, Files.readAllBytes(
                new File(second.getWorkspace(), "extras/future.resource").toPath()));
    }

    @Test
    public void preservesUnknownManifestDataAndLinesExactly() throws Exception {
        String manifest = "# preserved comment\r\n"
                + "name: \"Manifest test\"\r\n"
                + "chart: chart.data\r\n"
                + "futureField: {opaque: true}\r\n"
                + "custom line without colon\r\n";
        Map<String, byte[]> entries = normalEntries();
        entries.put("info.yml", bytes(manifest));
        ChartPackage first = importPackage(zip(entries), PackageLimits.DEFAULT);

        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);

        assertEquals(manifest, second.getManifests().get(0).getSourceText());
        assertEquals("{opaque: true}", second.getManifests().get(0).get("futureField"));
    }

    @Test
    public void preservesUnknownRpeJsonFields() throws Exception {
        ChartPackage first = importPackage(zip(normalEntries()), PackageLimits.DEFAULT);
        first.getChart().composer = "Changed composer";

        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);
        JSONObject json = new JSONObject(second.getChart().toJsonString());

        assertEquals("keep-root", json.getString("futureRoot"));
        assertEquals("keep-meta", json.getJSONObject("META").getString("futureMeta"));
        assertEquals("Changed composer", json.getJSONObject("META").getString("composer"));
    }

    @Test
    public void rejectsParentTraversalAndRemovesPartialWorkspace() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("safe.bin", bytes("safe"));
        entries.put("../escape.bin", bytes("bad"));
        File workspace = nextWorkspace();

        assertPackageError(PackageException.Code.UNSAFE_PATH, zip(entries),
                PackageLimits.DEFAULT, workspace);
        assertFalse(workspace.exists());
        assertFalse(new File(temporaryRoot, "escape.bin").exists());
    }

    @Test
    public void rejectsAbsolutePath() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("/absolute/chart.data", rpeJson());

        assertPackageError(PackageException.Code.UNSAFE_PATH, zip(entries),
                PackageLimits.DEFAULT, nextWorkspace());
    }

    @Test
    public void rejectsDuplicateNormalizedPath() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("folder\\chart.data", rpeJson());
        entries.put("folder/chart.data", rpeJson());

        assertPackageError(PackageException.Code.DUPLICATE_PATH, zip(entries),
                PackageLimits.DEFAULT, nextWorkspace());
    }

    @Test
    public void enforcesEntryCountLimit() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one.bin", bytes("1"));
        entries.put("two.bin", bytes("2"));

        assertPackageError(PackageException.Code.ENTRY_COUNT_LIMIT, zip(entries),
                new PackageLimits(1, 1024, 2048), nextWorkspace());
    }

    @Test
    public void enforcesPerEntrySizeLimit() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("large.bin", new byte[11]);

        assertPackageError(PackageException.Code.ENTRY_SIZE_LIMIT, zip(entries),
                new PackageLimits(10, 10, 100), nextWorkspace());
    }

    @Test
    public void enforcesTotalExtractedSizeLimit() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one.bin", new byte[6]);
        entries.put("two.bin", new byte[6]);

        assertPackageError(PackageException.Code.TOTAL_SIZE_LIMIT, zip(entries),
                new PackageLimits(10, 100, 10), nextWorkspace());
    }

    @Test
    public void rejectsPackageWithoutChartJson() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.json", bytes("{\"notAChart\":true}"));

        PackageException exception = assertPackageError(PackageException.Code.MISSING_CHART,
                zip(entries), PackageLimits.DEFAULT, nextWorkspace());
        assertNotNull(exception.getRetainedWorkspace());
        assertTrue(new File(exception.getRetainedWorkspace(), "metadata.json").isFile());
    }

    @Test
    public void rejectsMultipleAmbiguousRpeJsonCandidates() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one.json", rpeJson());
        entries.put("two.data", rpeJson());

        assertPackageError(PackageException.Code.AMBIGUOUS_CHART, zip(entries),
                PackageLimits.DEFAULT, nextWorkspace());
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
        assertEquals(150.0,
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
        assertEquals(0.8, chartPackage.getChart().judgeLines.get(0).notes.get(0).size, 0.0);
        assertEquals(4.5, chartPackage.getChart().judgeLines.get(0).eventLayers.get(0)
                .events(com.xpe.mobile.model.EventType.SPEED).get(0).start, 0.0001);
        assertEquals(675.0, chartPackage.getChart().judgeLines.get(0).eventLayers.get(0)
                .events(com.xpe.mobile.model.EventType.MOVE_X).get(0).end, 0.0001);
        assertEquals(ChartJsonFormat.RPE, ChartJsonFormatDetector.detect(new JSONObject(
                new String(Files.readAllBytes(new File(chartPackage.getWorkspace(),
                        "chart.json").toPath()), StandardCharsets.UTF_8))));
    }

    @Test
    public void appliesAndPreservesYamlPackageOffsetInSeconds() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.put("info.yml", bytes("name: Offset package\n"
                + "chart: chart.data\nmusic: music/song.ogg\noffset: -0.025\n"));
        ChartPackage first = importPackage(zip(entries), PackageLimits.DEFAULT);

        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);

        assertEquals(-25L, first.getManifestOffsetMs());
        assertEquals(-25L, second.getManifestOffsetMs());
        assertTrue(second.getManifests().get(0).getSourceText().contains("offset: -0.025"));
    }

    @Test
    public void appliesAndPreservesRpe170SpeedCompatibilityFlag() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.put("info.yml", bytes("name: Speed package\n"
                + "chart: chart.data\nmusic: music/song.ogg\nuseRpe170Speed: true\n"));

        ChartPackage first = importPackage(zip(entries), PackageLimits.DEFAULT);
        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);

        assertTrue(first.isUseRpe170Speed());
        assertTrue(second.isUseRpe170Speed());
        assertTrue(second.getManifests().get(0).getSourceText()
                .contains("useRpe170Speed: true"));
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

    @Test
    public void roundTripDoesNotChangeAudioOrIllustrationBytes() throws Exception {
        byte[] audio = new byte[]{0, 11, 22, 33, (byte) 0xfe, (byte) 0xff};
        byte[] image = new byte[]{(byte) 0x89, 80, 78, 71, 0, 1, 2, 3};
        Map<String, byte[]> entries = normalEntries();
        entries.put("music/song.ogg", audio);
        entries.put("art/cover.png", image);
        ChartPackage first = importPackage(zip(entries), PackageLimits.DEFAULT);

        byte[] exported = export(first);
        Map<String, byte[]> exportedEntries = unzip(exported);
        ChartPackage second = importPackage(exported, PackageLimits.DEFAULT);

        assertArrayEquals(audio, exportedEntries.get("music/song.ogg"));
        assertArrayEquals(image, exportedEntries.get("art/cover.png"));
        assertArrayEquals(audio, Files.readAllBytes(second.getAudioFile().toPath()));
        assertArrayEquals(image, Files.readAllBytes(second.getIllustrationFile().toPath()));
    }

    private ChartPackage importPackage(byte[] zip, PackageLimits limits) throws Exception {
        return new PackageImporter(limits).importPackage(
                new ByteArrayInputStream(zip), nextWorkspace(), "fixture.zip");
    }

    private byte[] export(ChartPackage chartPackage) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PackageExporter().exportPackage(chartPackage, chartPackage.getChart(), output);
        return output.toByteArray();
    }

    private PackageException assertPackageError(PackageException.Code code, byte[] zip,
                                                PackageLimits limits, File workspace) throws Exception {
        try {
            new PackageImporter(limits).importPackage(
                    new ByteArrayInputStream(zip), workspace, "invalid.zip");
            fail("Expected package error " + code);
            return null;
        } catch (PackageException exception) {
            assertEquals(code, exception.getCode());
            return exception;
        }
    }

    private File nextWorkspace() {
        return new File(temporaryRoot, "workspace-" + (++workspaceCounter));
    }

    private static Map<String, byte[]> normalEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: \"Unit package\"\n"
                + "chart: \"chart.data\"\n"
                + "music: \"music/song.ogg\"\n"
                + "illustration: \"art/cover.png\"\n"
                + "futureManifestField: keep\n"));
        entries.put("info.txt", bytes("# legacy manifest\nName: Unit package\nChart: chart.data\n"
                + "Song: music/song.ogg\nPicture: art/cover.png\nUnknown: keep this line\n"));
        entries.put("chart.data", rpeJson());
        entries.put("music/song.ogg", new byte[]{1, 3, 5, 7});
        entries.put("art/cover.png", new byte[]{2, 4, 6, 8});
        entries.put("extras/unknown.bin", new byte[]{9, 8, 7});
        return entries;
    }

    private static byte[] rpeJson() {
        return bytes("{"
                + "\"META\":{\"name\":\"Unit chart\",\"composer\":\"Composer\","
                + "\"song\":\"music/song.ogg\",\"background\":\"art/cover.png\","
                + "\"futureMeta\":\"keep-meta\"},"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"judgeLineList\":[],\"futureRoot\":\"keep-root\"}");
    }

    private static String officialV3Json() {
        return "{\"formatVersion\":3,\"offset\":0.125,\"judgeLineList\":[{"
                + "\"bpm\":120,"
                + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32,"
                + "\"start\":0.5,\"end\":1,\"start2\":0.5,\"end2\":0.25}],"
                + "\"judgeLineRotateEvents\":[{\"startTime\":0,\"endTime\":32,"
                + "\"start\":10,\"end\":20}],"
                + "\"judgeLineDisappearEvents\":[{\"startTime\":0,\"endTime\":32,"
                + "\"start\":0.5,\"end\":1}],"
                + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32,\"value\":2}],"
                + "\"notesAbove\":[{\"type\":1,\"time\":32,\"holdTime\":0,"
                + "\"positionX\":2,\"speed\":1.5,\"floorPosition\":0}],"
                + "\"notesBelow\":[{\"type\":3,\"time\":64,\"holdTime\":32,"
                + "\"positionX\":-2,\"speed\":1,\"floorPosition\":0}]}]}";
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] source) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(source), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                result.put(entry.getName(), output.toByteArray());
                zip.closeEntry();
            }
        }
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
