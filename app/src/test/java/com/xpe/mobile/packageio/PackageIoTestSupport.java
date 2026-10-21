package com.xpe.mobile.packageio;

import org.junit.After;
import org.junit.Before;

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

abstract class PackageIoTestSupport {
    protected File temporaryRoot;

    protected int workspaceCounter;

    @Before
    public void setUp() throws Exception {
        temporaryRoot = Files.createTempDirectory("xpe-package-tests-").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursively(temporaryRoot);
    }

    protected ChartPackage importPackage(byte[] zip, PackageLimits limits) throws Exception {
        return new PackageImporter(limits).importPackage(
                new ByteArrayInputStream(zip), nextWorkspace(), "fixture.zip");
    }

    protected byte[] export(ChartPackage chartPackage) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new PackageExporter().exportPackage(chartPackage, chartPackage.getChart(), output);
        return output.toByteArray();
    }

    protected PackageException assertPackageError(PackageException.Code code, byte[] zip,
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

    protected File nextWorkspace() {
        return new File(temporaryRoot, "workspace-" + (++workspaceCounter));
    }

    protected static Map<String, byte[]> normalEntries() {
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

    protected static byte[] rpeJson() {
        return bytes("{"
                + "\"META\":{\"name\":\"Unit chart\",\"composer\":\"Composer\","
                + "\"song\":\"music/song.ogg\",\"background\":\"art/cover.png\","
                + "\"futureMeta\":\"keep-meta\"},"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"judgeLineList\":[],\"futureRoot\":\"keep-root\"}");
    }

    protected static String officialV3Json() {
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

    protected static byte[] zip(Map<String, byte[]> entries) throws IOException {
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

    protected static Map<String, byte[]> unzip(byte[] source) throws IOException {
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

    protected static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    protected static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
