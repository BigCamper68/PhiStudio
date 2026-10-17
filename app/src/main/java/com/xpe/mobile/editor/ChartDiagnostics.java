package com.xpe.mobile.editor;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChartDiagnostics {
    public static final int MAX_DISPLAYED_ITEMS = 600;
    private static final int MAX_DISPLAYED_PER_CODE = 100;
    private static final double NOTE_X_LIMIT = 675.0;
    private static final double DEFAULT_VISIBLE_TIME = 999999.0;

    private ChartDiagnostics() {
    }

    public static Report analyze(ChartDocument chart, Double maximumBeat) {
        Collector collector = new Collector();
        if (chart == null) return collector.report();
        analyzeBpm(chart, collector);
        for (int lineIndex = 0; lineIndex < chart.judgeLines.size(); lineIndex++) {
            JudgeLine line = chart.judgeLines.get(lineIndex);
            analyzeNotes(line, lineIndex, maximumBeat, collector);
            analyzeEvents(line, lineIndex, maximumBeat, collector);
        }
        return collector.report();
    }

    private static void analyzeBpm(ChartDocument chart, Collector collector) {
        Set<BeatTime> starts = new HashSet<>();
        for (BpmChange change : chart.bpmChanges) {
            if (!Double.isFinite(change.bpm) || change.bpm <= 0.0) {
                collector.add(ChartDiagnostic.Severity.ERROR, ChartDiagnostic.Category.OTHER,
                        ChartDiagnostic.Code.INVALID_BPM, -1, -1, change.startTime, null, null);
            }
            if (change.startTime.compareTo(BeatTime.zero()) < 0) {
                collector.add(ChartDiagnostic.Severity.ERROR, ChartDiagnostic.Category.OTHER,
                        ChartDiagnostic.Code.NEGATIVE_BPM_START, -1, -1,
                        change.startTime, null, null);
            }
            if (!starts.add(change.startTime)) {
                collector.add(ChartDiagnostic.Severity.ERROR, ChartDiagnostic.Category.OTHER,
                        ChartDiagnostic.Code.DUPLICATE_BPM_START, -1, -1,
                        change.startTime, null, null);
            }
        }
    }

    private static void analyzeNotes(JudgeLine line, int lineIndex, Double maximumBeat,
                                     Collector collector) {
        for (Note note : line.notes) {
            boolean timeOut = note.startTime.compareTo(BeatTime.zero()) < 0
                    || exceedsMaximum(note.startTime, maximumBeat)
                    || (note.type == NoteType.HOLD
                    && (note.endTime.compareTo(BeatTime.zero()) < 0
                    || exceedsMaximum(note.endTime, maximumBeat)));
            if (timeOut) {
                collector.add(ChartDiagnostic.Severity.ERROR, ChartDiagnostic.Category.NOTE,
                        ChartDiagnostic.Code.NOTE_TIME_OUT_OF_RANGE, lineIndex, -1,
                        note.startTime, note, null);
            }
            if (note.type == NoteType.HOLD && note.endTime.compareTo(note.startTime) <= 0) {
                collector.add(ChartDiagnostic.Severity.ERROR, ChartDiagnostic.Category.NOTE,
                        ChartDiagnostic.Code.HOLD_INTERVAL_INVALID, lineIndex, -1,
                        note.startTime, note, null);
            }
            if (!Double.isFinite(note.positionX) || Math.abs(note.positionX) > NOTE_X_LIMIT) {
                collector.add(ChartDiagnostic.Severity.CAUTION, ChartDiagnostic.Category.NOTE,
                        ChartDiagnostic.Code.NOTE_X_TOO_LARGE, lineIndex, -1,
                        note.startTime, note, null);
            }
            if (note.fake) {
                collector.add(ChartDiagnostic.Severity.CAUTION, ChartDiagnostic.Category.NOTE,
                        ChartDiagnostic.Code.FAKE_NOTE, lineIndex, -1,
                        note.startTime, note, null);
            }
            if (Double.compare(note.size, 1.0) != 0) {
                collector.add(ChartDiagnostic.Severity.CAUTION, ChartDiagnostic.Category.NOTE,
                        ChartDiagnostic.Code.CUSTOM_NOTE_SIZE, lineIndex, -1,
                        note.startTime, note, null);
            }
            if (Double.compare(note.visibleTime, DEFAULT_VISIBLE_TIME) != 0) {
                collector.add(ChartDiagnostic.Severity.CAUTION, ChartDiagnostic.Category.NOTE,
                        ChartDiagnostic.Code.CUSTOM_VISIBLE_TIME, lineIndex, -1,
                        note.startTime, note, null);
            }
        }
    }

    private static void analyzeEvents(JudgeLine line, int lineIndex, Double maximumBeat,
                                      Collector collector) {
        for (int layerIndex = 0; layerIndex < line.eventLayers.size(); layerIndex++) {
            EventLayer layer = line.eventLayers.get(layerIndex);
            if (layerIndex >= 4) {
                if (layer.count() > 0) {
                    collector.add(ChartDiagnostic.Severity.CAUTION,
                            ChartDiagnostic.Category.OTHER,
                            ChartDiagnostic.Code.RESERVED_LAYER_NORMAL_EVENT,
                            lineIndex, layerIndex, BeatTime.zero(), null, null);
                }
                continue;
            }
            for (EventType type : EventType.values()) {
                List<LineEvent> values = new ArrayList<>(layer.events(type));
                values.sort(Comparator.comparing((LineEvent event) -> event.startTime)
                        .thenComparing(event -> event.endTime));
                LineEvent previousValid = null;
                for (LineEvent event : values) {
                    boolean validInterval = event.endTime.compareTo(event.startTime) > 0;
                    boolean timeOut = event.startTime.compareTo(BeatTime.zero()) < 0
                            || event.endTime.compareTo(BeatTime.zero()) < 0
                            || exceedsMaximum(event.startTime, maximumBeat)
                            || exceedsMaximum(event.endTime, maximumBeat);
                    if (timeOut) {
                        collector.add(ChartDiagnostic.Severity.ERROR,
                                ChartDiagnostic.Category.EVENT,
                                ChartDiagnostic.Code.EVENT_TIME_OUT_OF_RANGE,
                                lineIndex, layerIndex, event.startTime, null, event);
                    }
                    if (!validInterval) {
                        collector.add(ChartDiagnostic.Severity.ERROR,
                                ChartDiagnostic.Category.EVENT,
                                ChartDiagnostic.Code.EVENT_INTERVAL_INVALID,
                                lineIndex, layerIndex, event.startTime, null, event);
                    }
                    if (validInterval && previousValid != null
                            && event.startTime.compareTo(previousValid.endTime) < 0) {
                        collector.add(ChartDiagnostic.Severity.ERROR,
                                ChartDiagnostic.Category.EVENT,
                                ChartDiagnostic.Code.EVENT_OVERLAP,
                                lineIndex, layerIndex, event.startTime, null, event);
                    }
                    if (validInterval && (previousValid == null
                            || event.endTime.compareTo(previousValid.endTime) > 0)) {
                        previousValid = event;
                    }
                    if (type == EventType.ALPHA
                            && (!Double.isFinite(event.start) || !Double.isFinite(event.end)
                            || event.start < 0.0 || event.start > 255.0
                            || event.end < 0.0 || event.end > 255.0)) {
                        collector.add(ChartDiagnostic.Severity.WARNING,
                                ChartDiagnostic.Category.EVENT,
                                ChartDiagnostic.Code.ALPHA_OUT_OF_RANGE,
                                lineIndex, layerIndex, event.startTime, null, event);
                    }
                }
            }
        }
    }

    private static boolean exceedsMaximum(BeatTime beat, Double maximumBeat) {
        return maximumBeat != null && Double.isFinite(maximumBeat)
                && beat.toDouble() > maximumBeat;
    }

    public static final class Report {
        public final List<ChartDiagnostic> items;
        public final int totalCount;
        public final int errorCount;
        public final int warningCount;
        public final int cautionCount;

        private Report(List<ChartDiagnostic> items, int totalCount,
                       int errorCount, int warningCount, int cautionCount) {
            this.items = items;
            this.totalCount = totalCount;
            this.errorCount = errorCount;
            this.warningCount = warningCount;
            this.cautionCount = cautionCount;
        }

        public boolean isTruncated() {
            return items.size() < totalCount;
        }
    }

    private static final class Collector {
        private final List<ChartDiagnostic> items = new ArrayList<>();
        private final EnumMap<ChartDiagnostic.Code, Integer> storedByCode =
                new EnumMap<>(ChartDiagnostic.Code.class);
        private int total;
        private int errors;
        private int warnings;
        private int cautions;

        void add(ChartDiagnostic.Severity severity, ChartDiagnostic.Category category,
                 ChartDiagnostic.Code code, int lineIndex, int layerIndex, BeatTime beat,
                 Note note, LineEvent event) {
            total++;
            switch (severity) {
                case ERROR: errors++; break;
                case WARNING: warnings++; break;
                case CAUTION: cautions++; break;
            }
            int storedForCode = storedByCode.getOrDefault(code, 0);
            if (items.size() >= MAX_DISPLAYED_ITEMS
                    || storedForCode >= MAX_DISPLAYED_PER_CODE) return;
            items.add(new ChartDiagnostic(severity, category, code, lineIndex, layerIndex,
                    beat == null ? BeatTime.zero() : beat, note, event));
            storedByCode.put(code, storedForCode + 1);
        }

        Report report() {
            items.sort(Comparator
                    .comparingInt((ChartDiagnostic item) -> item.severity.ordinal())
                    .thenComparingInt(item -> item.lineIndex)
                    .thenComparingInt(item -> item.layerIndex)
                    .thenComparing(item -> item.beat)
                    .thenComparing(item -> item.code.ordinal()));
            return new Report(Collections.unmodifiableList(new ArrayList<>(items)), total,
                    errors, warnings, cautions);
        }
    }
}
