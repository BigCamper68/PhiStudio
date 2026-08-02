package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class InfoTxtExportTest {
    private File workspace;

    @Before
    public void setUp() throws Exception {
        workspace = Files.createTempDirectory("phistudio-info-export-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(workspace);
    }

    @Test
    public void exportsInfoTxtWhenSourcePackageDidNotContainOne() throws Exception {
        Files.write(new File(workspace, "song.ogg").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(workspace, "cover.png").toPath(), new byte[]{4, 5, 6});

        ChartDocument chart = new ChartDocument();
        chart.name = "Tornado";
        chart.level = "AT Lv.16";
        chart.composer = "Camellia";
        chart.charter = "BigCamper68";
        chart.song = "song.ogg";
        chart.background = "cover.png";

        List<ChartPackage.Entry> entries = new ArrayList<>();
        entries.add(new ChartPackage.Entry("chart.json", false, 0L));
        entries.add(new ChartPackage.Entry("song.ogg", false, 3L));
        entries.add(new ChartPackage.Entry("cover.png", false, 3L));
        ChartPackage chartPackage = new ChartPackage(
                workspace, "Tornado.zip", "Tornado", "chart.json",
                "song.ogg", "cover.png", 0L, false, chart,
                entries, Collections.emptyList());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PackageExporter().exportPackage(chartPackage, chart, output);
        Map<String, byte[]> exported = unzip(output.toByteArray());

        assertTrue(exported.containsKey("info.txt"));
        String manifest = new String(exported.get("info.txt"), StandardCharsets.UTF_8);
        assertTrue(manifest.startsWith("#\n"));
        assertTrue(manifest.contains("Name: Tornado\n"));
        assertTrue(manifest.contains("Path: chart\n"));
        assertTrue(manifest.contains("Song: song.ogg\n"));
        assertTrue(manifest.contains("Picture: cover.png\n"));
        assertTrue(manifest.contains("Chart: chart.json\n"));
        assertTrue(manifest.contains("Level: AT Lv.16\n"));
        assertTrue(manifest.contains("Composer: Camellia\n"));
        assertTrue(manifest.contains("Charter: BigCamper68\n"));
    }

    @Test
    public void replacesExistingInfoTxtAndPreservesTimingFields() throws Exception {
        String oldManifest = "#\nName: Old\nPath: legacy-id\nChart: old.json\n"
                + "LastEditTime: 2026_8_3_1_2_3_\nLength: 271.440\n"
                + "EditTime: 42.500\nGroup: Custom\n";
        Files.write(new File(workspace, "info.txt").toPath(),
                oldManifest.getBytes(StandardCharsets.UTF_8));

        ChartDocument chart = new ChartDocument();
        chart.name = "New name";
        ChartPackage chartPackage = new ChartPackage(
                workspace, "chart.zip", "chart", "chart.json",
                null, null, 0L, false, chart,
                java.util.Arrays.asList(
                        new ChartPackage.Entry("chart.json", false, 0L),
                        new ChartPackage.Entry("info.txt", false,
                                oldManifest.getBytes(StandardCharsets.UTF_8).length)),
                Collections.singletonList(PackageManifest.parse(
                        "info.txt", PackageManifest.Kind.TEXT, oldManifest)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PackageExporter().exportPackage(chartPackage, chart, output);
        Map<String, byte[]> exported = unzip(output.toByteArray());
        String manifest = new String(exported.get("info.txt"), StandardCharsets.UTF_8);

        assertEquals(1, exported.keySet().stream()
                .filter(name -> name.equalsIgnoreCase("info.txt")).count());
        assertTrue(manifest.contains("Name: New name\n"));
        assertTrue(manifest.contains("Path: legacy-id\n"));
        assertTrue(manifest.contains("LastEditTime: 2026_8_3_1_2_3_\n"));
        assertTrue(manifest.contains("Length: 271.440\n"));
        assertTrue(manifest.contains("EditTime: 42.500\n"));
        assertTrue(manifest.contains("Group: Custom\n"));
    }

    private static Map<String, byte[]> unzip(byte[] data) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream value = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = zip.read(buffer)) != -1) value.write(buffer, 0, count);
                result.put(entry.getName(), value.toByteArray());
                zip.closeEntry();
            }
        }
        return result;
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
