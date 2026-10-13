package com.xpe.mobile.preview;

import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** PreviewChartIndex responsibilities extracted from ChartEvaluator. */
final class PreviewChartIndex {
    private static final EventType[] EVENT_TYPES = EventType.values();
    private static final Map<ChartDocument, PreparedChart> PREPARED =
            Collections.synchronizedMap(new WeakHashMap<>());
    static double maximumRelevantBeat(JudgeLine line, double currentBeat) {
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

    static Map<BeatTime, Integer> countSimultaneousNotes(ChartDocument chart) {
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

    static PreparedChart prepared(ChartDocument chart, boolean useRpe170Speed) {
        synchronized (PREPARED) {
            PreparedChart value = PREPARED.get(chart);
            long structureStamp = PreviewChartIndex.structureStamp(chart);
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

    static long structureStamp(ChartDocument chart) {
        long result = 17L;
        for (BpmChange change : chart.bpmChanges) {
            result = result * 31L + System.identityHashCode(change);
            result = result * 31L + Double.doubleToLongBits(change.bpm);
            result = result * 31L + (change.startTime == null ? 0 : change.startTime.hashCode());
        }
        for (JudgeLine line : chart.judgeLines) {
            result = result * 31L + System.identityHashCode(line);
            result = PreviewChartIndex.stampList(result, line.notes);
            for (EventLayer layer : line.eventLayers) {
                result = result * 31L + System.identityHashCode(layer);
                for (EventType type : EVENT_TYPES) {
                    List<LineEvent> events = layer.events(type);
                    result = PreviewChartIndex.stampList(result, events);
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

    static long stampList(long result, List<?> values) {
        result = result * 31L + values.size();
        if (!values.isEmpty()) {
            result = result * 31L + System.identityHashCode(values.get(0));
            result = result * 31L + System.identityHashCode(values.get(values.size() - 1));
        }
        return result;
    }

    static final class PreparedChart {
        final PrprTime time;
        final long revision;
        final long structureStamp;
        final boolean useRpe170Speed;
        final PreparedLine[] lines;
        final Map<BeatTime, Integer> simultaneous;
        final double[] judgementBeats;
        final double finalBeat;

        PreparedChart(PrprTime time, long revision, long structureStamp, boolean useRpe170Speed,
                      PreparedLine[] lines,
                      Map<BeatTime, Integer> simultaneous, double[] judgementBeats,
                      double finalBeat) {
            this.time = time;
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
            PrprTime time = new PrprTime(chart);
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
                        chart, line, maximumBeat, useRpe170Speed, time);
            }
            double[] judgementBeats = new double[judgements.size()];
            double finalBeat = 0.0;
            for (int index = 0; index < judgementBeats.length; index++) {
                judgementBeats[index] = judgements.get(index);
                finalBeat = Math.max(finalBeat, judgementBeats[index]);
            }
            Arrays.sort(judgementBeats);
            return new PreparedChart(time, chart.revision(), structureStamp,
                    useRpe170Speed, lines,
                    simultaneous, judgementBeats, finalBeat);
        }
    }

    static final class PreparedLine {
        final Map<EventType, LineEvent[][]> events = new EnumMap<>(EventType.class);
        final Map<EventType, double[][]> activation = new EnumMap<>(EventType.class);
        final Map<EventType, EventSample[]> samples = new EnumMap<>(EventType.class);
        final PrprSpeed speed;
        final PrprTime time;
        final List<PreparedNote> notes = new ArrayList<>();

        PreparedLine(PrprSpeed speed, PrprTime time) {
            this.speed = speed;
            this.time = time;
        }

        static PreparedLine build(ChartDocument chart, JudgeLine line,
                                  double maximumBeat, boolean useRpe170Speed, PrprTime time) {
            PreparedLine result = new PreparedLine(new PrprSpeed(
                    chart, line, maximumBeat, useRpe170Speed, time), time);
            for (Note note : line.notes) {
                if (note == null || note.type == null || note.startTime == null) continue;
                PreparedNote cached = new PreparedNote(chart, result.speed, note, time);
                if (note.type != NoteType.HOLD || cached.endBeat > cached.startBeat) {
                    result.notes.add(cached);
                }
            }
            for (EventType type : EVENT_TYPES) {
                LineEvent[][] layers = new LineEvent[line.eventLayers.size()][];
                for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
                    List<LineEvent> source = line.eventLayers.get(layerIndex).events(type);
                    layers[layerIndex] = source.toArray(new LineEvent[0]);
                    Arrays.sort(layers[layerIndex], Comparator.comparing(
                            event -> event == null || event.startTime == null
                                    ? BeatTime.zero() : event.startTime));
                }
                result.events.put(type, layers);
                double[][] activation = new double[layers.length][];
                for (int li = 0; li < layers.length; li++) {
                    LineEvent[] layer = layers[li];
                    activation[li] = new double[layer.length];
                    for (int ei = 0; ei < layer.length; ei++) {
                        activation[li][ei] = layer[ei].startTime.toDouble();
                        if (ei > 0) activation[li][ei] = Math.max(activation[li][ei],
                                Math.max(activation[li][ei - 1], layer[ei - 1].endTime.toDouble()));
                    }
                }
                result.activation.put(type, activation);
                EventSample[] samples = new EventSample[layers.length];
                for (int li = 0; li < samples.length; li++) samples[li] = new EventSample();
                result.samples.put(type, samples);
            }
            return result;
        }

        double valueAt(EventType type, double beat) {
            return valueAt(type, beat, time.seconds(beat));
        }

        double valueAt(EventType type, double beat, double seconds) {
            double result = 0.0;
            LineEvent[][] layers = events.get(type);
            if (layers == null) return LineEvent.defaultValue(type);
            double[][] starts = activation.get(type);
            EventSample[] cached = samples.get(type);
            for (int layerIndex = 0; layerIndex < layers.length; layerIndex++) {
                LineEvent[] layer = layers[layerIndex];
                int low = 0;
                int high = layer.length;
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    double start = starts[layerIndex][middle];
                    if (start <= beat) low = middle + 1;
                    else high = middle;
                }
                if (low > 0) {
                    LineEvent latest = layer[low - 1];
                    if (latest != null) {
                        result += cached[layerIndex].value(latest, time, seconds);
                    }
                } else if (layer.length > 0
                        && (EventLayer.isSpatial(type) || type == EventType.ALPHA)) {
                    result += cached[layerIndex].value(layer[0], time, seconds);
                }
            }
            return result;
        }
    }

    /** One cache per channel/layer, rather than allocating timing wrappers for all authored events. */
    private static final class EventSample {
        private LineEvent event;
        private BeatTime startTime, endTime;
        private double startSeconds, endSeconds;

        double value(LineEvent next, PrprTime time, double seconds) {
            // BeatTime is immutable. Keep live scalar/easing fields and refresh replaced endpoints,
            // including callers that replace a timestamp before marking the chart edited.
            if (event != next || startTime != next.startTime || endTime != next.endTime) {
                event = next; startTime = next.startTime; endTime = next.endTime;
                startSeconds = time.seconds(startTime.toDouble());
                endSeconds = time.seconds(endTime.toDouble());
            }
            return PrprEvents.value(next, startSeconds, endSeconds, seconds);
        }
    }

    static final class PreparedNote {
        final Note note;
        final double startBeat, endBeat, startDistance, endDistance, startSeconds;
        final long startMs, endMs;

        PreparedNote(ChartDocument chart, PrprSpeed speed, Note note, PrprTime time) {
            this.note = note;
            startBeat = Math.max(0, note.startTime.toDouble());
            endBeat = note.type == NoteType.HOLD && note.endTime != null
                    ? Math.max(0, note.endTime.toDouble()) : startBeat;
            startMs = chart.beatToMillis(startBeat);
            startSeconds = time.seconds(startBeat);
            endMs = endBeat == startBeat ? startMs : chart.beatToMillis(endBeat);
            startDistance = speed.distanceAt(startBeat);
            endDistance = endBeat == startBeat ? startDistance : speed.distanceAt(endBeat);
        }
    }
}
