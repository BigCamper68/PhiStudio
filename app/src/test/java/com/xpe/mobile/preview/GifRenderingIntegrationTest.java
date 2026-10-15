package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.ExtendedLineEvents;
import org.junit.Test;
import static org.junit.Assert.*;

/** Renderer-facing GIF time, not just the authored event scalar. */
public class GifRenderingIntegrationTest {
    private ChartDocument chart() throws Exception {
        return ChartDocument.fromJson("{\"META\":{\"offset\":0},\"BPMList\":[{\"bpm\":130,\"startTime\":[0,0,1]}],"
                + "\"judgeLineList\":[{\"Texture\":\"phase.gif\",\"eventLayers\":[{\"alphaEvents\":[{"
                + "\"start\":255,\"end\":255,\"startTime\":[0,0,1],\"endTime\":[200,0,1]}]}],"
                + "\"extended\":{\"gifEvents\":[{\"start\":10,\"end\":10,\"startTime\":[0,0,1],\"endTime\":[1,0,1]}]}}]}");
    }

    private RenderScene.RenderLine frame(ChartDocument chart, double seconds) {
        return ChartEvaluator.evaluateAtSeconds(chart, seconds, false, -1, false, 16.0 / 9, null).lines.get(0);
    }

    @Test public void displayedMovieTimeMatchesNativeFramesAtVisibleChartMomentsAndAfterReopen() throws Exception {
        ChartDocument chart = chart();
        for (int pass = 0; pass < 2; pass++) {
            RenderScene.RenderLine at48 = frame(chart, 48.00333333333333);
            assertEquals(255, at48.alpha);
            assertEquals(51, at48.gifMovieTimeMillis(4880, 48003) / 80);
            assertEquals(55, frame(chart, 63.00333333333333).gifMovieTimeMillis(4880, 63003) / 80);
            chart = ChartDocument.fromJson(chart.toJsonString());
        }
    }

    @Test public void editsRebuildMovieTimelineWithoutMutatingAnAlreadyPreparedFrame() throws Exception {
        ChartDocument chart = chart();
        RenderScene.RenderLine before = frame(chart, 48.00333333333333);
        ExtendedLineEvents.NumericEvent event = chart.judgeLines.get(0).extended.gifEvents.get(0);
        event.start = event.end = 0;
        chart.markEdited();
        RenderScene.RenderLine after = frame(chart, 48.00333333333333);
        assertEquals(45, after.gifMovieTimeMillis(4880, 48003) / 80);
        assertEquals(51, before.gifMovieTimeMillis(4880, 48003) / 80);
    }

    @Test public void inPlaceEventEditInvalidatesPreparedTimelineWithoutAnExplicitRevision() throws Exception {
        ChartDocument chart = chart();
        assertEquals(51, frame(chart, 48.00333333333333).gifMovieTimeMillis(4880, 48003) / 80);
        ExtendedLineEvents.NumericEvent event = chart.judgeLines.get(0).extended.gifEvents.get(0);
        event.start = event.end = 0;
        assertEquals(45, frame(chart, 48.00333333333333).gifMovieTimeMillis(4880, 48003) / 80);
    }

    @Test public void importedGifSourceOrderIsIndependentOfEditorSort() throws Exception {
        ExtendedLineEvents extended = ExtendedLineEvents.fromJson(new org.json.JSONObject(
                "{\"gifEvents\":[{\"start\":0,\"end\":0,\"startTime\":[5,0,1],\"endTime\":[6,0,1]},"
                + "{\"start\":10,\"end\":10,\"startTime\":[0,0,1],\"endTime\":[1,0,1]}]}"));
        assertEquals(0, extended.gifEvents.get(0).startTime.toDouble(), 0);
        assertEquals(5, extended.gifSourceEvents().get(0).startTime.toDouble(), 0);
        extended = ExtendedLineEvents.fromJson(extended.toJsonValue());
        assertEquals(5, extended.gifSourceEvents().get(0).startTime.toDouble(), 0);
    }
}
