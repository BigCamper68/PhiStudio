package com.xpe.mobile.packageio;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PackageWorkspaceLoadingTest extends PackageIoTestSupport {
    @Test public void uniqueManifestLoadsSelectedChartAndPreservesOtherJsonResources() throws Exception {
        File workspace = workspace();
        write(workspace, "info.yml", "chart: charts/selected.data\n");
        write(workspace, "charts/selected.data", "\ufeff" + rpe("Selected"));
        write(workspace, "charts/other.json", rpe("Other"));
        write(workspace, "extra.json", "{this is a shader definition, not a chart");
        ChartPackage result = load(workspace);
        assertEquals("charts/selected.data", result.getChartPath());
        assertEquals("Selected", result.getChart().name);
        assertTrue(new File(workspace, "extra.json").isFile());
    }

    @Test public void unknownOrMissingManifestChartFallsBackToSupportedCandidate() throws Exception {
        File workspace = workspace();
        write(workspace, "chart.data", rpe("Fallback"));
        write(workspace, "unknown.json", "{\"shader\":true}");
        for (String hint : new String[]{"missing.json", "unknown.json"}) {
            write(workspace, "info.yml", "chart: " + hint + "\n");
            assertEquals("chart.data", load(workspace).getChartPath());
        }
    }

    @Test public void conflictingManifestSelectionsRemainAmbiguous() throws Exception {
        File workspace = workspace();
        write(workspace, "first.data", rpe("First"));
        write(workspace, "second.data", rpe("Second"));
        write(workspace, "info.yml", "chart: first.data\n");
        write(workspace, "info.txt", "Chart: second.data\n");
        assertLoadError(workspace, PackageLimits.DEFAULT, PackageException.Code.AMBIGUOUS_CHART);
    }

    @Test public void unsupportedExplicitOfficialChartDoesNotSilentlySelectAnotherChart() throws Exception {
        File workspace = workspace();
        write(workspace, "info.yml", "chart: official.data\n");
        write(workspace, "official.data", "{\"formatVersion\":99,\"judgeLineList\":[]}");
        write(workspace, "other.data", rpe("Other"));
        assertLoadError(workspace, PackageLimits.DEFAULT, PackageException.Code.UNSUPPORTED_CHART_FORMAT);
    }

    @Test public void noManifestDiscoversExtensionlessChartAmongBinaryAndJsonResources() throws Exception {
        File workspace = workspace();
        write(workspace, "chart", "\ufeff  " + rpe("Found"));
        write(workspace, "extra.json", "{\"shader\":true}");
        Files.write(new File(workspace, "audio.bin").toPath(), new byte[80 * 1024]);
        assertEquals("Found", load(workspace).getChart().name);
    }

    @Test public void reopeningReadsEditedChartAndChangedManifestWithoutStaleModels() throws Exception {
        File workspace = workspace();
        write(workspace, "info.yml", "chart: first.data\n");
        write(workspace, "first.data", rpe("First"));
        write(workspace, "second.data", rpe("Second"));
        ChartPackage first = load(workspace);
        write(workspace, "first.data", rpe("Edited"));
        assertEquals("Edited", load(workspace).getChart().name);
        write(workspace, "info.yml", "chart: second.data\n");
        assertEquals("Second", load(workspace).getChart().name);
        assertEquals("First", first.getChart().name);
    }

    @Test public void indexedHeaderCheckDefersUnrelatedResourceValidationUntilOpen() throws Exception {
        File workspace = workspace();
        write(workspace, "chart.data", rpe("Valid"));
        File target = new File(temporaryRoot, "outside.bin");
        Files.write(target.toPath(), new byte[]{1});
        File link = new File(workspace, "resource.bin");
        Files.createSymbolicLink(link.toPath(), target.toPath());
        try {
            new PackageWorkspaceLoader().validateIndexedWorkspace(workspace, "chart.data");
            assertIoFailure(() -> load(workspace));
        } finally { Files.delete(link.toPath()); }
    }

    @Test public void indexedHeaderCheckRejectsUnsafeMissingNonJsonAndOversizedCharts() throws Exception {
        File workspace = workspace();
        write(workspace, "chart.data", rpe("Valid"));
        write(workspace, "not-json.data", "not json");
        PackageWorkspaceLoader loader = new PackageWorkspaceLoader();
        for (String path : new String[]{"../chart.data", "missing.data", "not-json.data"}) {
            assertIoFailure(() -> loader.validateIndexedWorkspace(workspace, path));
        }
        assertIoFailure(() -> new PackageWorkspaceLoader(new PackageLimits(10, 8, 100))
                .validateIndexedWorkspace(workspace, "chart.data"));
    }

    @Test public void indexedChartCannotBeReachedThroughFileOrDirectorySymlinks() throws Exception {
        File workspace = workspace();
        write(workspace, "real/chart.data", rpe("Valid"));
        File directoryLink = new File(workspace, "alias");
        File fileLink = new File(workspace, "chart-link.data");
        Files.createSymbolicLink(directoryLink.toPath(), new File(workspace, "real").toPath());
        Files.createSymbolicLink(fileLink.toPath(), new File(workspace, "real/chart.data").toPath());
        try {
            PackageWorkspaceLoader loader = new PackageWorkspaceLoader();
            loader.validateIndexedWorkspace(workspace, "real/chart.data");
            assertIoFailure(() -> loader.validateIndexedWorkspace(workspace, "alias/chart.data"));
            assertIoFailure(() -> loader.validateIndexedWorkspace(workspace, "chart-link.data"));
        } finally {
            Files.delete(directoryLink.toPath()); Files.delete(fileLink.toPath());
        }
    }

    @Test public void completeInventoryKeepsSortedDepthFirstEntriesAndExactEntryLimit() throws Exception {
        File workspace = workspace();
        write(workspace, "a/inner.bin", "1");
        write(workspace, "a-b.bin", "2");
        write(workspace, "chart.data", rpe("Valid"));
        write(workspace, "info.txt", "Chart: chart.data\n");
        ChartPackage result = new PackageWorkspaceLoader(new PackageLimits(5, 4096, 4096))
                .load(workspace, "fixture.zip");
        List<String> paths = new ArrayList<>();
        for (ChartPackage.Entry entry : result.getEntries()) paths.add(entry.getPath());
        assertEquals(Arrays.asList("a", "a/inner.bin", "a-b.bin", "chart.data", "info.txt"), paths);
        assertLoadError(workspace, new PackageLimits(4, 4096, 4096), PackageException.Code.ENTRY_COUNT_LIMIT);
    }

    @Test public void completeInventoryEnforcesIndividualAndTotalResourceSizes() throws Exception {
        File workspace = workspace();
        write(workspace, "chart.data", rpe("Valid"));
        Files.write(new File(workspace, "large.bin").toPath(), new byte[2048]);
        assertLoadError(workspace, new PackageLimits(10, 1024, 8192), PackageException.Code.ENTRY_SIZE_LIMIT);
        assertLoadError(workspace, new PackageLimits(10, 4096, 2048), PackageException.Code.TOTAL_SIZE_LIMIT);
    }

    @Test public void pecSniffReadsCommandsBeyondFirstBufferedBlockAndBoundsWhitespace() throws Exception {
        File workspace = workspace();
        File pec = new File(workspace, "chart.pec");
        write(workspace, "chart.pec", "\ufeff\t-12.5\n" + " ".repeat(12 * 1024)
                + "bp 0 120\nn1 0 1 512 1 0\n");
        assertTrue(PackageChartSelection.looksLikeLegacyPec(pec));
        write(workspace, "chart.pec", " ".repeat(70 * 1024) + "0\nbp 0 120\nn1 0 1 512 1 0\n");
        assertFalse(PackageChartSelection.looksLikeLegacyPec(pec));
        write(workspace, "chart.pec", "\n0\nbp 0 120\nn1 0 1 512 1 0\n");
        assertFalse(PackageChartSelection.looksLikeLegacyPec(pec));
    }

    private File workspace() throws IOException {
        File result = nextWorkspace();
        if (!result.mkdirs()) throw new IOException("Unable to create test workspace");
        return result;
    }

    private static String rpe(String name) {
        return "{\"META\":{\"name\":\"" + name + "\"},\"BPMList\":[],\"judgeLineList\":[]}";
    }

    private static void write(File workspace, String path, String content) throws IOException {
        File file = new File(workspace, path);
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static ChartPackage load(File workspace) throws IOException {
        return new PackageWorkspaceLoader().load(workspace, "fixture.zip");
    }

    private static void assertLoadError(File workspace, PackageLimits limits, PackageException.Code code)
            throws IOException {
        try {
            new PackageWorkspaceLoader(limits).load(workspace, "fixture.zip");
            fail("Expected " + code);
        } catch (PackageException exception) { assertEquals(code, exception.getCode()); }
    }

    private interface IoAction { void run() throws IOException; }

    private static void assertIoFailure(IoAction action) throws IOException {
        try { action.run(); fail("Expected workspace validation failure"); }
        catch (IOException expected) { }
    }
}
