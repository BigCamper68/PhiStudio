package com.xpe.mobile.preview;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteControlEvents;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedNote;
import com.xpe.mobile.preview.PreviewLineEvaluator.ResolvedLine;
import java.util.List;

/** PreviewHitEvaluator responsibilities extracted from ChartEvaluator. */
final class PreviewHitEvaluator {
    static void appendHitEffects(
            ChartDocument chart, PreparedChart prepared, int lineIndex, PreparedNote cached,
            NoteControlEvents controls, long chartTimeMs,
            double noteSpeed, double yOffset, List<RenderScene.HitEffect> target, double localYScale) {
        Note note = cached.note;
        if (note.fake) return;
        if (note.type != NoteType.HOLD) {
            long effectTimeMs = cached.startMs;
            PreviewHitEvaluator.appendHitEffectAt(chart, prepared, lineIndex, note, controls, cached.startBeat,
                    effectTimeMs, chartTimeMs, noteSpeed, yOffset, target, localYScale);
            return;
        }
        long startTimeMs = cached.startMs;
        long endTimeMs = cached.endMs;
        if (chartTimeMs >= endTimeMs) {
            PreviewHitEvaluator.appendHitEffectAt(chart, prepared, lineIndex, note, controls, cached.endBeat,
                    endTimeMs, chartTimeMs, noteSpeed, yOffset, target, localYScale);
        }
        long pulseLimitMs = Math.min(chartTimeMs, endTimeMs - 1);
        if (pulseLimitMs < startTimeMs) return;
        long pulseIndex = (pulseLimitMs - startTimeMs) / ChartEvaluator.HOLD_EFFECT_INTERVAL_MS;
        long pulseTimeMs = startTimeMs + pulseIndex * ChartEvaluator.HOLD_EFFECT_INTERVAL_MS;
        for (int count = 0; count < ChartEvaluator.MAX_ACTIVE_HOLD_EFFECTS
                && pulseTimeMs >= startTimeMs; count++) {
            long ageMs = chartTimeMs - pulseTimeMs;
            if (ageMs >= ChartEvaluator.HIT_EFFECT_DURATION_MS) break;
            if (ageMs >= 0L) {
                double pulseBeat = chart.millisToBeat(pulseTimeMs);
                PreviewHitEvaluator.appendHitEffectAt(chart, prepared, lineIndex, note, controls, pulseBeat,
                        pulseTimeMs, chartTimeMs, noteSpeed, yOffset, target, localYScale);
            }
            pulseTimeMs -= ChartEvaluator.HOLD_EFFECT_INTERVAL_MS;
        }
    }

    static void appendHitEffectAt(
            ChartDocument chart, PreparedChart prepared, int lineIndex, Note note, NoteControlEvents controls,
            double effectBeat, long effectTimeMs, long chartTimeMs,
            double noteSpeed, double yOffset,
            List<RenderScene.HitEffect> target, double localYScale) {
        long ageMs = chartTimeMs - effectTimeMs;
        if (ageMs < 0L || ageMs >= ChartEvaluator.HIT_EFFECT_DURATION_MS) return;
        double positionControl = note.type == NoteType.HOLD ? 1.0
                : ChartEvaluator.finiteOr(PrprEvents.control(
                controls.position, yOffset, 1.0), 1.0);
        double localX = ChartEvaluator.finiteOr(note.positionX, 0.0) * positionControl;
        double localY = yOffset * noteSpeed * (note.above == 1 ? 1.0 : -1.0);
        ResolvedLine transform = PreviewLineEvaluator.resolveSingleLineAt(chart, prepared, lineIndex, effectBeat,
                new boolean[chart.judgeLines.size()], localYScale);
        double radians = Math.toRadians(transform.rotationDegrees);
        double worldX = transform.x + Math.cos(radians) * localX
                - Math.sin(radians) * localY * localYScale;
        double worldY = transform.y + Math.sin(radians) * localX / localYScale
                + Math.cos(radians) * localY;
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)) return;
        int seed = 31 * (31 * (lineIndex + 1)
                + (note.startTime == null ? 0 : note.startTime.hashCode()))
                + Double.hashCode(effectBeat) + 17 * Double.hashCode(note.positionX)
                + 37 * note.type.ordinal();
        target.add(new RenderScene.HitEffect(worldX, worldY, transform.x, transform.y,
                localX, localY, transform.rotationDegrees,
                ageMs / (double) ChartEvaluator.HIT_EFFECT_DURATION_MS,
                note.hasHitEffectTint ? note.hitEffectTintRgb : 0xFFEC9F, seed,
                note.hasHitEffectTint ? 255 : 225));
    }
}
