package com.xpe.mobile.packageio;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProjectWorkspaceCreatorTest {
    private File root;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("xpe-new-project-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(root);
    }

    @Test
    public void createsLoadableChartWithSelectedAssets() throws Exception {
        File workspace = new File(root, "created");
        byte[] audio = new byte[]{1, 2, 3, 4};
        byte[] illustration = new byte[]{5, 6, 7};
        ProjectWorkspaceCreator.Spec spec = new ProjectWorkspaceCreator.Spec(
                "created", "Fresh chart", "Composer", "Charter", "IN 14",
                160.0, "ogg", "png");

        ChartPackage created = new ProjectWorkspaceCreator().create(
                workspace, spec, new ByteArrayInputStream(audio),
                new ByteArrayInputStream(illustration));

        assertEquals("Fresh chart", created.getProjectName());
        assertEquals("audio.ogg", created.getAudioPath());
        assertEquals("illustration.png", created.getIllustrationPath());
        assertArrayEquals(audio, Files.readAllBytes(created.getAudioFile().toPath()));
        assertArrayEquals(illustration,
                Files.readAllBytes(created.getIllustrationFile().toPath()));
        assertEquals("Fresh chart", created.getChart().name);
        assertEquals("Composer", created.getChart().composer);
        assertEquals("Charter", created.getChart().charter);
        assertEquals("IN 14", created.getChart().level);
        assertEquals("created", created.getChart().id);
        assertEquals(160.0, created.getChart().bpmChanges.get(0).bpm, 0.0);
        assertEquals(1, created.getChart().judgeLines.size());
        assertEquals("Line 0", created.getChart().judgeLines.get(0).name);
        String manifest = new String(Files.readAllBytes(
                new File(workspace, "info.yml").toPath()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("chart: chart.json"));
        assertTrue(manifest.contains("music: audio.ogg"));
        assertTrue(manifest.contains("illustration: illustration.png"));
    }

    @Test
    public void rejectsProjectSpecWithoutRequiredAssets() {
        try {
            new ProjectWorkspaceCreator.Spec(
                    "chart-only", "Chart only", "", "", "", 120.0, null, null);
            fail("Expected required asset validation");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("required"));
        }
    }

    @Test
    public void rejectsMissingAssetStreamsBeforeCreatingWorkspace() throws Exception {
        File workspace = new File(root, "missing-stream");
        ProjectWorkspaceCreator.Spec spec = new ProjectWorkspaceCreator.Spec(
                "missing-stream", "Missing stream", "", "", "", 120.0, "ogg", "png");

        try {
            new ProjectWorkspaceCreator().create(
                    workspace, spec, new ByteArrayInputStream(new byte[]{1}), null);
            fail("Expected required asset validation");
        } catch (java.io.IOException exception) {
            assertTrue(exception.getMessage().contains("required"));
        }
        assertFalse(workspace.exists());
    }

    @Test
    public void removesIncompleteWorkspaceWhenLimitsAreExceeded() throws Exception {
        File workspace = new File(root, "too-small");
        ProjectWorkspaceCreator.Spec spec = new ProjectWorkspaceCreator.Spec(
                "too-small", "Too small", "", "", "", 120.0, "ogg", "png");
        ProjectWorkspaceCreator creator = new ProjectWorkspaceCreator(
                new PackageLimits(10, 16, 64));

        try {
            creator.create(workspace, spec,
                    new ByteArrayInputStream(new byte[]{1}),
                    new ByteArrayInputStream(new byte[]{2}));
            fail("Expected size limit failure");
        } catch (PackageException exception) {
            assertEquals(PackageException.Code.ENTRY_SIZE_LIMIT, exception.getCode());
        }

        assertFalse(workspace.exists());
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
