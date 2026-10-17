package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

public final class ChartBackgroundDimTest {
    @Test public void attachUiFixComesOnlyFromYamlLikePhiraNotRpeMeta() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\"META\":{\"useAttachUiFix\":true}}");
        assertFalse(chart.useAttachUiFix);
        new ChartPackage(new File("unused"), "chart.zip", "chart", "chart.json",
                null, null, 0, false, chart, Collections.emptyList(), List.of(PackageManifest.parse(
                "info.yml", PackageManifest.Kind.YAML, "useAttachUiFix: true # Phira compatibility\n")));
        assertTrue(chart.useAttachUiFix);
        assertFalse(ChartDocument.fromJson(chart.toJsonString()).useAttachUiFix);
        assertFalse(new ChartDocument().useAttachUiFix);
        // Unknown authored META values are preserved but not interpreted as ChartInfo.
        assertTrue(new org.json.JSONObject(chart.toJsonString()).getJSONObject("META").getBoolean("useAttachUiFix"));
    }
    @Test public void uniformNoteScaleComesFromYamlOnly() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{}");
        new ChartPackage(new File("unused"), "chart.zip", "chart", "chart.json",
                null, null, 0, false, chart, Collections.emptyList(), List.of(PackageManifest.parse(
                "info.yml", PackageManifest.Kind.YAML, "noteUniformScale: true # enabled\n")));
        assertTrue(chart.noteUniformScale);
        assertFalse(new org.json.JSONObject(chart.toJsonString()).getJSONObject("META").has("noteUniformScale"));
    }

    @Test public void packageUsesYamlDimAndFallsBackForOrdinaryRpePackages() {
        assertEquals(.1,chartPackage(List.of(PackageManifest.parse("info.yml",
                PackageManifest.Kind.YAML,"backgroundDim: 0.1\n"))).getBackgroundDim(),0);
        assertEquals(.6,chartPackage(Collections.emptyList()).getBackgroundDim(),0);
        assertEquals(.6,chartPackage(List.of(PackageManifest.parse("info.yml",
                PackageManifest.Kind.YAML,"backgroundDim: NaN\n"))).getBackgroundDim(),0);
    }

    @Test public void actualOblivionManifestRetainsItsAuthoredDim() throws Exception {
        String directory = System.getenv("PHISTUDIO_CHART_DIR");
        Assume.assumeNotNull(directory);
        Assume.assumeTrue("Oblivion fixture not supplied", new File(directory, "oblivionphim.pez").isFile());
        try (ZipFile zip = new ZipFile(directory + "/oblivionphim.pez");
             var input = zip.getInputStream(zip.getEntry("info.yml"))) {
            PackageManifest manifest = PackageManifest.parse("info.yml",PackageManifest.Kind.YAML,
                    new String(input.readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(.1,chartPackage(List.of(manifest)).getBackgroundDim(),0);
        }
    }

    @Test public void chartMetadataWinsOverManifestAndSurvivesStandaloneJsonRoundTrip() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\"META\":{\"backgroundDim\":0.8}}");
        ChartPackage pack = new ChartPackage(new File("unused"),"chart.zip","chart","chart.json",
                null,null,0,false,chart,Collections.emptyList(),List.of(PackageManifest.parse(
                "info.yml", PackageManifest.Kind.YAML, "backgroundDim: 0.1\n")));
        assertEquals(.8, pack.getBackgroundDim(), 0);
        chart.backgroundDim = .35;
        assertEquals(.35, ChartDocument.fromJson(chart.toJsonString()).getBackgroundDim(), 0);
        assertEquals(.35, pack.getBackgroundDim(), 0);
        for (String value : new String[]{"-0.1", "1.1", "\"NaN\"", "null"}) {
            assertEquals(.6, ChartDocument.fromJson("{\"META\":{\"backgroundDim\":"
                    + value + "}}").getBackgroundDim(), 0);
        }
    }

    private static ChartPackage chartPackage(List<PackageManifest> manifests) {
        return new ChartPackage(new File("unused"),"chart.zip","chart","chart.json",
                "audio.ogg","illustration.png",0,false,new ChartDocument(),Collections.emptyList(),manifests);
    }
}
