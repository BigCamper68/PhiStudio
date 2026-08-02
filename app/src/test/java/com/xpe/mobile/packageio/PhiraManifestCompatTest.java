package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PhiraManifestCompatTest {
    @Test
    public void expandsMinimalPhiStudioManifest() {
        ChartDocument chart = new ChartDocument();
        chart.name = "Tornado";
        chart.composer = "Composer";
        chart.charter = "Charter";
        chart.level = "IN Lv.15";

        String result = PhiraManifestCompat.normalize(
                "name: \"Tornado\"\nchart: chart.json\nmusic: audio.mp3\n"
                        + "illustration: illustration.jpg\noffset: 0\n",
                chart, "chart.json", "audio.mp3", "illustration.jpg", 50L, false);

        assertTrue(result.contains("difficulty: 15"));
        assertTrue(result.contains("format: null"));
        assertTrue(result.contains("composer: \"Composer\""));
        assertTrue(result.contains("charter: \"Charter\""));
        assertTrue(result.contains("offset: 0.05"));
        assertTrue(result.contains("previewStart: 0"));
        assertTrue(result.contains("previewEnd: null"));
        assertTrue(result.contains("useRpe170Speed: null"));
        assertTrue(result.contains("tags: []"));
    }

    @Test
    public void enablesModernSpeedOnlyWhenPackageRequestsIt() {
        String result = PhiraManifestCompat.normalize("", new ChartDocument(),
                "chart.json", "audio.mp3", "illustration.png", 0L, true);
        assertTrue(result.contains("useRpe170Speed: true"));
    }

    @Test
    public void preservesUnknownManifestExtensionsExactly() {
        String source = "# future package\r\n"
                + "name: Custom\r\n"
                + "chart: chart.data\r\n"
                + "futureField: {opaque: true}\r\n"
                + "custom line without colon\r\n";

        String result = PhiraManifestCompat.normalize(source, new ChartDocument(),
                "chart.data", null, null, 0L, false);

        assertEquals(source, result);
    }
}
