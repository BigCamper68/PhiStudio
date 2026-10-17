package com.xpe.mobile.preview;

import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import java.util.Map;

/** PreviewHudEvaluator responsibilities extracted from ChartEvaluator. */
final class PreviewHudEvaluator {
    static RenderScene.HudState buildHudState(
            ChartDocument chart, PreparedChart prepared, double beat,
            long chartTimeMs, long trackDurationMs,
            Map<AttachedUiElement, RenderScene.HudTransform> transforms) {
        return buildHudStateAtSeconds(chart, prepared, beat, prepared.time.seconds(beat),
                chartTimeMs, trackDurationMs, transforms);
    }

    static RenderScene.HudState buildHudStateAtSeconds(
            ChartDocument chart, PreparedChart prepared, double beat, double seconds,
            long chartTimeMs, long trackDurationMs,
            Map<AttachedUiElement, RenderScene.HudTransform> transforms) {
        int total = prepared.judgementBeats.length;
        int passed = PreviewHudEvaluator.upperBound(prepared.judgementSeconds, seconds);
        double completion = total == 0 ? 0.0 : passed / (double) total;
        int score = (int) Math.round(Math.max(0.0, Math.min(1.0, completion))
                * 1_000_000.0);
        double progress = trackDurationMs > 0L
                ? chartTimeMs / (double) trackDurationMs
                : prepared.finalBeat <= 0.0 ? 0.0 : beat / prepared.finalBeat;
        progress = Math.max(0.0, Math.min(1.0, progress));
        return new RenderScene.HudState(chart.getDisplayName(), chart.getDisplayLevel(), passed,
                score, progress, transforms, chart.useAttachUiFix);
    }

    static int upperBound(double[] values, double target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] <= target) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}
