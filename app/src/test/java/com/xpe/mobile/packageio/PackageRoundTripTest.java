package com.xpe.mobile.packageio;

import org.json.JSONObject;
import org.junit.Test;
import com.xpe.mobile.preview.PreviewShaderLoader;
import com.xpe.mobile.preview.ShaderTimeline;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class PackageRoundTripTest extends PackageIoTestSupport {
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

    @Test public void backgroundDimEditUpdatesJsonYamlWorkspaceAndExportWithoutTouchingAssets() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        String manifest = "# keep comment\r\nname: \"Unit package\"\r\n"
                + "chart: chart.data\r\nbackgroundDim: 0.1 # authored\r\n"
                + "futureField: {opaque: true}\r\n";
        entries.put("info.yml", bytes(manifest));
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        assertEquals(.1, pack.getBackgroundDim(), 0);
        pack.getChart().backgroundDim = .85;
        assertEquals(.85, pack.getBackgroundDim(), 0); // live model, no stale cached dim
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        String expected = manifest.replace("0.1 # authored", "0.85 # authored");
        assertEquals(expected, new String(Files.readAllBytes(
                new File(pack.getWorkspace(), "info.yml").toPath()), StandardCharsets.UTF_8));
        ChartPackage reopened = new PackageWorkspaceLoader().load(pack.getWorkspace(), "chart.zip");
        assertEquals(.85, reopened.getBackgroundDim(), 0);
        assertEquals(.85, new JSONObject(reopened.getChart().toJsonString())
                .getJSONObject("META").getDouble("backgroundDim"), 0);
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals(.85, exported.getBackgroundDim(), 0);
        assertEquals(expected, exported.getManifests().get(0).getSourceText());
        for (String path : new String[]{"music/song.ogg", "art/cover.png", "extras/unknown.bin"}) {
            assertArrayEquals(entries.get(path), Files.readAllBytes(
                    new File(exported.getWorkspace(), path).toPath()));
        }
        // A second chart remains independent of the first chart's metadata.
        ChartPackage other = importPackage(zip(normalEntries()), PackageLimits.DEFAULT);
        assertEquals(.6, other.getBackgroundDim(), 0);
    }

    @Test public void customDimWithoutYamlCreatesPhiraManifestAndRetainsTxtAndUnknownData() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        entries.remove("info.yml");
        ChartPackage pack = importPackage(zip(entries), PackageLimits.DEFAULT);
        pack.getChart().backgroundDim = .25;
        ChartPackage exported = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals(.25, exported.getBackgroundDim(), 0);
        PackageManifest yaml = exported.getManifests().stream()
                .filter(m -> m.getKind() == PackageManifest.Kind.YAML).findFirst().orElseThrow();
        assertEquals("chart.data", yaml.get("chart"));
        assertEquals("music/song.ogg", yaml.get("music"));
        assertEquals("art/cover.png", yaml.get("illustration"));
        assertEquals("0.25", yaml.get("backgroundDim"));
        assertArrayEquals(entries.get("extras/unknown.bin"), Files.readAllBytes(
                new File(exported.getWorkspace(), "extras/unknown.bin").toPath()));
        PackageWorkspaceWriter.writeChart(pack, pack.getChart());
        assertTrue(new File(pack.getWorkspace(), "info.yml").isFile());
        assertEquals(.25, new PackageWorkspaceLoader().load(pack.getWorkspace(), "chart.zip")
                .getBackgroundDim(), 0);
    }

    @Test public void exportingUnsavedDimEditStillUpdatesYamlAndCanRestoreDefault() throws Exception {
        ChartPackage pack = importPackage(zip(normalEntries()), PackageLimits.DEFAULT);
        pack.getChart().backgroundDim = 0.0;
        ChartPackage first = importPackage(export(pack), PackageLimits.DEFAULT);
        assertEquals(0, first.getBackgroundDim(), 0);
        assertEquals("0.0", first.getManifests().get(0).get("backgroundDim"));
        first.getChart().backgroundDim = .6;
        ChartPackage second = importPackage(export(first), PackageLimits.DEFAULT);
        assertEquals(.6, second.getBackgroundDim(), 0);
        assertEquals("0.6", second.getManifests().get(0).get("backgroundDim"));
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

    @Test public void customShadersSurviveEditingExportAndWorkspaceReloadByteForByte() throws Exception {
        Map<String, byte[]> entries = normalEntries();
        byte[] extra = bytes("{\"bpm\":120,\"effects\":[{\"start\":[0,0,1],\"end\":[4,0,1],"
                + "\"shader\":\"/fx/custom.glsl\",\"vars\":{\"power\":0.8}}],\"futureExtra\":42}");
        byte[] source = bytes("\ufeff#version 100\r\nprecision mediump float;\r\n"
                + "uniform float power; // %0.2%\r\nvoid main(){gl_FragColor=vec4(power,0.0,0.0,1.0);}\r\n");
        entries.put("extra.json", extra); entries.put("fx/custom.glsl", source);
        ChartPackage first = importPackage(zip(entries), PackageLimits.DEFAULT);
        assertEquals("chart.data", first.getChartPath());
        ShaderTimeline before = PreviewShaderLoader.load(first, null);
        assertEquals(1, before.effects.size()); assertTrue(before.effects.get(0).source.startsWith("#version"));
        first.getChart().name = "Edited without changing shaders";
        ChartPackage reopened = importPackage(export(first), PackageLimits.DEFAULT);
        ShaderTimeline after = PreviewShaderLoader.load(reopened, null);
        assertArrayEquals(extra, Files.readAllBytes(new File(reopened.getWorkspace(), "extra.json").toPath()));
        assertArrayEquals(source, Files.readAllBytes(new File(reopened.getWorkspace(), "fx/custom.glsl").toPath()));
        assertEquals(before.effects.get(0).source, after.effects.get(0).source);
        assertEquals(.8f, after.effects.get(0).uniforms.get("power").at(1)[0], 0);
        assertSame(ShaderTimeline.EMPTY, PreviewShaderLoader.load(importPackage(zip(normalEntries()), PackageLimits.DEFAULT), null));
    }
}
