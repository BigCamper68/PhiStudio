package com.xpe.mobile.preview;

import com.xpe.mobile.model.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** IF = Infinity's real beat 1015 / 300s boundary, without its media assets. */
public class SecondsBoundaryRegressionTest {
    private static BeatTime beat(int value) { return new BeatTime(value, 0, 1); }
    private static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange(); bpm.bpm = 202.99999999999997;
        chart.bpmChanges.add(bpm); chart.judgeLines.add(new JudgeLine());
        return chart;
    }
    private static LineEvent event(EventType type, int start, int end, double value) {
        LineEvent event = new LineEvent(); event.type = type;
        event.startTime = beat(start); event.endTime = beat(end);
        event.start = event.end = value; return event;
    }
    private static void step(ChartDocument chart, EventType type, double before, double after) {
        List<LineEvent> events = chart.judgeLines.get(0).layer(0).events(type);
        events.clear(); events.add(event(type, 0, 1015, before));
        events.add(event(type, 1015, 1016, after)); chart.markEdited();
    }
    private static ExtendedLineEvents.NumericEvent numeric(int start, int end, double value) {
        ExtendedLineEvents.NumericEvent event = new ExtendedLineEvents.NumericEvent();
        event.startTime = beat(start); event.endTime = beat(end);
        event.start = event.end = value; return event;
    }

    @Test public void spatialAlphaAndRotationActivateAtExactClockBoundaryWithoutEarlyEpsilon() {
        ChartDocument chart = chart(); step(chart, EventType.MOVE_X, 0, 675);
        step(chart, EventType.ALPHA, 0, 255); step(chart, EventType.ROTATE, -90, -100);
        PreviewChartIndex.PreparedChart prepared = PreviewChartIndex.prepared(chart, false);
        assertEquals(300.0, prepared.time.seconds(1015), 0.0);
        double roundtripBeat = prepared.time.beat(300.0);
        assertTrue("Fixture must retain its floating boundary failure", roundtripBeat < 1015);
        double[] times = {Math.nextDown(300.0), 300.0, Math.nextUp(300.0),
                299.99, 300.0};
        for (double seconds : times) {
            RenderScene.RenderLine line = ChartEvaluator.evaluateAtSeconds(chart, seconds, false).lines.get(0);
            assertEquals(seconds < 300.0 ? 0 : 675, line.x, 0.0);
            assertEquals(seconds < 300.0 ? 0 : 255, line.alpha);
            assertEquals(seconds < 300.0 ? 90 : 100, line.rotationDegrees, 0.0);
        }
    }

    @Test public void extendedChannelsGifAndLiteralTextKeyframeUseExactSeconds() {
        ChartDocument chart = chart(); ExtendedLineEvents extended = chart.judgeLines.get(0).extended;
        extended.scaleXEvents.add(numeric(0, 1015, 1));
        extended.scaleXEvents.add(numeric(1015, 1016, 4));
        extended.inclineEvents.add(numeric(0, 1015, 0));
        extended.inclineEvents.add(numeric(1015, 1016, 45));
        ExtendedLineEvents.ColorEvent color = new ExtendedLineEvents.ColorEvent();
        color.startTime = beat(1015); color.endTime = beat(1016);
        color.startRgb = color.endRgb = 0x123456; extended.colorEvents.add(color);
        ExtendedLineEvents.TextEvent text = new ExtendedLineEvents.TextEvent();
        text.startTime = beat(1015); text.endTime = beat(1016);
        text.start = text.end = "arrived"; extended.textEvents.add(text);
        RenderScene.RenderLine before = ChartEvaluator.evaluateAtSeconds(chart, Math.nextDown(300.0), false).lines.get(0);
        assertEquals(0xFFFFFF, before.colorRgb); assertEquals("", before.text);
        RenderScene.RenderLine at = ChartEvaluator.evaluateAtSeconds(chart, 300.0, false).lines.get(0);
        assertEquals(4.0, at.scaleX, 0.0); assertEquals(45.0, at.inclineDegrees, 0.0);
        assertEquals(0x123456, at.colorRgb); assertEquals("arrived", at.text);
        PrprTime time = new PrprTime(chart);
        text.startTime = beat(0); text.endTime = beat(1015); text.start = "0%P%"; text.end = "10%P%";
        assertEquals("10%P%", PrprEvents.textAtSeconds(extended.textEvents, time, 300.0));
        chart.judgeLines.get(0).texture = "test.gif";
        ExtendedLineEvents.NumericEvent gif = numeric(0, 1015, 0); gif.end = 1;
        extended.gifEvents.add(gif);
        at = ChartEvaluator.evaluateAtSeconds(chart, 300.0, false).lines.get(0);
        assertFalse(at.gifControlled); assertEquals(300000L, at.gifAnchorTimeMs);
    }

    @Test public void noteJudgementAndHudAgreeWithExactEventClock() {
        ChartDocument chart = chart(); Note note = new Note();
        note.type = NoteType.TAP; note.startTime = note.endTime = beat(1015);
        chart.judgeLines.get(0).notes.add(note);
        RenderScene before = ChartEvaluator.evaluateAtSeconds(chart, Math.nextDown(300.0), false);
        assertEquals(1, before.lines.get(0).notes.size()); assertEquals(0, before.hud.combo);
        RenderScene at = ChartEvaluator.evaluateAtSeconds(chart, 300.0, false);
        assertEquals(0, at.lines.get(0).notes.size()); assertEquals(1, at.hud.combo);
        assertEquals(1, at.lines.get(0).hitEffects.size());
    }

    @Test public void overlapRetainsSourceOrderPrerollAndBackwardSeeks() {
        ChartDocument chart = chart(); PrprTime time = new PrprTime(chart);
        List<ExtendedLineEvents.NumericEvent> events = new ArrayList<>();
        events.add(numeric(1000, 1015, 10)); events.add(numeric(1005, 1016, 20));
        for (double seconds : new double[]{299.9, 300.0, 300.1, 299.9}) {
            assertEquals(seconds < 300.0 ? 10 : 20,
                    PrprEvents.numericAtSeconds(events, time, seconds, -1, false), 0.0);
        }
        assertEquals(-1, PrprEvents.numericAtSeconds(events, time, 0, -1, false), 0.0);
        assertEquals(10, PrprEvents.numericAtSeconds(events, time, 0, -1, true), 0.0);
    }

    @Test public void paintSampleKeepsExactSecondsForChannelAndTransformActivation() {
        ChartDocument chart = chart(); step(chart, EventType.MOVE_X, 0, 675);
        chart.judgeLines.get(0).extended.paintEvents.add(numeric(1015, 1016, 2));
        PreviewChartIndex.PreparedChart prepared = PreviewChartIndex.prepared(chart, false);
        assertNull(PreviewPaintEvaluator.paintSampleAtSeconds(chart, prepared, 0, Math.nextDown(300.0), 1));
        RenderScene.PaintStroke stroke = PreviewPaintEvaluator.paintSampleAtSeconds(chart, prepared, 0, 300.0, 1);
        assertNotNull(stroke); assertEquals(675.0, stroke.x, 0.0); assertEquals(2.0, stroke.radius, 0.0);
    }

    @Test public void bpmClockAnchorsZeroAtFirstAuthoredBeatAndKeepsNegativePreroll() throws Exception {
        for (int first : new int[]{4, -4}) {
            ChartDocument chart = chart(); chart.bpmChanges.get(0).bpm = 120;
            chart.bpmChanges.get(0).startTime = beat(first);
            BpmChange next = new BpmChange(); next.startTime = beat(first + 4); next.bpm = 60;
            chart.bpmChanges.add(next);
            // Import must retain the authored first BPM position before the time index sees it.
            chart = ChartDocument.fromJson(chart.toJsonString());
            PrprTime time = new PrprTime(chart);
            assertEquals(0.0, time.seconds(first), 0.0);
            assertEquals(-1.0, time.seconds(first - 2), 0.0);
            assertEquals(2.0, time.seconds(first + 4), 0.0);
            assertEquals(3.0, time.seconds(first + 5), 0.0);
            assertEquals(first, chart.secondsToBeat(0.0), 0.0);
            assertEquals(0.0, chart.beatToSeconds(first), 0.0);
            assertEquals(3000L, chart.beatToMillis(first + 5));
            assertEquals(first + 5, chart.millisToBeat(3000), 0.0);
            assertEquals(first, ChartEvaluator.evaluateAtSeconds(chart, 0.0, false).beat, 0.0);
            for (double seconds : new double[]{0, 3, -1, 2, .5}) {
                assertEquals(seconds, time.seconds(time.beat(seconds)), 0.0);
            }
        }
        ChartDocument empty = new ChartDocument(); PrprTime fallback = new PrprTime(empty);
        assertEquals(2.0, fallback.seconds(4), 0.0);
        assertEquals(4.0, fallback.beat(2), 0.0);
    }
}
