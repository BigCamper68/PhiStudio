package com.xpe.mobile.project;

import com.xpe.mobile.packageio.ChartPackage;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.packageio.PackageImporter;
import com.xpe.mobile.packageio.PackageWorkspaceWriter;

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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProjectLibraryTest {
    private File temporaryRoot;
    private File libraryRoot;
    private ProjectLibrary library;

    @Before
    public void setUp() throws Exception {
        temporaryRoot = Files.createTempDirectory("xpe-project-library-").toFile();
        libraryRoot = new File(temporaryRoot, "library");
        library = new ProjectLibrary(libraryRoot);
    }

    @After
    public void tearDown() {
        deleteRecursively(temporaryRoot);
    }

    @Test
    public void persistsImportedProjectAndCurrentSelection() throws Exception {
        ChartPackage chartPackage = importProject("project-one", normalEntries("First"));
        library.addImportedProject("project-one", chartPackage, 100L);

        ProjectLibrary.State restored = new ProjectLibrary(libraryRoot).load();

        assertEquals(1, restored.getProjects().size());
        assertEquals("project-one", restored.getCurrentProjectId());
        assertEquals("First", restored.getProjects().get(0).getName());
        assertTrue(restored.getProjects().get(0).hasAudio());
        assertTrue(restored.getProjects().get(0).hasIllustration());
    }

    @Test
    public void reopensPackageWorkspaceAfterLibraryRecreation() throws Exception {
        byte[] unknown = new byte[]{0, 3, 7, (byte) 0xff};
        Map<String, byte[]> entries = normalEntries("Reopen");
        entries.put("extras/future.bin", unknown);
        ChartPackage imported = importProject("reopen", entries);
        library.addImportedProject("reopen", imported, 100L);

        ProjectLibrary.OpenResult opened = new ProjectLibrary(libraryRoot)
                .openProject("reopen", 200L);

        assertEquals("Reopen chart", opened.getChartPackage().getChart().name);
        assertEquals(200L, opened.getRecord().getLastOpenedAtMillis());
        assertArrayEquals(unknown, Files.readAllBytes(new File(
                opened.getChartPackage().getWorkspace(), "extras/future.bin").toPath()));
        assertArrayEquals(new byte[]{1, 2, 3, 4},
                Files.readAllBytes(opened.getChartPackage().getAudioFile().toPath()));
    }

    @Test
    public void autosavesEditedChartAndPreservesUnknownJson() throws Exception {
        ChartPackage imported = importProject("autosave", normalEntries("Autosave"));
        library.addImportedProject("autosave", imported, 100L);
        imported.getChart().name = "Edited after import";

        PackageWorkspaceWriter.writeChart(imported, imported.getChart());
        ProjectLibrary.OpenResult reopened = library.openProject("autosave", 300L);
        JSONObject exported = new JSONObject(reopened.getChartPackage().getChart().toJsonString());

        assertEquals("Edited after import", reopened.getChartPackage().getChart().name);
        assertEquals("keep-root", exported.getString("futureRoot"));
        assertFalse(hasTemporaryFile(new File(libraryRoot, "projects")));
    }

    @Test
    public void sortsProjectsByMostRecentlyOpened() throws Exception {
        ChartPackage first = importProject("first", normalEntries("First"));
        library.addImportedProject("first", first, 100L);
        ChartPackage second = importProject("second", normalEntries("Second"));
        library.addImportedProject("second", second, 200L);

        library.openProject("first", 300L);
        ProjectLibrary.State state = library.load();

        assertEquals("first", state.getProjects().get(0).getId());
        assertEquals("second", state.getProjects().get(1).getId());
        assertEquals("first", state.getCurrentProjectId());
    }

    @Test
    public void removesOnlyPrivateWorkspaceAndNeverExternalSource() throws Exception {
        File externalSource = new File(temporaryRoot, "original-user-package.zip");
        Files.write(externalSource.toPath(), new byte[]{9, 9, 9});
        ChartPackage imported = importProject("remove", normalEntries("Remove"),
                externalSource.getAbsolutePath());
        library.addImportedProject("remove", imported, 100L);
        File workspace = imported.getWorkspace();

        ProjectLibrary.State state = library.removeProject("remove");

        assertTrue(externalSource.isFile());
        assertArrayEquals(new byte[]{9, 9, 9}, Files.readAllBytes(externalSource.toPath()));
        assertFalse(workspace.exists());
        assertTrue(state.getProjects().isEmpty());
        assertNull(state.getCurrentProjectId());
    }

    @Test
    public void clearingCurrentProjectKeepsLibraryEntries() throws Exception {
        ChartPackage imported = importProject("raw-mode", normalEntries("Raw mode"));
        library.addImportedProject("raw-mode", imported, 100L);

        ProjectLibrary.State state = library.clearCurrentProject();

        assertNull(state.getCurrentProjectId());
        assertEquals(1, state.getProjects().size());
        assertTrue(imported.getWorkspace().isDirectory());
    }

    @Test
    public void malformedIndexFailsWithoutDeletingWorkspaces() throws Exception {
        ChartPackage imported = importProject("safe", normalEntries("Safe"));
        library.addImportedProject("safe", imported, 100L);
        File index = new File(libraryRoot, "index.json");
        Files.write(index.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));

        assertLibraryFailure(() -> library.load(), "malformed");

        assertTrue(imported.getWorkspace().isDirectory());
        assertEquals("{broken", new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void unsupportedFutureIndexVersionIsNotOverwritten() throws Exception {
        assertTrue(library.load().getProjects().isEmpty());
        File index = new File(libraryRoot, "index.json");
        String future = "{\"formatVersion\":99,\"currentProjectId\":null,\"projects\":[]}";
        Files.write(index.toPath(), future.getBytes(StandardCharsets.UTF_8));

        assertLibraryFailure(() -> library.load(), "formatVersion: 99");

        assertEquals(future, new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsTraversalProjectId() throws Exception {
        assertLibraryFailure(() -> library.workspaceForNewProject("../escape"), "Invalid project ID");
        assertFalse(new File(temporaryRoot, "escape").exists());
    }

    @Test
    public void missingWorkspaceDoesNotRemoveIndexRecord() throws Exception {
        ChartPackage imported = importProject("missing", normalEntries("Missing"));
        library.addImportedProject("missing", imported, 100L);
        deleteRecursively(imported.getWorkspace());

        assertLibraryFailure(() -> library.openProject("missing", 200L), "workspace is missing");

        assertNotNull(library.load().find("missing"));
    }

    @Test
    public void duplicateProjectIdIsRejected() throws Exception {
        ChartPackage imported = importProject("duplicate", normalEntries("Duplicate"));
        library.addImportedProject("duplicate", imported, 100L);

        assertLibraryFailure(() -> library.addImportedProject("duplicate", imported, 200L),
                "already exists");
        assertEquals(1, library.load().getProjects().size());
    }

    @Test
    public void indexWritesLeaveNoTemporaryFile() throws Exception {
        ChartPackage imported = importProject("atomic", normalEntries("Atomic"));
        library.addImportedProject("atomic", imported, 100L);
        library.openProject("atomic", 200L);
        library.clearCurrentProject();

        assertFalse(new File(libraryRoot, "index.json.tmp").exists());
        assertTrue(new File(libraryRoot, "index.json").isFile());
    }

    @Test
    public void renameChangesOnlyLocalIndexName() throws Exception {
        ChartPackage imported = importProject("rename", normalEntries("Original"));
        library.addImportedProject("rename", imported, 100L);
        byte[] manifestBefore = Files.readAllBytes(new File(imported.getWorkspace(), "info.yml").toPath());
        byte[] chartBefore = Files.readAllBytes(new File(imported.getWorkspace(), "chart.rpe").toPath());

        ProjectLibrary.State renamed = library.renameProject("rename", "Local alias");
        ProjectLibrary.State restored = new ProjectLibrary(libraryRoot).load();

        assertEquals("Local alias", renamed.find("rename").getName());
        assertEquals("Local alias", restored.find("rename").getName());
        assertArrayEquals(manifestBefore,
                Files.readAllBytes(new File(imported.getWorkspace(), "info.yml").toPath()));
        assertArrayEquals(chartBefore,
                Files.readAllBytes(new File(imported.getWorkspace(), "chart.rpe").toPath()));
    }

    @Test
    public void renameRejectsEmptyAndControlCharacterNames() throws Exception {
        ChartPackage imported = importProject("bad-name", normalEntries("Original"));
        library.addImportedProject("bad-name", imported, 100L);

        assertLibraryFailure(() -> library.renameProject("bad-name", "   "), "cannot be empty");
        assertLibraryFailure(() -> library.renameProject("bad-name", "bad\nname"), "control characters");

        assertEquals("Original", library.load().find("bad-name").getName());
    }

    @Test
    public void duplicatePreservesResourcesAndBecomesIndependent() throws Exception {
        Map<String, byte[]> entries = normalEntries("Original");
        byte[] unknown = new byte[]{12, 34, 56, (byte) 0xfe};
        entries.put("extras/unknown.bin", unknown);
        ChartPackage original = importProject("original", entries);
        library.addImportedProject("original", original, 100L);

        ProjectLibrary.OpenResult duplicate = library.duplicateProject("original", "copy", 200L);

        assertEquals("Original — Copy", duplicate.getRecord().getName());
        assertArrayEquals(unknown, Files.readAllBytes(new File(
                duplicate.getChartPackage().getWorkspace(), "extras/unknown.bin").toPath()));
        assertArrayEquals(Files.readAllBytes(original.getAudioFile().toPath()),
                Files.readAllBytes(duplicate.getChartPackage().getAudioFile().toPath()));
        duplicate.getChartPackage().getChart().name = "Edited copy";
        PackageWorkspaceWriter.writeChart(
                duplicate.getChartPackage(), duplicate.getChartPackage().getChart());

        ProjectLibrary.OpenResult reopenedOriginal = library.openProject("original", 300L);
        ProjectLibrary.OpenResult reopenedCopy = library.openProject("copy", 400L);
        assertEquals("Original chart", reopenedOriginal.getChartPackage().getChart().name);
        assertEquals("Edited copy", reopenedCopy.getChartPackage().getChart().name);
    }

    @Test
    public void duplicateNamesUseDeterministicSuffixes() throws Exception {
        ChartPackage original = importProject("name-source", normalEntries("Song"));
        library.addImportedProject("name-source", original, 100L);

        ProjectLibrary.OpenResult first = library.duplicateProject("name-source", "copy-one", 200L);
        ProjectLibrary.OpenResult second = library.duplicateProject("name-source", "copy-two", 300L);

        assertEquals("Song — Copy", first.getRecord().getName());
        assertEquals("Song — Copy 2", second.getRecord().getName());
    }

    @Test
    public void discoveryClassifiesRecoverableMissingDamagedAndUnsupported() throws Exception {
        ChartPackage missing = importProject("missing-indexed", normalEntries("Missing"));
        library.addImportedProject("missing-indexed", missing, 100L);
        deleteRecursively(missing.getWorkspace());

        importProject("recoverable", normalEntries("Recoverable"));
        File damaged = library.workspaceForNewProject("damaged");
        assertTrue(damaged.mkdirs());
        Files.write(new File(damaged, "junk.bin").toPath(), new byte[]{1, 2});
        createUnsupportedWorkspace("unsupported");

        ProjectLibrary.Discovery discovery = library.discoverWorkspaces();

        assertIssue(discovery.getIssues(), "missing-indexed", ProjectLibrary.WorkspaceStatus.MISSING);
        assertIssue(discovery.getIssues(), "recoverable", ProjectLibrary.WorkspaceStatus.RECOVERABLE);
        assertIssue(discovery.getIssues(), "damaged", ProjectLibrary.WorkspaceStatus.DAMAGED);
        assertIssue(discovery.getIssues(), "unsupported", ProjectLibrary.WorkspaceStatus.UNSUPPORTED);
        assertTrue(damaged.isDirectory());
    }

    @Test
    public void recoversValidOrphanWithoutChangingWorkspaceFiles() throws Exception {
        ChartPackage orphan = importProject("orphan", normalEntries("Orphan"));
        byte[] before = Files.readAllBytes(new File(orphan.getWorkspace(), "info.yml").toPath());

        ProjectLibrary.OpenResult recovered = library.recoverOrphan("orphan", 500L);

        assertEquals("Orphan", recovered.getRecord().getName());
        assertEquals("orphan", recovered.getState().getCurrentProjectId());
        assertArrayEquals(before,
                Files.readAllBytes(new File(orphan.getWorkspace(), "info.yml").toPath()));
        assertNotNull(new ProjectLibrary(libraryRoot).load().find("orphan"));
    }

    @Test
    public void removesUnindexedWorkspaceOnlyAfterExplicitCall() throws Exception {
        ChartPackage orphan = importProject("discard", normalEntries("Discard"));
        assertIssue(library.discoverWorkspaces().getIssues(), "discard",
                ProjectLibrary.WorkspaceStatus.RECOVERABLE);

        library.removeUnindexedWorkspace("discard");

        assertFalse(orphan.getWorkspace().exists());
        assertTrue(library.load().getProjects().isEmpty());
    }

    @Test
    public void discoveryReportsDamagedIndexedWorkspaceWithoutDeletingIt() throws Exception {
        ChartPackage imported = importProject("indexed-damaged", normalEntries("Damaged"));
        library.addImportedProject("indexed-damaged", imported, 100L);
        Files.write(new File(imported.getWorkspace(), "chart.rpe").toPath(),
                "not json".getBytes(StandardCharsets.UTF_8));

        ProjectLibrary.Discovery discovery = library.discoverWorkspaces();
        ProjectLibrary.WorkspaceIssue issue = findIssue(
                discovery.getIssues(), "indexed-damaged");

        assertEquals(ProjectLibrary.WorkspaceStatus.DAMAGED, issue.getStatus());
        assertTrue(issue.isIndexed());
        assertTrue(imported.getWorkspace().isDirectory());
        assertNotNull(library.load().find("indexed-damaged"));
    }

    @Test
    public void discoveryHandlesMultipleIndexedChartsWithoutOpeningEditorModels() throws Exception {
        addImportedProject("large-one", largeEntries("Large one", 300), 100L);
        addImportedProject("large-two", largeEntries("Large two", 300), 200L);

        ProjectLibrary.Discovery discovery = library.discoverWorkspaces();

        assertEquals(2, discovery.getState().getProjects().size());
        assertTrue(discovery.getIssues().isEmpty());
    }

    @Test
    public void duplicateFailureLeavesOriginalAndIndexUnchanged() throws Exception {
        ChartPackage original = importProject("dup-source", normalEntries("Duplicate failure"));
        library.addImportedProject("dup-source", original, 100L);
        File blockingTarget = library.getProjectWorkspace("blocked-copy");
        assertTrue(blockingTarget.mkdirs());

        assertLibraryFailure(() -> library.duplicateProject(
                "dup-source", "blocked-copy", 200L), "already exists");

        assertTrue(original.getWorkspace().isDirectory());
        assertNull(library.load().find("blocked-copy"));
        assertFalse(new File(new File(libraryRoot, "projects"),
                ".duplicate-blocked-copy.tmp").exists());
    }

    private ChartPackage importProject(String id, Map<String, byte[]> entries) throws Exception {
        return importProject(id, entries, id + ".zip");
    }

    private void addImportedProject(String id, Map<String, byte[]> entries, long nowMillis)
            throws Exception {
        ChartPackage chartPackage = importProject(id, entries);
        library.addImportedProject(id, chartPackage, nowMillis);
    }

    private ChartPackage importProject(String id, Map<String, byte[]> entries,
                                       String sourceDisplayName) throws Exception {
        File workspace = library.workspaceForNewProject(id);
        return new PackageImporter().importPackage(
                new ByteArrayInputStream(zip(entries)), workspace, sourceDisplayName);
    }

    private void createUnsupportedWorkspace(String id) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: Official\nchart: official.json\n"));
        entries.put("official.json", bytes(
                "{\"formatVersion\":99,\"offset\":0,\"judgeLineList\":[]}"));
        File workspace = library.workspaceForNewProject(id);
        try {
            new PackageImporter().importPackage(
                    new ByteArrayInputStream(zip(entries)), workspace, id + ".zip");
            fail("Expected unsupported chart format");
        } catch (PackageException exception) {
            assertEquals(PackageException.Code.UNSUPPORTED_CHART_FORMAT, exception.getCode());
            assertNotNull(exception.getRetainedWorkspace());
        }
    }

    private static Map<String, byte[]> normalEntries(String name) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("info.yml", bytes("name: \"" + name + "\"\n"
                + "chart: chart.rpe\nmusic: song.ogg\nillustration: cover.png\n"));
        entries.put("chart.rpe", bytes("{"
                + "\"META\":{\"name\":\"" + name + " chart\",\"futureMeta\":true},"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"judgeLineList\":[],\"futureRoot\":\"keep-root\"}"));
        entries.put("song.ogg", new byte[]{1, 2, 3, 4});
        entries.put("cover.png", new byte[]{5, 6, 7, 8});
        return entries;
    }

    private static Map<String, byte[]> largeEntries(String name, int lineCount) {
        StringBuilder lines = new StringBuilder();
        for (int index = 0; index < lineCount; index++) {
            if (index > 0) lines.append(',');
            lines.append("{\"Name\":\"Line ").append(index)
                    .append("\",\"notes\":[],\"eventLayers\":[],\"futureData\":\"");
            for (int padding = 0; padding < 1024; padding++) lines.append('x');
            lines.append("\"}");
        }
        Map<String, byte[]> entries = normalEntries(name);
        entries.put("chart.rpe", bytes("{"
                + "\"META\":{\"name\":\"" + name + " chart\"},"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"judgeLineList\":[" + lines + "]}"));
        return entries;
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

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasTemporaryFile(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.getName().startsWith(".xpe-chart-") && file.getName().endsWith(".tmp")) {
                return true;
            }
        }
        return false;
    }

    private static void assertLibraryFailure(ThrowingAction action, String messagePart) throws Exception {
        try {
            action.run();
            fail("Expected library failure containing: " + messagePart);
        } catch (IOException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains(messagePart));
        }
    }

    private static void assertIssue(List<ProjectLibrary.WorkspaceIssue> issues, String id,
                                    ProjectLibrary.WorkspaceStatus status) {
        assertEquals(status, findIssue(issues, id).getStatus());
    }

    private static ProjectLibrary.WorkspaceIssue findIssue(
            List<ProjectLibrary.WorkspaceIssue> issues, String id) {
        for (ProjectLibrary.WorkspaceIssue issue : issues) {
            if (issue.getProjectId().equals(id)) return issue;
        }
        fail("Missing workspace issue for " + id);
        return null;
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
