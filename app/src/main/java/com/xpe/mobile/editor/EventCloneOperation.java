package com.xpe.mobile.editor;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reversible Event Clone implementation from the Re:PhiEdit batch workflow. */
public final class EventCloneOperation {
    public static final int MAX_TARGET_LINES = 256;
    public static final int MAX_CLONED_EVENTS = 4096;

    public enum Error {
        NONE,
        EMPTY_SELECTION,
        TARGET_NOT_FOUND,
        RESERVED_LAYER,
        INVALID_LINE_SEQUENCE,
        INVALID_TIME_INCREMENT,
        INVALID_PROFILE,
        TOO_MANY_EVENTS,
        INVALID_RESULT,
        EVENT_OVERLAP
    }

    public static final class Spec {
        public int[] lineSequence;
        public BeatTime timeIncrement = BeatTime.zero();
        public BatchValueTransform.Spec xProfile;
        public BatchValueTransform.Spec yProfile;
        public BatchValueTransform.Spec rotateProfile;
        public BatchValueTransform.Spec alphaProfile;
        public boolean keepSource;
    }

    public static final class Result {
        public final Error error;
        public final EditHistory.Command command;
        public final List<LineEvent> events;

        private Result(Error error, EditHistory.Command command, List<LineEvent> events) {
            this.error = error;
            this.command = command;
            this.events = Collections.unmodifiableList(events);
        }

        static Result error(Error error) {
            return new Result(error, null, Collections.emptyList());
        }
    }

    private static final class Placement {
        final JudgeLine line;
        final int layerIndex;
        final LineEvent event;

        Placement(JudgeLine line, int layerIndex, LineEvent event) {
            this.line = line;
            this.layerIndex = layerIndex;
            this.event = event;
        }
    }

    private EventCloneOperation() {
    }

    public static Result prepare(ChartDocument chart, JudgeLine sourceLine,
                                 int layerIndex, List<LineEvent> selection, Spec spec) {
        List<LineEvent> sources = uniqueEvents(selection);
        if (sources.isEmpty()) return Result.error(Error.EMPTY_SELECTION);
        if (chart == null || sourceLine == null || spec == null
                || !chart.judgeLines.contains(sourceLine)) {
            return Result.error(Error.TARGET_NOT_FOUND);
        }
        if (layerIndex < 0 || layerIndex > 3
                || layerIndex >= sourceLine.eventLayers.size()) {
            return Result.error(Error.RESERVED_LAYER);
        }
        EventLayer sourceLayer = sourceLine.eventLayers.get(layerIndex);
        for (LineEvent source : sources) {
            if (!sourceLayer.events(source.type).contains(source)) {
                return Result.error(Error.TARGET_NOT_FOUND);
            }
        }
        if (!validLineSequence(chart, spec.lineSequence)) {
            return Result.error(Error.INVALID_LINE_SEQUENCE);
        }
        if (spec.timeIncrement == null) return Result.error(Error.INVALID_TIME_INCREMENT);
        if (!BatchValueTransform.isValid(spec.xProfile)
                || !BatchValueTransform.isValid(spec.yProfile)
                || !BatchValueTransform.isValid(spec.rotateProfile)
                || !BatchValueTransform.isValid(spec.alphaProfile)) {
            return Result.error(Error.INVALID_PROFILE);
        }
        long cloneCount = (long) sources.size() * spec.lineSequence.length;
        if (cloneCount > MAX_CLONED_EVENTS) return Result.error(Error.TOO_MANY_EVENTS);

        int targetCount = spec.lineSequence.length;
        double[] xOffsets;
        double[] yOffsets;
        double[] rotateOffsets;
        double[] alphaOffsets;
        try {
            xOffsets = BatchValueTransform.values(spec.xProfile, targetCount);
            yOffsets = BatchValueTransform.values(spec.yProfile, targetCount);
            rotateOffsets = BatchValueTransform.values(spec.rotateProfile, targetCount);
            alphaOffsets = BatchValueTransform.values(spec.alphaProfile, targetCount);
        } catch (IllegalArgumentException exception) {
            return Result.error(Error.INVALID_PROFILE);
        }

        List<Placement> placements = new ArrayList<>((int) cloneCount);
        List<LineEvent> clones = new ArrayList<>((int) cloneCount);
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            JudgeLine targetLine = chart.judgeLines.get(spec.lineSequence[targetIndex]);
            BeatTime shift;
            try {
                shift = multiply(spec.timeIncrement, targetIndex);
            } catch (ArithmeticException exception) {
                return Result.error(Error.INVALID_TIME_INCREMENT);
            }
            for (LineEvent source : sources) {
                LineEvent clone = source.copy();
                try {
                    clone.startTime = clone.startTime.plus(shift);
                    clone.endTime = clone.endTime.plus(shift);
                } catch (ArithmeticException exception) {
                    return Result.error(Error.INVALID_TIME_INCREMENT);
                }
                double offset = offsetFor(clone.type, targetIndex,
                        xOffsets, yOffsets, rotateOffsets, alphaOffsets);
                if (clone.type != EventType.SPEED) {
                    clone.start += offset;
                    clone.end += offset;
                }
                if (PropertyValidator.validate(clone) != PropertyValidator.Error.NONE) {
                    return Result.error(Error.INVALID_RESULT);
                }
                placements.add(new Placement(targetLine, layerIndex, clone));
                clones.add(clone);
            }
        }

        if (overlapsExisting(sourceLine, sourceLayer, sources, placements,
                layerIndex, spec.keepSource)) {
            return Result.error(Error.EVENT_OVERLAP);
        }
        EditHistory.Command command = command(sourceLine, sourceLayer, sources,
                placements, spec.keepSource);
        return new Result(Error.NONE, command, clones);
    }

    public static int[] parseLineSequence(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("line sequence is required");
        }
        String[] parts = text.trim().split("[\\s,;]+", -1);
        if (parts.length == 0 || parts.length > MAX_TARGET_LINES) {
            throw new IllegalArgumentException("line sequence is too long");
        }
        int[] result = new int[parts.length];
        Set<Integer> seen = new HashSet<>();
        try {
            for (int index = 0; index < parts.length; index++) {
                result[index] = Integer.parseInt(parts[index]);
                if (!seen.add(result[index])) {
                    throw new IllegalArgumentException("line sequence contains duplicates");
                }
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid line sequence", exception);
        }
        return result;
    }

    private static boolean validLineSequence(ChartDocument chart, int[] sequence) {
        if (sequence == null || sequence.length == 0 || sequence.length > MAX_TARGET_LINES) {
            return false;
        }
        Set<Integer> seen = new HashSet<>();
        for (int lineIndex : sequence) {
            if (lineIndex < 0 || lineIndex >= chart.judgeLines.size() || !seen.add(lineIndex)) {
                return false;
            }
        }
        return true;
    }

    private static double offsetFor(EventType type, int index, double[] x, double[] y,
                                    double[] rotate, double[] alpha) {
        switch (type) {
            case MOVE_X: return x[index];
            case MOVE_Y: return y[index];
            case ROTATE: return rotate[index];
            case ALPHA: return alpha[index];
            default: return 0.0;
        }
    }

    private static BeatTime multiply(BeatTime value, int multiplier) {
        BeatTime result = BeatTime.zero();
        for (int index = 0; index < multiplier; index++) result = result.plus(value);
        return result;
    }

    private static boolean overlapsExisting(JudgeLine sourceLine, EventLayer sourceLayer,
                                            List<LineEvent> sources,
                                            List<Placement> placements, int layerIndex,
                                            boolean keepSource) {
        Map<JudgeLine, Map<EventType, List<LineEvent>>> generated = new IdentityHashMap<>();
        for (Placement placement : placements) {
            Map<EventType, List<LineEvent>> byType = generated.computeIfAbsent(
                    placement.line, ignored -> new EnumMap<>(EventType.class));
            List<LineEvent> sameType = byType.computeIfAbsent(
                    placement.event.type, ignored -> new ArrayList<>());
            for (LineEvent other : sameType) {
                if (overlaps(placement.event, other)) return true;
            }
            EventLayer existingLayer = layerIndex < placement.line.eventLayers.size()
                    ? placement.line.eventLayers.get(layerIndex) : null;
            if (existingLayer != null) {
                for (LineEvent existing : existingLayer.events(placement.event.type)) {
                    boolean removedSource = !keepSource
                            && placement.line == sourceLine
                            && existingLayer == sourceLayer
                            && sources.contains(existing);
                    if (!removedSource && overlaps(placement.event, existing)) return true;
                }
            }
            sameType.add(placement.event);
        }
        return false;
    }

    private static boolean overlaps(LineEvent first, LineEvent second) {
        return first.startTime.compareTo(second.endTime) < 0
                && first.endTime.compareTo(second.startTime) > 0;
    }

    private static EditHistory.Command command(JudgeLine sourceLine, EventLayer sourceLayer,
                                               List<LineEvent> sources,
                                               List<Placement> placements,
                                               boolean keepSource) {
        List<LineEvent> sourceCopy = new ArrayList<>(sources);
        Map<JudgeLine, Integer> originalLayerCounts = new IdentityHashMap<>();
        for (Placement placement : placements) {
            originalLayerCounts.putIfAbsent(placement.line, placement.line.eventLayers.size());
        }
        return new EditHistory.Command() {
            @Override
            public void apply() {
                if (!keepSource) {
                    for (LineEvent source : sourceCopy) {
                        sourceLayer.events(source.type).remove(source);
                    }
                }
                for (Placement placement : placements) {
                    List<LineEvent> target = placement.line.layer(
                            placement.layerIndex).events(placement.event.type);
                    if (!target.contains(placement.event)) target.add(placement.event);
                }
                sortAffected(sourceLine, sourceLayer, placements);
            }

            @Override
            public void revert() {
                for (Placement placement : placements) {
                    if (placement.layerIndex < placement.line.eventLayers.size()) {
                        placement.line.eventLayers.get(placement.layerIndex)
                                .events(placement.event.type).remove(placement.event);
                    }
                }
                if (!keepSource) {
                    for (LineEvent source : sourceCopy) {
                        List<LineEvent> target = sourceLayer.events(source.type);
                        if (!target.contains(source)) target.add(source);
                    }
                }
                sortAffected(sourceLine, sourceLayer, placements);
                for (Map.Entry<JudgeLine, Integer> entry : originalLayerCounts.entrySet()) {
                    while (entry.getKey().eventLayers.size() > entry.getValue()) {
                        entry.getKey().eventLayers.remove(entry.getKey().eventLayers.size() - 1);
                    }
                }
            }
        };
    }

    private static void sortAffected(JudgeLine sourceLine, EventLayer sourceLayer,
                                     List<Placement> placements) {
        for (EventType type : EventType.values()) {
            sourceLayer.events(type).sort(Comparator.comparing(event -> event.startTime));
        }
        for (Placement placement : placements) {
            if (placement.layerIndex < placement.line.eventLayers.size()) {
                placement.line.eventLayers.get(placement.layerIndex)
                        .events(placement.event.type)
                        .sort(Comparator.comparing(event -> event.startTime));
            }
        }
    }

    private static List<LineEvent> uniqueEvents(List<LineEvent> values) {
        List<LineEvent> result = new ArrayList<>();
        Set<LineEvent> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (values != null) {
            for (LineEvent value : values) if (value != null && seen.add(value)) result.add(value);
        }
        return result;
    }
}
