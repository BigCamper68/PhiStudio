package com.xpe.mobile.preview;

import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteControlEvents;
import com.xpe.mobile.model.NoteType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;

/** Deterministically evaluates the supported RPE chart model at a supplied beat. */
public final class ChartEvaluator {
    private static final double PIXELS_PER_SPEED_SECOND = 100.0 / 0.83175;
    private static final double RPE_WIDTH = 1350.0;
    private static final long HIT_EFFECT_DURATION_MS = 500L;
    private static final long HOLD_EFFECT_INTERVAL_MS = 150L;
    private static final int MAX_ACTIVE_HOLD_EFFECTS = 32;
    private static final long PAINT_SAMPLE_INTERVAL_MS = 33L;
    private static final int MAX_PAINT_SAMPLES_PER_LINE = 2400;
    private static final double NOTE_RENDER_DISTANCE_LIMIT = 6000.0;
    private static final Map<ChartDocument, PreparedChart> PREPARED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChartEvaluator() {
    }

    /**
     * Builds immutable playback indexes before the first preview frame.
     *
     * <p>The caller must exclusively own {@code chart} for the duration of this call; the chart
     * model is intentionally UI-thread confined and is not safe to mutate concurrently.
     */
    public static void prepare(ChartDocument chart, boolean useRpe170Speed) {
        if (chart != null) prepared(chart, useRpe170Speed);
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
        if (chart == null) throw new IllegalArgumentException("chart is required");
        double beat = finiteOr(requestedBeat, 0.0);
        beat = Math.max(0.0, beat);
        long chartTimeMs = chart.beatToMillis(beat);
        PreparedChart prepared = prepared(chart, useRpe170Speed);
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
                    preparedLine.valueAt(EventType.MOVE_X, beat),
                    preparedLine.valueAt(EventType.MOVE_Y, beat),
                    -preparedLine.valueAt(EventType.ROTATE, beat),
                    preparedLine.valueAt(EventType.ALPHA, beat));
        }
        for (int index = 0; index < count; index++) {
            resolveLine(chart, index, local, resolved, resolveState);
        }

        List<RenderScene.RenderLine> lines = new ArrayList<>(count);
        Map<AttachedUiElement, RenderScene.HudTransform> hudTransforms =
                new EnumMap<>(AttachedUiElement.class);
        for (int index = 0; index < count; index++) {
            JudgeLine source = chart.judgeLines.get(index);
            ResolvedLine transform = resolved[index];
            ExtendedLineEvents extended = source.extended == null
                    ? new ExtendedLineEvents() : source.extended;
            if (source.attachUi != null) {
                int hudColor = extended.colorEvents.isEmpty() ? -1
                        : ExtendedLineEvents.colorValueAt(
                        extended.colorEvents, beat, 0xFFFFFF);
                double hudScaleX = lineScaleAt(source, extended, true, beat);
                double hudScaleY = lineScaleAt(source, extended, false, beat);
                hudTransforms.put(source.attachUi, new RenderScene.HudTransform(
                        index, transform.x, transform.y, transform.rotationDegrees,
                        clampAlpha(local[index].rawAlpha), hudColor,
                        hudScaleX, hudScaleY));
                continue;
            }
            AlphaExtension alphaExtension = AlphaExtension.from(
                    local[index].rawAlpha, source.cover);
            if (alphaExtension.hideLineAndNotes) continue;
            SpeedProfile speed = prepared.lines[index].speed;
            double currentDistance = speed.distanceAt(beat);
            List<RenderScene.RenderNote> notes = new ArrayList<>();
            List<RenderScene.HitEffect> effects = new ArrayList<>();
            for (Note note : source.notes) {
                if (note == null || note.type == null || note.startTime == null) continue;
                double startBeat = Math.max(0.0, note.startTime.toDouble());
                long startTimeMs = chart.beatToMillis(startBeat);
                boolean hold = note.type == NoteType.HOLD;
                double endBeat = hold && note.endTime != null
                        ? Math.max(0.0, note.endTime.toDouble()) : startBeat;
                if (hold && endBeat <= startBeat) continue;

                double noteSpeed = finiteOr(note.speed, 1.0);
                double yOffset = finiteOr(note.yOffset, 0.0);
                double baseStartDistance = speed.distanceAt(startBeat) - currentDistance;
                double baseEndDistance = hold
                        ? speed.distanceAt(endBeat) - currentDistance : baseStartDistance;
                double controlHeight = baseStartDistance + yOffset;
                NoteControlEvents controls = source.noteControls == null
                        ? new NoteControlEvents() : source.noteControls;
                double positionControl = finiteOr(NoteControlEvents.valueAt(
                        controls.position, controlHeight, 1.0), 1.0);
                double sizeControl = finiteOr(NoteControlEvents.valueAt(
                        controls.size, controlHeight, 1.0), 1.0);
                double alphaControl = finiteOr(NoteControlEvents.valueAt(
                        controls.alpha, controlHeight, 1.0), 1.0);
                double yControl = finiteOr(NoteControlEvents.valueAt(
                        controls.y, controlHeight, 1.0), 1.0);
                double renderedX = finiteOr(note.positionX, 0.0)
                        * (hold ? 1.0 : positionControl);
                double renderedOffset = yOffset * noteSpeed;
                double startDistance = baseStartDistance * noteSpeed * yControl
                        + renderedOffset;
                double endDistance = baseEndDistance * noteSpeed * yControl
                        + renderedOffset;
                if (hold && beat >= startBeat) startDistance = renderedOffset;

                appendHitEffects(chart, index, note, controls, startBeat, endBeat,
                        chartTimeMs, noteSpeed, yOffset, effects);

                if (!hold && Math.abs(startDistance) > NOTE_RENDER_DISTANCE_LIMIT
                        || hold && startDistance > NOTE_RENDER_DISTANCE_LIMIT
                        && endDistance > NOTE_RENDER_DISTANCE_LIMIT
                        || hold && startDistance < -NOTE_RENDER_DISTANCE_LIMIT
                        && endDistance < -NOTE_RENDER_DISTANCE_LIMIT) continue;

                if (beat < startBeat - alphaExtension.appearBeforeBeats) continue;
                if ((!hold && note.fake && alphaExtension.cover && beat >= startBeat)
                        || (!hold && !note.fake && beat > startBeat)
                        || (hold && beat >= endBeat)) continue;

                double secondsUntilHit = (startTimeMs - chartTimeMs) / 1000.0;
                double visibleTime = finiteOr(note.visibleTime, Double.POSITIVE_INFINITY);
                if (secondsUntilHit > Math.max(0.0, visibleTime)) continue;

                if (alphaExtension.cover && beat < startBeat
                        && startDistance < 0.0) continue;

                boolean multiHit = highlightSimultaneousNotes
                        && simultaneous.getOrDefault(note.startTime, 0) > 1;
                double renderedSize = Math.abs(finiteOr(note.size, 1.0) * sizeControl);
                int renderedAlpha = clampAlpha(note.alpha * alphaControl);
                if (renderedSize <= 1.0e-6 || renderedAlpha <= 0) continue;
                notes.add(new RenderScene.RenderNote(note.type,
                        renderedX, startDistance, endDistance,
                        note.above == 1, renderedSize,
                        renderedAlpha, note.fake, multiHit,
                        !hold || beat < startBeat,
                        note.hasTint ? note.tintRgb : 0xFFFFFF));
            }

            int colorRgb = extended.colorEvents.isEmpty() ? -1
                    : ExtendedLineEvents.colorValueAt(
                    extended.colorEvents, beat, 0xFFFFFF);
            double incline = finiteOr(ExtendedLineEvents.numericValueAt(
                    extended.inclineEvents, beat, 0.0), 0.0);
            boolean defaultTexture = source.texture == null
                    || source.texture.trim().isEmpty()
                    || "line.png".equalsIgnoreCase(source.texture.trim());
            boolean paintMode = defaultTexture && !extended.paintEvents.isEmpty();
            String text = !defaultTexture || paintMode || extended.textEvents.isEmpty() ? null
                    : ExtendedLineEvents.textValueAt(extended.textEvents, beat, "");
            double scaleX = lineScaleAt(source, extended, true, beat);
            double scaleY = lineScaleAt(source, extended, false, beat);
            GifState gif = !defaultTexture && !extended.gifEvents.isEmpty()
                    ? evaluateGifState(chart, extended.gifEvents, beat)
                    : GifState.disabled();
            List<RenderScene.PaintStroke> paintStrokes = paintMode
                    ? evaluatePaintStrokes(chart, index, beat) : new ArrayList<>();
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
                buildHudState(chart, prepared, beat, chartTimeMs,
                        trackDurationMs, hudTransforms));
    }

    private static RenderScene.HudState buildHudState(
            ChartDocument chart, PreparedChart prepared, double beat,
            long chartTimeMs, long trackDurationMs,
            Map<AttachedUiElement, RenderScene.HudTransform> transforms) {
        int total = prepared.judgementBeats.length;
        int passed = upperBound(prepared.judgementBeats, beat);
        double completion = total == 0 ? 0.0 : passed / (double) total;
        int score = (int) Math.round(Math.max(0.0, Math.min(1.0, completion))
                * 1_000_000.0);
        double progress = trackDurationMs > 0L
                ? chartTimeMs / (double) trackDurationMs
                : prepared.finalBeat <= 0.0 ? 0.0 : beat / prepared.finalBeat;
        progress = Math.max(0.0, Math.min(1.0, progress));
        return new RenderScene.HudState(chart.name, chart.level, passed,
                score, progress, transforms);
    }

    private static int upperBound(double[] values, double target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] <= target) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void appendHitEffects(
            ChartDocument chart, int lineIndex, Note note, NoteControlEvents controls,
            double startBeat, double endBeat, long chartTimeMs,
            double noteSpeed, double yOffset, List<RenderScene.HitEffect> target) {
        if (note.fake) return;
        if (note.type != NoteType.HOLD) {
            long effectTimeMs = chart.beatToMillis(startBeat);
            appendHitEffectAt(chart, lineIndex, note, controls, startBeat,
                    effectTimeMs, chartTimeMs, noteSpeed, yOffset, target);
            return;
        }
        long startTimeMs = chart.beatToMillis(startBeat);
        long endTimeMs = chart.beatToMillis(endBeat);
        long pulseLimitMs = Math.min(chartTimeMs, endTimeMs);
        if (pulseLimitMs < startTimeMs) return;
        long pulseIndex = (pulseLimitMs - startTimeMs) / HOLD_EFFECT_INTERVAL_MS;
        long pulseTimeMs = startTimeMs + pulseIndex * HOLD_EFFECT_INTERVAL_MS;
        for (int count = 0; count < MAX_ACTIVE_HOLD_EFFECTS
                && pulseTimeMs >= startTimeMs; count++) {
            long ageMs = chartTimeMs - pulseTimeMs;
            if (ageMs >= HIT_EFFECT_DURATION_MS) break;
            if (ageMs >= 0L) {
                double pulseBeat = chart.millisToBeat(pulseTimeMs);
                appendHitEffectAt(chart, lineIndex, note, controls, pulseBeat,
                        pulseTimeMs, chartTimeMs, noteSpeed, yOffset, target);
            }
            pulseTimeMs -= HOLD_EFFECT_INTERVAL_MS;
        }
    }

    private static void appendHitEffectAt(
            ChartDocument chart, int lineIndex, Note note, NoteControlEvents controls,
            double effectBeat, long effectTimeMs, long chartTimeMs,
            double noteSpeed, double yOffset,
            List<RenderScene.HitEffect> target) {
        long ageMs = chartTimeMs - effectTimeMs;
        if (ageMs < 0L || ageMs >= HIT_EFFECT_DURATION_MS) return;
        double positionControl = note.type == NoteType.HOLD ? 1.0
                : finiteOr(NoteControlEvents.valueAt(
                controls.position, yOffset, 1.0), 1.0);
        double localX = finiteOr(note.positionX, 0.0) * positionControl;
        double localY = yOffset * noteSpeed * (note.above == 1 ? 1.0 : -1.0);
        ResolvedLine transform = resolveSingleLineAt(chart, lineIndex, effectBeat,
                new boolean[chart.judgeLines.size()]);
        double radians = Math.toRadians(transform.rotationDegrees);
        double worldX = transform.x + Math.cos(radians) * localX
                - Math.sin(radians) * localY;
        double worldY = transform.y + Math.sin(radians) * localX
                + Math.cos(radians) * localY;
        int seed = 31 * (31 * (lineIndex + 1)
                + (note.startTime == null ? 0 : note.startTime.hashCode()))
                + Double.hashCode(effectBeat);
        target.add(new RenderScene.HitEffect(worldX, worldY,
                ageMs / (double) HIT_EFFECT_DURATION_MS,
                note.hasHitEffectTint ? note.hitEffectTintRgb : 0xFEFFA9, seed));
    }

    private static double maximumRelevantBeat(JudgeLine line, double currentBeat) {
        double maximum = Math.max(1.0, currentBeat);
        for (Note note : line.notes) {
            if (note == null) continue;
            if (note.startTime != null) maximum = Math.max(maximum, note.startTime.toDouble());
            if (note.type == NoteType.HOLD && note.endTime != null) {
                maximum = Math.max(maximum, note.endTime.toDouble());
            }
        }
        return Math.max(0.0, maximum);
    }

    private static Map<BeatTime, Integer> countSimultaneousNotes(ChartDocument chart) {
        Map<BeatTime, Integer> result = new HashMap<>();
        for (JudgeLine line : chart.judgeLines) {
            for (Note note : line.notes) {
                if (note == null || note.type == null || note.startTime == null) continue;
                result.put(note.startTime,
                        result.getOrDefault(note.startTime, 0) + 1);
            }
        }
        return result;
    }

    private static PreparedChart prepared(ChartDocument chart, boolean useRpe170Speed) {
        synchronized (PREPARED) {
            PreparedChart value = PREPARED.get(chart);
            long structureStamp = structureStamp(chart);
            if (value == null || value.revision != chart.revision()
                    || value.structureStamp != structureStamp
                    || value.useRpe170Speed != useRpe170Speed
                    || value.lines.length != chart.judgeLines.size()) {
                value = PreparedChart.build(chart, useRpe170Speed, structureStamp);
                PREPARED.put(chart, value);
            }
            return value;
        }
    }

    private static long structureStamp(ChartDocument chart) {
        long result = 17L;
        for (BpmChange change : chart.bpmChanges) {
            result = result * 31L + System.identityHashCode(change);
            result = result * 31L + Double.doubleToLongBits(change.bpm);
            result = result * 31L + (change.startTime == null ? 0 : change.startTime.hashCode());
        }
        for (JudgeLine line : chart.judgeLines) {
            result = result * 31L + System.identityHashCode(line);
            result = stampList(result, line.notes);
            for (EventLayer layer : line.eventLayers) {
                result = result * 31L + System.identityHashCode(layer);
                for (EventType type : EventType.values()) {
                    List<LineEvent> events = layer.events(type);
                    result = stampList(result, events);
                    if (type == EventType.SPEED) {
                        for (LineEvent event : events) {
                            if (event == null) continue;
                            result = result * 31L + Double.doubleToLongBits(event.start);
                            result = result * 31L + Double.doubleToLongBits(event.end);
                            result = result * 31L + (event.startTime == null
                                    ? 0 : event.startTime.hashCode());
                            result = result * 31L + (event.endTime == null
                                    ? 0 : event.endTime.hashCode());
                        }
                    }
                }
            }
        }
        return result;
    }

    private static long stampList(long result, List<?> values) {
        result = result * 31L + values.size();
        if (!values.isEmpty()) {
            result = result * 31L + System.identityHashCode(values.get(0));
            result = result * 31L + System.identityHashCode(values.get(values.size() - 1));
        }
        return result;
    }

    /** Converts RPE's kind-dependent extended scale values into preview multipliers. */
    private static double lineScaleAt(JudgeLine line, ExtendedLineEvents extended,
                                      boolean horizontal, double beat) {
        List<ExtendedLineEvents.NumericEvent> events = horizontal
                ? extended.scaleXEvents : extended.scaleYEvents;
        if (events.isEmpty()) return 1.0;
        double raw = finiteOr(ExtendedLineEvents.numericValueAt(events, beat, 1.0), 1.0);
        boolean defaultTexture = line.texture == null || line.texture.trim().isEmpty()
                || "line.png".equalsIgnoreCase(line.texture.trim());
        if (!defaultTexture) return raw;
        boolean ordinaryOrPaint = line.attachUi == null && extended.textEvents.isEmpty();
        return horizontal && ordinaryOrPaint ? raw * 0.5 : raw;
    }

    /** Adds the most recent active contribution from each event layer. */
    static double layeredValueAt(JudgeLine line, EventType type, double beat) {
        double result = 0.0;
        boolean affected = false;
        for (EventLayer layer : line.eventLayers) {
            LineEvent latest = null;
            for (LineEvent event : layer.events(type)) {
                if (event == null || event.startTime == null) continue;
                if (event.startTime.toDouble() <= beat
                        && (latest == null
                        || event.startTime.compareTo(latest.startTime) >= 0)) {
                    latest = event;
                }
            }
            if (latest != null) {
                result += finiteOr(latest.valueAt(beat), 0.0);
                affected = true;
            }
        }
        return affected ? result : LineEvent.defaultValue(type);
    }

    private static final class PreparedChart {
        final long revision;
        final long structureStamp;
        final boolean useRpe170Speed;
        final PreparedLine[] lines;
        final Map<BeatTime, Integer> simultaneous;
        final double[] judgementBeats;
        final double finalBeat;

        PreparedChart(long revision, long structureStamp, boolean useRpe170Speed,
                      PreparedLine[] lines,
                      Map<BeatTime, Integer> simultaneous, double[] judgementBeats,
                      double finalBeat) {
            this.revision = revision;
            this.structureStamp = structureStamp;
            this.useRpe170Speed = useRpe170Speed;
            this.lines = lines;
            this.simultaneous = simultaneous;
            this.judgementBeats = judgementBeats;
            this.finalBeat = finalBeat;
        }

        static PreparedChart build(ChartDocument chart, boolean useRpe170Speed,
                                   long structureStamp) {
            PreparedLine[] lines = new PreparedLine[chart.judgeLines.size()];
            double maximumBeat = 1.0;
            List<Double> judgements = new ArrayList<>();
            Map<BeatTime, Integer> simultaneous = countSimultaneousNotes(chart);
            for (JudgeLine line : chart.judgeLines) {
                maximumBeat = Math.max(maximumBeat, maximumRelevantBeat(line, 0.0));
                if (line == null || line.attachUi != null) continue;
                for (Note note : line.notes) {
                    if (note == null || note.fake || note.startTime == null) continue;
                    double start = Math.max(0.0, note.startTime.toDouble());
                    double end = note.type == NoteType.HOLD && note.endTime != null
                            ? Math.max(start, note.endTime.toDouble()) : start;
                    judgements.add(note.type == NoteType.HOLD ? end : start);
                    maximumBeat = Math.max(maximumBeat, end);
                }
            }
            maximumBeat = Math.max(1_000_000.0, maximumBeat + 1024.0);
            for (int index = 0; index < lines.length; index++) {
                JudgeLine line = chart.judgeLines.get(index);
                lines[index] = PreparedLine.build(
                        chart, line, maximumBeat, useRpe170Speed);
            }
            double[] judgementBeats = new double[judgements.size()];
            double finalBeat = 0.0;
            for (int index = 0; index < judgementBeats.length; index++) {
                judgementBeats[index] = judgements.get(index);
                finalBeat = Math.max(finalBeat, judgementBeats[index]);
            }
            Arrays.sort(judgementBeats);
            return new PreparedChart(chart.revision(), structureStamp,
                    useRpe170Speed, lines,
                    simultaneous, judgementBeats, finalBeat);
        }
    }

    private static final class PreparedLine {
        final Map<EventType, LineEvent[][]> events = new EnumMap<>(EventType.class);
        final SpeedProfile speed;

        PreparedLine(SpeedProfile speed) {
            this.speed = speed;
        }

        static PreparedLine build(ChartDocument chart, JudgeLine line,
                                  double maximumBeat, boolean useRpe170Speed) {
            PreparedLine result = new PreparedLine(SpeedProfile.build(
                    chart, line, maximumBeat, useRpe170Speed));
            for (EventType type : EventType.values()) {
                LineEvent[][] layers = new LineEvent[line.eventLayers.size()][];
                for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
                    List<LineEvent> source = line.eventLayers.get(layerIndex).events(type);
                    layers[layerIndex] = source.toArray(new LineEvent[0]);
                    Arrays.sort(layers[layerIndex], Comparator.comparing(
                            event -> event == null || event.startTime == null
                                    ? BeatTime.zero() : event.startTime));
                }
                result.events.put(type, layers);
            }
            return result;
        }

        double valueAt(EventType type, double beat) {
            double result = 0.0;
            boolean affected = false;
            LineEvent[][] layers = events.get(type);
            if (layers == null) return LineEvent.defaultValue(type);
            for (LineEvent[] layer : layers) {
                int low = 0;
                int high = layer.length;
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    LineEvent event = layer[middle];
                    double start = event == null || event.startTime == null
                            ? Double.NEGATIVE_INFINITY : event.startTime.toDouble();
                    if (start <= beat) low = middle + 1;
                    else high = middle;
                }
                if (low > 0) {
                    LineEvent latest = layer[low - 1];
                    if (latest != null) {
                        result += finiteOr(latest.valueAt(beat), 0.0);
                        affected = true;
                    }
                }
            }
            return affected ? result : LineEvent.defaultValue(type);
        }
    }

    private static void resolveLine(ChartDocument chart, int index, LocalLine[] local,
                                    ResolvedLine[] resolved, int[] state) {
        if (state[index] == 2) return;
        LocalLine own = local[index];
        if (state[index] == 1) {
            resolved[index] = new ResolvedLine(own.x, own.y, own.rotationDegrees);
            state[index] = 2;
            return;
        }
        state[index] = 1;
        JudgeLine line = chart.judgeLines.get(index);
        int parentIndex = line.father;
        if (parentIndex < 0 || parentIndex >= chart.judgeLines.size()
                || parentIndex == index || state[parentIndex] == 1) {
            resolved[index] = new ResolvedLine(own.x, own.y, own.rotationDegrees);
        } else {
            resolveLine(chart, parentIndex, local, resolved, state);
            ResolvedLine parent = resolved[parentIndex];
            double radians = Math.toRadians(parent.rotationDegrees);
            double rotatedX = own.x * Math.cos(radians) - own.y * Math.sin(radians);
            double rotatedY = own.x * Math.sin(radians) + own.y * Math.cos(radians);
            resolved[index] = new ResolvedLine(parent.x + rotatedX, parent.y + rotatedY,
                    own.rotationDegrees + (line.rotateWithFather
                            ? parent.rotationDegrees : 0.0));
        }
        state[index] = 2;
    }

    private static List<RenderScene.PaintStroke> evaluatePaintStrokes(
            ChartDocument chart, int lineIndex, double beat) {
        JudgeLine source = chart.judgeLines.get(lineIndex);
        ExtendedLineEvents extended = source.extended;
        List<RenderScene.PaintStroke> result = new ArrayList<>();
        if (extended == null || extended.paintEvents.isEmpty() || beat < 0.0) return result;

        double firstBeat = Math.max(0.0, ExtendedLineEvents.firstStartBeat(
                extended.paintEvents, beat));
        if (firstBeat > beat) return result;
        long firstMs = chart.beatToMillis(firstBeat);
        long currentMs = chart.beatToMillis(beat);
        long spanMs = Math.max(0L, currentMs - firstMs);
        long intervalMs = Math.max(PAINT_SAMPLE_INTERVAL_MS,
                (spanMs + MAX_PAINT_SAMPLES_PER_LINE - 2L)
                        / Math.max(1L, MAX_PAINT_SAMPLES_PER_LINE - 1L));
        long sampleMs = firstMs;
        int samples = 0;
        while (sampleMs <= currentMs && samples < MAX_PAINT_SAMPLES_PER_LINE) {
            appendPaintSample(chart, lineIndex, sampleMs, result);
            samples++;
            if (sampleMs > Long.MAX_VALUE - intervalMs) break;
            sampleMs += intervalMs;
        }
        if (spanMs > 0L && sampleMs - intervalMs != currentMs
                && samples < MAX_PAINT_SAMPLES_PER_LINE) {
            appendPaintSample(chart, lineIndex, currentMs, result);
        }
        return result;
    }

    private static GifState evaluateGifState(
            ChartDocument chart, List<ExtendedLineEvents.NumericEvent> events,
            double beat) {
        ExtendedLineEvents.NumericEvent latest = null;
        for (ExtendedLineEvents.NumericEvent event : events) {
            if (event == null || event.startTime == null) continue;
            if (event.startTime.toDouble() <= beat
                    && (latest == null
                    || event.startTime.compareTo(latest.startTime) >= 0)) {
                latest = event;
            }
        }
        if (latest == null) return new GifState(true, false, 0.0, 0L);
        double endBeat = latest.endTime == null
                ? latest.startTime.toDouble() : latest.endTime.toDouble();
        if (endBeat > latest.startTime.toDouble() && beat < endBeat) {
            return new GifState(true, true, latest.valueAt(beat), 0L);
        }
        return new GifState(true, false, latest.end,
                chart.beatToMillis(Math.max(0.0, endBeat)));
    }

    private static void appendPaintSample(ChartDocument chart, int lineIndex, long timeMs,
                                          List<RenderScene.PaintStroke> target) {
        double sampleBeat = chart.millisToBeat(Math.max(0L, timeMs));
        JudgeLine source = chart.judgeLines.get(lineIndex);
        ExtendedLineEvents extended = source.extended;
        double radius = ExtendedLineEvents.numericValueAt(
                extended.paintEvents, sampleBeat, 0.0);
        if (!Double.isFinite(radius)) return;
        if (radius <= 0.0) {
            target.clear();
            return;
        }
        ResolvedLine transform = resolveSingleLineAt(
                chart, lineIndex, sampleBeat, new boolean[chart.judgeLines.size()]);
        int color = extended.colorEvents.isEmpty() ? -1
                : ExtendedLineEvents.colorValueAt(
                extended.colorEvents, sampleBeat, 0xFFFFFF);
        int alpha = clampAlpha(layeredValueAt(
                source, EventType.ALPHA, sampleBeat) * 2.55);
        double scaleX = lineScaleAt(source, extended, true, sampleBeat);
        double scaleY = lineScaleAt(source, extended, false, sampleBeat);
        target.add(new RenderScene.PaintStroke(transform.x, transform.y,
                Math.abs(radius), transform.rotationDegrees,
                scaleX, scaleY, color, alpha));
    }

    private static ResolvedLine resolveSingleLineAt(ChartDocument chart, int index,
                                                    double beat, boolean[] visiting) {
        JudgeLine line = chart.judgeLines.get(index);
        ResolvedLine own = new ResolvedLine(
                layeredValueAt(line, EventType.MOVE_X, beat),
                layeredValueAt(line, EventType.MOVE_Y, beat),
                -layeredValueAt(line, EventType.ROTATE, beat));
        int parentIndex = line.father;
        if (parentIndex < 0 || parentIndex >= chart.judgeLines.size()
                || parentIndex == index || visiting[index]) return own;
        visiting[index] = true;
        ResolvedLine parent = resolveSingleLineAt(chart, parentIndex, beat, visiting);
        visiting[index] = false;
        double radians = Math.toRadians(parent.rotationDegrees);
        double rotatedX = own.x * Math.cos(radians) - own.y * Math.sin(radians);
        double rotatedY = own.x * Math.sin(radians) + own.y * Math.cos(radians);
        return new ResolvedLine(parent.x + rotatedX, parent.y + rotatedY,
                own.rotationDegrees + (line.rotateWithFather
                        ? parent.rotationDegrees : 0.0));
    }

    private static int clampAlpha(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.round(Math.max(0.0, Math.min(255.0, value)));
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static final class LocalLine {
        final double x;
        final double y;
        final double rotationDegrees;
        final double rawAlpha;

        LocalLine(double x, double y, double rotationDegrees, double rawAlpha) {
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
            this.rawAlpha = rawAlpha;
        }
    }

    private static final class AlphaExtension {
        final boolean hideLineAndNotes;
        final boolean cover;
        final double appearBeforeBeats;

        AlphaExtension(boolean hideLineAndNotes, boolean cover,
                       double appearBeforeBeats) {
            this.hideLineAndNotes = hideLineAndNotes;
            this.cover = cover;
            this.appearBeforeBeats = appearBeforeBeats;
        }

        static AlphaExtension from(double rawAlpha, boolean sourceCover) {
            if (!Double.isFinite(rawAlpha) || rawAlpha >= 0.0) {
                return new AlphaExtension(false, sourceCover,
                        Double.POSITIVE_INFINITY);
            }
            // RPE charts use Phira's default ChartSettings, where the PEC-only negative-alpha
            // extension is disabled. Any negative value therefore skips the complete judge line,
            // including its notes; it is not merely a transparent line texture.
            return new AlphaExtension(true, sourceCover, Double.POSITIVE_INFINITY);
        }
    }

    private static final class GifState {
        final boolean enabled;
        final boolean controlled;
        final double progress;
        final long anchorTimeMs;

        GifState(boolean enabled, boolean controlled, double progress,
                 long anchorTimeMs) {
            this.enabled = enabled;
            this.controlled = controlled;
            this.progress = progress;
            this.anchorTimeMs = anchorTimeMs;
        }

        static GifState disabled() {
            return new GifState(false, false, 0.0, 0L);
        }
    }

    private static final class ResolvedLine {
        final double x;
        final double y;
        final double rotationDegrees;

        ResolvedLine(double x, double y, double rotationDegrees) {
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
        }
    }

    private static final class SpeedProfile {
        final List<SpeedInterval> intervals;

        SpeedProfile(List<SpeedInterval> intervals) {
            this.intervals = intervals;
        }

        static SpeedProfile build(ChartDocument chart, JudgeLine line, double maximumBeat,
                                  boolean useRpe170Speed) {
            TreeSet<Double> points = new TreeSet<>();
            points.add(0.0);
            points.add(maximumBeat);
            for (BpmChange change : chart.bpmChanges) {
                addPoint(points, change.startTime == null ? Double.NaN
                        : change.startTime.toDouble(), maximumBeat);
            }
            for (EventLayer layer : line.eventLayers) {
                for (LineEvent event : layer.events(EventType.SPEED)) {
                    if (event == null) continue;
                    addPoint(points, event.startTime == null ? Double.NaN
                            : event.startTime.toDouble(), maximumBeat);
                    addPoint(points, event.endTime == null ? Double.NaN
                            : event.endTime.toDouble(), maximumBeat);
                }
            }

            List<Double> ordered = new ArrayList<>(points);
            List<SpeedInterval> intervals = new ArrayList<>();
            double cumulative = 0.0;
            for (int index = 0; index + 1 < ordered.size(); index++) {
                double start = ordered.get(index);
                double end = ordered.get(index + 1);
                if (end <= start) continue;
                List<LineEvent> speedContributions = activeSpeedEvents(
                        line, (start + end) / 2.0);
                double bpm = chart.bpmAt((start + end) / 2.0);
                if (!Double.isFinite(bpm) || bpm <= 0.0) bpm = 120.0;
                double integratedSpeed = integratedSpeed(
                        speedContributions, start, end,
                        chart.rpeVersion, useRpe170Speed);
                double distance = integratedSpeed * 60.0 / bpm
                        * PIXELS_PER_SPEED_SECOND;
                intervals.add(new SpeedInterval(start, end, speedContributions,
                        chart.rpeVersion, useRpe170Speed, bpm,
                        cumulative, cumulative + distance));
                cumulative += distance;
            }
            return new SpeedProfile(intervals);
        }

        double distanceAt(double beat) {
            if (beat <= 0.0 || intervals.isEmpty()) return 0.0;
            int low = 0;
            int high = intervals.size() - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                SpeedInterval interval = intervals.get(middle);
                if (beat < interval.startBeat) high = middle - 1;
                else if (beat > interval.endBeat) low = middle + 1;
                else return interval.distanceAt(beat);
            }
            if (low >= intervals.size()) return intervals.get(intervals.size() - 1).endDistance;
            return intervals.get(Math.max(0, low)).startDistance;
        }

        private static void addPoint(TreeSet<Double> points, double value, double maximumBeat) {
            if (Double.isFinite(value) && value > 0.0 && value < maximumBeat) points.add(value);
        }

        private static List<LineEvent> activeSpeedEvents(JudgeLine line, double beat) {
            List<LineEvent> result = new ArrayList<>();
            for (EventLayer layer : line.eventLayers) {
                LineEvent latest = null;
                for (LineEvent event : layer.events(EventType.SPEED)) {
                    if (event == null || event.startTime == null) continue;
                    if (event.startTime.toDouble() <= beat
                            && (latest == null
                            || event.startTime.compareTo(latest.startTime) >= 0)) {
                        latest = event;
                    }
                }
                if (latest != null) result.add(latest);
            }
            return result;
        }

        private static double integratedSpeed(List<LineEvent> contributions,
                                              double start, double end,
                                              int rpeVersion,
                                              boolean useRpe170Speed) {
            if (contributions.isEmpty()) return 0.0;
            double result = 0.0;
            for (LineEvent event : contributions) {
                result += event.integratedRenderSpeed(
                        start, end, rpeVersion, useRpe170Speed);
            }
            return Double.isFinite(result) ? result : 0.0;
        }
    }

    private static final class SpeedInterval {
        final double startBeat;
        final double endBeat;
        final List<LineEvent> contributions;
        final int rpeVersion;
        final boolean useRpe170Speed;
        final double bpm;
        final double startDistance;
        final double endDistance;

        SpeedInterval(double startBeat, double endBeat, List<LineEvent> contributions,
                      int rpeVersion, boolean useRpe170Speed, double bpm,
                      double startDistance, double endDistance) {
            this.startBeat = startBeat;
            this.endBeat = endBeat;
            this.contributions = contributions;
            this.rpeVersion = rpeVersion;
            this.useRpe170Speed = useRpe170Speed;
            this.bpm = bpm;
            this.startDistance = startDistance;
            this.endDistance = endDistance;
        }

        double distanceAt(double beat) {
            if (beat <= startBeat) return startDistance;
            if (beat >= endBeat) return endDistance;
            double integratedSpeed = SpeedProfile.integratedSpeed(
                    contributions, startBeat, beat, rpeVersion, useRpe170Speed);
            return startDistance + integratedSpeed * 60.0 / bpm
                    * PIXELS_PER_SPEED_SECOND;
        }
    }
}
