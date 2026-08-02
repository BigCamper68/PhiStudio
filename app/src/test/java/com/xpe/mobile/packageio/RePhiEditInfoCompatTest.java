package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RePhiEditInfoCompatTest {
    @Test
    public void createsRootChartIndexForPezImport() {
        ChartDocument chart = new ChartDocument();
        chart.name = "Tornado";
        chart.level = "AT Lv.16";
        chart.composer = "Camellia";
        chart.charter = "BigCamper68";

        String result = RePhiEditInfoCompat.normalize("", chart,
                "charts/chart.json", "audio.mp3", "illustration.jpg");

        assertTrue(result.startsWith("#\n"));
        assertTrue(result.contains("Name: Tornado\n"));
        assertTrue(result.contains("Path: chart\n"));
        assertTrue(result.contains("Song: audio.mp3\n"));
        assertTrue(result.contains("Picture: illustration.jpg\n"));
        assertTrue(result.contains("Chart: charts/chart.json\n"));
        assertTrue(result.contains("Group: Default\n"));
    }

    @Test
    public void preservesExistingEditorMetadataExactly() {
        String source = "#\r\nName: Existing\r\nCustom: value\r\n";
        assertEquals(source, RePhiEditInfoCompat.normalize(source,
                new ChartDocument(), "chart.json", "audio.mp3", "background.png"));
    }
}
