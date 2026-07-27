package com.xpe.mobile.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ChartDocumentTest {
    @Test
    public void convertsAcrossMultipleBpmSegments() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\n"
                + "\"BPMList\":["
                + "{\"bpm\":120,\"startTime\":[0,0,1]},"
                + "{\"bpm\":60,\"startTime\":[4,0,1]}],"
                + "\"META\":{\"offset\":250},\"judgeLineList\":[]}");

        assertEquals(4000L, chart.beatToMillis(6.0));
        assertEquals(5.0, chart.millisToBeat(3000L), 0.000001);
        assertEquals(4250L, chart.beatToAudioMillis(6.0));
        assertEquals(6.0, chart.audioMillisToBeat(4250L), 0.000001);
    }

    @Test
    public void combinesChartAndPackageOffsetsLikePhiraPlayback()
            throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{"
                + "\"BPMList\":[{\"bpm\":120,\"startTime\":[0,0,1]}],"
                + "\"META\":{\"offset\":-219},\"judgeLineList\":[]}");

        assertEquals(1756L, chart.beatToAudioMillis(4.0, -25L));
        assertEquals(1781L, chart.beatToAudioMillis(4.0, 0L));
        assertEquals(2780L, chart.beatToAudioMillis(4.0, 999L));
        assertEquals(4.0, chart.audioMillisToBeat(1756L, -25L), 0.000001);
        assertEquals(4.0, chart.audioMillisToBeat(2780L, 999L), 0.000001);
        assertEquals(0.488, chart.audioMillisToBeat(0L, -25L), 0.000001);
        assertEquals(-219, chart.offsetMs);
    }

    @Test
    public void convertsBothDirectionsAcrossThreeBpmSegments() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\n"
                + "\"BPMList\":["
                + "{\"bpm\":120,\"startTime\":[0,0,1]},"
                + "{\"bpm\":240,\"startTime\":[4,0,1]},"
                + "{\"bpm\":60,\"startTime\":[6,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[]}");

        assertEquals(2000L, chart.beatToMillis(4.0));
        assertEquals(2500L, chart.beatToMillis(6.0));
        assertEquals(4500L, chart.beatToMillis(8.0));
        assertEquals(4.0, chart.millisToBeat(2000L), 0.000001);
        assertEquals(6.0, chart.millisToBeat(2500L), 0.000001);
        assertEquals(8.0, chart.millisToBeat(4500L), 0.000001);
        assertEquals(5.5, chart.millisToBeat(chart.beatToMillis(5.5)), 0.002);
        assertEquals(7.25, chart.millisToBeat(chart.beatToMillis(7.25)), 0.002);
    }

    @Test
    public void ignoresInvalidImportedBpmForClockWithoutRewritingIt() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\n"
                + "\"BPMList\":["
                + "{\"bpm\":120,\"startTime\":[0,0,1]},"
                + "{\"bpm\":-5,\"startTime\":[4,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[]}");

        assertEquals(3000L, chart.beatToMillis(6.0));
        assertEquals(-5.0, new org.json.JSONObject(chart.toJsonString())
                .getJSONArray("BPMList").getJSONObject(1).getDouble("bpm"), 0.0);
    }

    @Test
    public void usesFirstBpmBeforeDelayedFirstChange() throws Exception {
        ChartDocument chart = ChartDocument.fromJson("{\n"
                + "\"BPMList\":["
                + "{\"bpm\":120,\"startTime\":[2,0,1]},"
                + "{\"bpm\":60,\"startTime\":[4,0,1]}],"
                + "\"META\":{},\"judgeLineList\":[]}");

        assertEquals(4000L, chart.beatToMillis(6.0));
        assertEquals(6.0, chart.millisToBeat(4000L), 0.000001);
    }
}
