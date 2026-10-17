package com.xpe.mobile.packageio;

import com.xpe.mobile.preview.ChartEvaluator;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class ReferencePackageConversionTest {
    private File workspaceRoot;

    @Before
    public void setUp() throws Exception {
        workspaceRoot = Files.createTempDirectory("phistudio-package-conversion-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(workspaceRoot);
    }

    @Test
    public void suppliedOfficialAndPecPackagesConvertToRpe() throws Exception {
        String referenceRoot = System.getenv("PHISTUDIO_REFERENCE_PACKAGE_DIR");
        Assume.assumeTrue("Set PHISTUDIO_REFERENCE_PACKAGE_DIR to run package conversion checks",
                referenceRoot != null && !referenceRoot.trim().isEmpty());

        verify(Paths.get(referenceRoot, "Astaroth.zip"), "Astaroth", "astaroth");
        verify(Paths.get(referenceRoot, "OblivionPHIM.zip"), "Oblivion:PHIM", "oblivion");
        verify(Paths.get(referenceRoot, "Retribution ~ Cycle of Redemption ~.zip"),
                "Retribution ~ Cycle of Redemption ~", "retribution");
    }

    @Test
    public void suppliedLargeGalaxyChartSavesReloadsAndEvaluatesRepeatedly() throws Exception {
        String referenceRoot = System.getenv("PHISTUDIO_REFERENCE_PACKAGE_DIR");
        Assume.assumeTrue("Set PHISTUDIO_REFERENCE_PACKAGE_DIR to run large chart checks",
                referenceRoot != null && !referenceRoot.trim().isEmpty());
        Path archive = Paths.get(referenceRoot, "Galaxy Collapse.zip");
        Assume.assumeTrue("Missing supplied package: " + archive, Files.isRegularFile(archive));

        File workspace = new File(workspaceRoot, "galaxy-collapse");
        ChartPackage imported;
        try (FileInputStream input = new FileInputStream(archive.toFile())) {
            imported = new PackageImporter().importPackage(
                    input, workspace, archive.getFileName().toString());
        }
        int notes = imported.getChart().totalNotes();
        int events = imported.getChart().totalEvents();
        assertTrue(notes > 2000);
        assertTrue(events > 50_000);

        PackageWorkspaceWriter.writeChart(imported, imported.getChart());
        ChartPackage reopened = new PackageWorkspaceLoader().load(
                workspace, archive.getFileName().toString());
        assertEquals(notes, reopened.getChart().totalNotes());
        assertEquals(events, reopened.getChart().totalEvents());

        for (int frame = 0; frame < 360; frame++) {
            assertNotNull(ChartEvaluator.evaluate(reopened.getChart(),
                    frame * 0.5, true, -1L, reopened.isUseRpe170Speed()));
        }
    }

    private void verify(Path archive, String expectedName, String workspaceName) throws Exception {
        Assume.assumeTrue("Missing supplied package: " + archive, Files.isRegularFile(archive));
        File workspace = new File(workspaceRoot, workspaceName);
        ChartPackage converted;
        try (FileInputStream input = new FileInputStream(archive.toFile())) {
            converted = new PackageImporter().importPackage(
                    input, workspace, archive.getFileName().toString());
        }

        assertEquals(expectedName, converted.getChart().name);
        assertTrue(converted.getChart().judgeLines.size() > 0);
        assertTrue(converted.getChart().totalNotes() > 0);
        assertTrue(converted.getChart().totalEvents() > 0);
        assertNotNull(converted.getAudioPath());
        assertNotNull(converted.getIllustrationPath());
        String chartJson = new String(Files.readAllBytes(
                new File(workspace, converted.getChartPath()).toPath()), StandardCharsets.UTF_8);
        assertEquals(ChartJsonFormat.RPE,
                ChartJsonFormatDetector.detect(new JSONObject(chartJson)));
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
