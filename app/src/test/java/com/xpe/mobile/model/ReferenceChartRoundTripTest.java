package com.xpe.mobile.model;

import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public final class ReferenceChartRoundTripTest {
    @Test
    public void suppliedChartsRoundTripWithStableCounts() throws Exception {
        String referenceRoot = System.getenv("XPE_REFERENCE_CHART_DIR");
        Assume.assumeTrue("Set XPE_REFERENCE_CHART_DIR to run supplied-chart regression checks",
                referenceRoot != null && !referenceRoot.trim().isEmpty());

        verify(Paths.get(referenceRoot, "Introduction", "Introduction.json"), 35, 600, 6291);
        verify(Paths.get(referenceRoot, "Example_1", "Example_1.json"), 205, 1729, 56760);
        verify(Paths.get(referenceRoot, "Example_2", "Example_2.json"), 88, 121688, 29264);
    }

    private static void verify(Path path, int lines, int notes, int events) throws Exception {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        ChartDocument first = ChartDocument.fromJson(source);
        assertEquals(path.toString(), lines, first.judgeLines.size());
        assertEquals(path.toString(), notes, first.totalNotes());
        assertEquals(path.toString(), events, first.totalEvents());

        ChartDocument second = ChartDocument.fromJson(first.toJsonString());
        assertEquals(path.toString(), lines, second.judgeLines.size());
        assertEquals(path.toString(), notes, second.totalNotes());
        assertEquals(path.toString(), events, second.totalEvents());
    }
}
