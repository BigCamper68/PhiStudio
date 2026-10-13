package com.xpe.mobile.preview;

import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteControlEvents;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.model.StoryboardEventType;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedChart;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedLine;
import com.xpe.mobile.preview.PreviewChartIndex.PreparedNote;
import com.xpe.mobile.preview.PreviewGifEvaluator.GifState;
import com.xpe.mobile.preview.PreviewLineEvaluator.AlphaExtension;
import com.xpe.mobile.preview.PreviewLineEvaluator.LocalLine;
import com.xpe.mobile.preview.PreviewLineEvaluator.ResolvedLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministically evaluates the supported RPE chart model at a supplied beat. */
public final class ChartEvaluator {
    private static final double RPE_WIDTH = 1350.0;
    static final long HIT_EFFECT_DURATION_MS = 500L;
    static final long HOLD_EFFECT_INTERVAL_MS = 150L;
    static final int MAX_ACTIVE_HOLD_EFFECTS = 32;
    static final double PAINT_REPLAY_FPS = 60.0;

    private ChartEvaluator() {
    }

    /**
     * Builds immutable playback indexes before the first preview frame.
     *
     * <p>The caller must exclusively own {@code chart} for the duration of this call; the chart
     * model is intentionally UI-thread confined and is not safe to mutate concurrently.
     */
    public static void prepare(ChartDocument chart, boolean useRpe170Speed) {
        if (chart != null) PreviewChartIndex.prepared(chart, useRpe170Speed);
    }

    public static RenderScene evaluate(ChartDocument chart, double requestedBeat,
                                       boolean highlightSimultaneousNotes) {
        return evaluate(chart, requestedBeat, highlightSimultaneousNotes, -1L, false);
    }

    public static RenderScene evaluate(ChartDocument chart, double requestedBeat,
                                       boolean highlightSimultaneousNotes,
                                       long trackDurationMs) {
        return evaluate(chart, requestedBeat, highlightSimultaneousNotes,
                trackDurationMs, false);
    }

    public static RenderScene evaluate(ChartDocument chart, double requestedBeat,
                                       boolean highlightSimultaneousNotes,
                                       long trackDurationMs,
                                       boolean useRpe170Speed) {
        return evaluate(chart, requestedBeat, highlightSimultaneousNotes,
                trackDurationMs, useRpe170Speed, 1.5);
    }

    /** Aspect is needed before rotating a child line's normalized translation. */
    public static RenderScene evaluate(ChartDocument chart, double requestedBeat,
                                       boolean highlightSimultaneousNotes,
                                       long trackDurationMs, boolean useRpe170Speed,
                                       double aspectRatio) {
        return evaluate(chart, requestedBeat, highlightSimultaneousNotes, trackDurationMs,
                useRpe170Speed, aspectRatio, null);
    }

    /** Stateful paint accumulation for actual playback; null reconstructs a still frame. */
    public static RenderScene evaluate(ChartDocument chart, double requestedBeat,
                                       boolean highlightSimultaneousNotes, long trackDurationMs,
                                       boolean useRpe170Speed, double aspectRatio,
                                       PreviewPlaybackState playback) {
        if (chart == null) throw new IllegalArgumentException("chart is required");
        double localYScale = Double.isFinite(aspectRatio) && aspectRatio > 0
                ? 1.5 / aspectRatio : 1.0;
        double beat = finiteOr(requestedBeat, 0.0);
        beat = Math.max(0.0, beat);
        long chartTimeMs = chart.beatToMillis(beat);
        PreparedChart prepared = PreviewChartIndex.prepared(chart, useRpe170Speed);
        PrprTime time = prepared.time;
        double seconds = time.seconds(beat);
        boolean rebuildPaint = playback == null || playback.begin(prepared, seconds, localYScale);
        Map<Integer, PaintHistory> paintHistories = new HashMap<>();
        Map<BeatTime, Integer> simultaneous = highlightSimultaneousNotes
                ? prepared.simultaneous : Collections.emptyMap();

        int count = chart.judgeLines.size();
        LocalLine[] local = new LocalLine[count];
        ResolvedLine[] resolved = new ResolvedLine[count];
        int[] resolveState = new int[count];
        for (int index = 0; index < count; index++) {
            JudgeLine line = chart.judgeLines.get(index);
            PreparedLine preparedLine = prepared.lines[index];
            local[index] = new LocalLine(
                    preparedLine.valueAt(EventType.MOVE_X, beat, seconds),
                    preparedLine.valueAt(EventType.MOVE_Y, beat, seconds),
                    -preparedLine.valueAt(EventType.ROTATE, beat, seconds),
                    preparedLine.valueAt(EventType.ALPHA, beat, seconds));
        }
        for (int index = 0; index < count; index++) {
            PreviewLineEvaluator.resolveLine(chart, index, local, resolved, resolveState, localYScale);
        }

        List<RenderScene.RenderLine> lines = new ArrayList<>(count);
        Map<AttachedUiElement, RenderScene.HudTransform> hudTransforms =
                new EnumMap<>(AttachedUiElement.class);
        for (int index = 0; index < count; index++) {
            JudgeLine source = chart.judgeLines.get(index);
            ResolvedLine transform = resolved[index];
            if (!Double.isFinite(transform.x) || !Double.isFinite(transform.y)
                    || !Double.isFinite(transform.rotationDegrees)) continue;
            ExtendedLineEvents extended = source.extended == null
                    ? new ExtendedLineEvents() : source.extended;
            if (source.attachUi != null) {
                int hudColor = extended.colorEvents.isEmpty() ? -1
                        : PrprEvents.color(
                        extended.colorEvents, time, beat, 0xFFFFFF);
                double hudScaleX = PreviewLineEvaluator.lineScaleAt(source, extended, true, beat, time);
                double hudScaleY = PreviewLineEvaluator.lineScaleAt(source, extended, false, beat, time);
                hudTransforms.put(source.attachUi, new RenderScene.HudTransform(
                        index, transform.x, transform.y, local[index].rotationDegrees,
                        clampAlpha(local[index].rawAlpha), hudColor,
                        hudScaleX, hudScaleY));
                continue;
            }
            double incline = finiteOr(PrprEvents.numeric(
                    extended.inclineEvents, time, beat, 0.0, false), 0.0);
            AlphaExtension alphaExtension = AlphaExtension.from(
                    local[index].rawAlpha, source.cover);
            PrprSpeed speed = prepared.lines[index].speed;
            double currentDistance = speed.distanceAt(beat);
            List<RenderScene.RenderNote> notes = new ArrayList<>();
            List<RenderScene.HitEffect> effects = new ArrayList<>();
            NoteControlEvents controls = source.noteControls == null
                    ? new NoteControlEvents() : source.noteControls;
            for (PreparedNote cached : prepared.lines[index].notes) {
                Note note = cached.note;
                double startBeat = cached.startBeat;
                long startTimeMs = cached.startMs;
                boolean hold = note.type == NoteType.HOLD;
                double endBeat = cached.endBeat;
                // Fake notes without cover can remain visible after their timestamp.
                if ((hold || !note.fake || alphaExtension.cover)
                        && beat > endBeat && chartTimeMs - cached.endMs >= HIT_EFFECT_DURATION_MS) continue;

                double noteSpeed = finiteOr(note.speed, 1.0);
                double yOffset = finiteOr(note.yOffset, 0.0);
                double baseStartDistance = cached.startDistance - currentDistance;
                double baseEndDistance = hold
                        ? cached.endDistance - currentDistance : baseStartDistance;
                double controlHeight = baseStartDistance + yOffset;
                double positionControl = finiteOr(PrprEvents.control(
                        controls.position, controlHeight, 1.0), 1.0);
                double sizeControl = finiteOr(PrprEvents.control(
                        controls.size, controlHeight, 1.0), 1.0);
                double alphaControl = finiteOr(PrprEvents.control(
                        controls.alpha, controlHeight, 1.0), 1.0);
                double yControl = finiteOr(PrprEvents.control(
                        controls.y, controlHeight, 1.0), 1.0);
                double renderedX = finiteOr(note.positionX, 0.0)
                        * (hold ? 1.0 : positionControl);
                double renderedOffset = yOffset * noteSpeed;
                double startDistance = baseStartDistance * noteSpeed * yControl
                        + renderedOffset;
                double endDistance = baseEndDistance * noteSpeed * yControl
                        + renderedOffset;
                if (hold && beat >= startBeat) startDistance = renderedOffset;

                if (beat >= startBeat) PreviewHitEvaluator.appendHitEffects(chart, prepared, index, cached, controls,
                        chartTimeMs, noteSpeed, yOffset, effects, localYScale);
                // Negative line alpha hides geometry, but real notes still hit normally.
                if (alphaExtension.hideLineAndNotes) continue;

                if (beat < startBeat - alphaExtension.appearBeforeBeats) continue;
                if ((!hold && note.fake && alphaExtension.cover && beat >= startBeat)
                        || (!hold && !note.fake && beat >= startBeat)
                        || (hold && beat >= endBeat)) continue;

                double secondsUntilHit = cached.startSeconds - seconds;
                double visibleTime = finiteOr(note.visibleTime, Double.POSITIVE_INFINITY);
                if (secondsUntilHit > visibleTime) continue;

                if (alphaExtension.cover && beat < startBeat
                        // prpr compares the scroll distance before yOffset, in normalized screen units.
                        // Its -0.001 tolerance keeps notes on a zero-speed plateau visible
                        // after cancellation of positive and negative speed integrals.
                        && baseStartDistance * noteSpeed * yControl <= -0.675 / localYScale) continue;

                boolean multiHit = highlightSimultaneousNotes
                        && simultaneous.getOrDefault(note.startTime, 0) > 1;
                double renderedSize = finiteOr(note.size, 1.0) * sizeControl;
                int renderedAlpha = clampAlpha(Math.min(255, note.alpha) * alphaControl);
                if (PreviewLineEvaluator.farOutsideScene(transform, renderedX, startDistance, endDistance,
                        yOffset * noteSpeed, note.above == 1, hold, renderedSize,
                        chart.noteUniformScale, localYScale, Math.sin(Math.toRadians(incline)))) continue;
                if (Math.abs(renderedSize) <= 1.0e-6 || renderedAlpha <= 0) continue;
                notes.add(new RenderScene.RenderNote(note.type,
                        renderedX, startDistance, endDistance,
                        note.above == 1, renderedSize,
                        renderedAlpha, note.fake, multiHit,
                        !hold || beat < startBeat,
                        note.hasTint ? note.tintRgb : 0xFFFFFF,
                        chart.noteUniformScale ? renderedSize : 1.0, renderedOffset));
            }

            int colorRgb = extended.colorEvents.isEmpty() ? -1
                    : PrprEvents.color(
                    extended.colorEvents, time, beat, 0xFFFFFF);
            boolean defaultTexture = source.texture == null
                    || source.texture.trim().isEmpty()
                    || "line.png".equalsIgnoreCase(source.texture.trim());
            boolean paintMode = defaultTexture && extended.hasChannel(StoryboardEventType.PAINT);
            String text = !defaultTexture || paintMode || !extended.hasChannel(StoryboardEventType.TEXT) ? null
                    : PrprEvents.text(extended.textEvents, time, beat);
            double scaleX = PreviewLineEvaluator.lineScaleAt(source, extended, true, beat, time);
            double scaleY = PreviewLineEvaluator.lineScaleAt(source, extended, false, beat, time);
            GifState gif = !defaultTexture && extended.hasChannel(StoryboardEventType.GIF)
                    ? PreviewGifEvaluator.evaluateGifState(chart, time, extended.gifEvents, beat)
                    : GifState.disabled();
            List<RenderScene.PaintStroke> paintStrokes = new ArrayList<>();
            if (paintMode) {
                if (playback == null) {
                    paintStrokes = PreviewPaintEvaluator.evaluatePaintStrokes(chart, prepared, index, beat, localYScale);
                } else {
                    PaintHistory history = playback.paint.get(index);
                    if (rebuildPaint) {
                        history = PaintHistory.empty();
                        for (RenderScene.PaintStroke stroke : PreviewPaintEvaluator.evaluatePaintStrokes(chart, prepared, index, beat, localYScale))
                            history = history.append(stroke);
                    } else if (playback.advanced) {
                        RenderScene.PaintStroke stroke = PreviewPaintEvaluator.paintSample(chart, prepared, index, beat, localYScale);
                        history = stroke == null ? PaintHistory.empty() : history.append(stroke);
                    }
                    if (history == null) history = PaintHistory.empty();
                    playback.paint.put(index, history);
                    paintHistories.put(index, history);
                }
            }
            if (alphaExtension.hideLineAndNotes && effects.isEmpty() && !paintMode) continue;
            lines.add(new RenderScene.RenderLine(index, source.zOrder,
                    transform.x, transform.y, transform.rotationDegrees,
                    clampAlpha(local[index].rawAlpha), colorRgb, scaleX, scaleY, incline,
                    text, source.texture, paintMode, alphaExtension.cover,
                    gif.enabled, gif.controlled, gif.progress, gif.anchorTimeMs,
                    notes, effects, paintStrokes));
        }
        lines.sort(Comparator.comparingInt((RenderScene.RenderLine line) -> line.zOrder)
                .thenComparingInt(line -> line.sourceIndex));
        return new RenderScene(beat, chartTimeMs, lines,
                PreviewHudEvaluator.buildHudState(chart, prepared, beat, chartTimeMs,
                        trackDurationMs, hudTransforms), paintHistories);
    }

    static int clampAlpha(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.round(Math.max(0.0, Math.min(255.0, value)));
    }

    static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

}
