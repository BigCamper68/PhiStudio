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
        JudgeLine source = chart.judgeLines.get(lineIndex);
        ExtendedLineEvents extended = source.extended;
        List<RenderScene.PaintStroke> result = new ArrayList<>();
        if (extended == null || extended.paintEvents.isEmpty() || beat < 0.0) return result;

        double seconds = prepared.time.seconds(beat);
        // Seeking has no frame history. Replay at a fixed 60 Hz without resampling old
        // marks as the chart grows. Normal playback appends at actual display frames.
        long frames = (long) Math.floor(seconds * ChartEvaluator.PAINT_REPLAY_FPS);
        for (long frame = 0; frame <= frames; frame++) {
            double sampleBeat = prepared.time.beat(frame / ChartEvaluator.PAINT_REPLAY_FPS);
            RenderScene.PaintStroke stroke = PreviewPaintEvaluator.paintSample(chart, prepared, lineIndex, sampleBeat, localYScale);
            if (stroke == null) result.clear(); else result.add(stroke);
        }
        if (seconds > frames / ChartEvaluator.PAINT_REPLAY_FPS + 1e-9) {
            RenderScene.PaintStroke stroke = PreviewPaintEvaluator.paintSample(chart, prepared, lineIndex, beat, localYScale);
            if (stroke == null) result.clear(); else result.add(stroke);
        }
        return result;
    }

    static RenderScene.PaintStroke paintSample(ChartDocument chart, PreparedChart prepared,
                                                        int lineIndex, double sampleBeat, double localYScale) {
        JudgeLine source = chart.judgeLines.get(lineIndex);
        ExtendedLineEvents extended = source.extended;
        PrprTime time = prepared.time;
        double radius = PrprEvents.numeric(extended.paintEvents, time, sampleBeat, -1.0, false);
        if (!Double.isFinite(radius) || radius <= 0.0) return null;
        ResolvedLine transform = PreviewLineEvaluator.resolveSingleLineAt(chart, prepared, lineIndex, sampleBeat,
                new boolean[chart.judgeLines.size()], localYScale);
        int color = PrprEvents.color(extended.colorEvents, time, sampleBeat, 0xFFFFFF);
        int alpha = ChartEvaluator.clampAlpha(prepared.lines[lineIndex].valueAt(EventType.ALPHA, sampleBeat) * 2.55);
        return new RenderScene.PaintStroke(transform.x, transform.y, radius, transform.rotationDegrees,
                PreviewLineEvaluator.lineScaleAt(source, extended, true, sampleBeat, time),
                PreviewLineEvaluator.lineScaleAt(source, extended, false, sampleBeat, time), color, alpha);
    }
}
