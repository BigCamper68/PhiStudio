package com.xpe.mobile.packageio;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.xpe.mobile.preview.PreviewShaderLoader;
import com.xpe.mobile.preview.ShaderTimeline;

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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PackageImportValidationTest extends PackageIoTestSupport {
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

    @Test public void shaderPathsCannotReadOutsideTheProjectWorkspace() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.put("extra.json", bytes("{\"bpm\":120,\"effects\":[{\"start\":[0,0,1],\"end\":[4,0,1],"
                + "\"shader\":\"/../outside.glsl\"}]}"));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        try { PreviewShaderLoader.load(pack, null); fail("Escaping path must not be loaded"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("effect 0")); }
    }
}
