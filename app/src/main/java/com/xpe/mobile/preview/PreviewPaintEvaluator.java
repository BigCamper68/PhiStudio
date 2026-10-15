package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import com.xpe.mobile.preview.PreviewLineEvaluator.ResolvedLine;
import java.util.ArrayList;
import java.util.List;

/** PreviewPaintEvaluator responsibilities extracted from ChartEvaluator. */
final class PreviewPaintEvaluator {
    static List<RenderScene.PaintStroke> evaluatePaintStrokes(
            ChartDocument chart, PreparedChart prepared, int lineIndex, double beat, double localYScale) {
        return evaluatePaintStrokesAtSeconds(chart, prepared, lineIndex,
                prepared.time.seconds(beat), localYScale);
    }

    static List<RenderScene.PaintStroke> evaluatePaintStrokesAtSeconds(
            ChartDocument chart, PreparedChart prepared, int lineIndex, double seconds, double localYScale) {
        JudgeLine source = chart.judgeLines.get(lineIndex);
        ExtendedLineEvents extended = source.extended;
        List<RenderScene.PaintStroke> result = new ArrayList<>();
        if (extended == null || extended.paintEvents.isEmpty() || seconds < 0.0) return result;
        // Seeking has no frame history. Replay at a fixed 60 Hz without resampling old
        // marks as the chart grows. Normal playback appends at actual display frames.
        long frames = (long) Math.floor(seconds * ChartEvaluator.PAINT_REPLAY_FPS);
        for (long frame = 0; frame <= frames; frame++) {
            double sampleSeconds = frame / ChartEvaluator.PAINT_REPLAY_FPS;
            RenderScene.PaintStroke stroke = PreviewPaintEvaluator.paintSampleAtSeconds(chart, prepared, lineIndex, sampleSeconds, localYScale);
            if (stroke == null) result.clear(); else result.add(stroke);
        }
        if (seconds > frames / ChartEvaluator.PAINT_REPLAY_FPS + 1e-9) {
            RenderScene.PaintStroke stroke = PreviewPaintEvaluator.paintSampleAtSeconds(chart, prepared, lineIndex, seconds, localYScale);
            if (stroke == null) result.clear(); else result.add(stroke);
        }
        return result;
    }

    static RenderScene.PaintStroke paintSample(ChartDocument chart, PreparedChart prepared,
                                                        int lineIndex, double sampleBeat, double localYScale) {
        return paintSampleAtSeconds(chart, prepared, lineIndex,
                prepared.time.seconds(sampleBeat), localYScale);
    }

    static RenderScene.PaintStroke paintSampleAtSeconds(ChartDocument chart, PreparedChart prepared,
                                                       int lineIndex, double seconds, double localYScale) {
        JudgeLine source = chart.judgeLines.get(lineIndex);
        ExtendedLineEvents extended = source.extended;
        PrprTime time = prepared.time;
        double radius = PrprEvents.numericAtSeconds(extended.paintEvents, time, seconds, -1.0, false);
        if (!Double.isFinite(radius) || radius <= 0.0) return null;
        ResolvedLine transform = PreviewLineEvaluator.resolveSingleLineAtSeconds(chart, prepared, lineIndex, seconds,
                new boolean[chart.judgeLines.size()], localYScale);
        int color = PrprEvents.colorAtSeconds(extended.colorEvents, time, seconds, 0xFFFFFF);
        int alpha = ChartEvaluator.clampAlpha(prepared.lines[lineIndex].valueAt(
                EventType.ALPHA, time.beat(seconds), seconds) * 2.55);
        return new RenderScene.PaintStroke(transform.x, transform.y, radius, transform.rotationDegrees,
                PreviewLineEvaluator.lineScaleAtSeconds(source, extended, true, seconds, time),
                PreviewLineEvaluator.lineScaleAtSeconds(source, extended, false, seconds, time), color, alpha);
    }
}
