package com.xpe.mobile.packageio;

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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts legacy PhiEdit/PEC text charts, regardless of their file extension. */
final class LegacyPecConverter {
    private static final int BEAT_PRECISION = 1_000_000;
    private static final double MIN_DURATION = 1.0 / BEAT_PRECISION;

    private LegacyPecConverter() {
    }

    static ChartDocument convert(String source) throws PackageException {
        if (source == null) throw malformed(0, "PEC source is missing");
        String[] lines = source.split("\\r?\\n", -1);
        int offsetLine = firstContentLine(lines);
        if (offsetLine < 0) throw malformed(0, "PEC source is empty");
        String offsetText = stripBom(lines[offsetLine].trim());

        ChartDocument chart = new ChartDocument();
        chart.offsetMs = offsetMilliseconds(number(offsetText, offsetLine + 1, "offset"));
        List<LineBuilder> builders = new ArrayList<>();
        Note lastNote = null;
        boolean sawBpm = false;

        for (int index = offsetLine + 1; index < lines.length; index++) {
            String text = lines[index].trim();
            if (text.isEmpty() || text.startsWith("//")) continue;
            String[] values = text.split("\\s+");
            String command = values[0].toLowerCase(Locale.ROOT);
            int lineNumber = index + 1;
            try {
                switch (command) {
                    case "bp": {
                        requireCount(values, 3, lineNumber);
                        BpmChange bpm = new BpmChange();
                        bpm.startTime = beat(number(values[1], lineNumber, "BPM beat"));
                        bpm.bpm = positive(number(values[2], lineNumber, "BPM"),
                                lineNumber, "BPM");
                        chart.bpmChanges.add(bpm);
                        sawBpm = true;
                        break;
                    }
                    case "n1":
                    case "n2":
                    case "n3":
                    case "n4": {
                        int minimum = command.equals("n2") ? 7 : 6;
                        requireAtLeast(values, minimum, lineNumber);
                        int lineId = nonNegativeInt(values[1], lineNumber, "line ID");
                        LineBuilder builder = line(builders, lineId);
                        Note note = new Note();
                        note.type = noteType(command);
                        double start = number(values[2], lineNumber, "note beat");
                        int cursor = 3;
                        double end = start;
                        if (command.equals("n2")) {
                            end = number(values[cursor++], lineNumber, "hold end beat");
                        }
                        note.startTime = beat(start);
                        note.endTime = beat(note.type == NoteType.HOLD
                                ? Math.max(start + MIN_DURATION, end) : start);
                        note.positionX = number(values[cursor++], lineNumber, "note X")
                                * 675.0 / 1024.0;
                        note.above = nonNegativeInt(values[cursor++], lineNumber, "above") == 1
                                ? 1 : 0;
                        note.fake = binary(values[cursor++], lineNumber, "fake") == 1;
                        while (cursor < values.length) {
                            String marker = values[cursor++];
                            if (cursor >= values.length) {
                                throw malformed(lineNumber, "Missing value after " + marker);
                            }
                            if (marker.equals("#")) {
                                note.speed = number(values[cursor++], lineNumber, "note speed");
                            } else if (marker.equals("&")) {
                                note.size = number(values[cursor++], lineNumber, "note size");
                            } else {
                                throw malformed(lineNumber, "Unexpected note suffix " + marker);
                            }
                        }
                        builder.line.notes.add(note);
                        lastNote = note;
                        break;
                    }
                    case "#":
                        requireCount(values, 2, lineNumber);
                        if (lastNote == null) throw malformed(lineNumber, "No note before #");
                        lastNote.speed = number(values[1], lineNumber, "note speed");
                        break;
                    case "&":
                        requireCount(values, 2, lineNumber);
                        if (lastNote == null) throw malformed(lineNumber, "No note before &");
                        lastNote.size = number(values[1], lineNumber, "note size");
                        break;
                    case "cv": {
                        requireCount(values, 4, lineNumber);
                        LineBuilder builder = line(builders,
                                nonNegativeInt(values[1], lineNumber, "line ID"));
                        builder.speed.add(new SpeedPoint(
                                number(values[2], lineNumber, "speed beat"),
                                number(values[3], lineNumber, "speed") * 9.0 / 14.0));
                        break;
                    }
                    case "cp":
                    case "cm": {
                        boolean transition = command.equals("cm");
                        requireCount(values, transition ? 7 : 5, lineNumber);
                        LineBuilder builder = line(builders,
                                nonNegativeInt(values[1], lineNumber, "line ID"));
                        double start = number(values[2], lineNumber, "move start beat");
                        int cursor = 3;
                        double end = transition
                                ? number(values[cursor++], lineNumber, "move end beat") : start;
                        double x = (number(values[cursor++], lineNumber, "move X") - 1024.0)
                                * 675.0 / 1024.0;
                        double y = (number(values[cursor++], lineNumber, "move Y") - 700.0)
                                * 450.0 / 700.0;
                        int easing = transition
                                ? easing(values[cursor], lineNumber) : 1;
                        builder.command(EventType.MOVE_X, start, end, x, easing, transition);
                        builder.command(EventType.MOVE_Y, start, end, y, easing, transition);
                        break;
                    }
                    case "cd":
                    case "cr": {
                        boolean transition = command.equals("cr");
                        requireCount(values, transition ? 6 : 4, lineNumber);
                        LineBuilder builder = line(builders,
                                nonNegativeInt(values[1], lineNumber, "line ID"));
                        double start = number(values[2], lineNumber, "rotation start beat");
                        int cursor = 3;
                        double end = transition
                                ? number(values[cursor++], lineNumber, "rotation end beat") : start;
                        double target = number(values[cursor++], lineNumber, "rotation");
                        int easing = transition ? easing(values[cursor], lineNumber) : 1;
                        builder.command(EventType.ROTATE, start, end,
                                target, easing, transition);
                        break;
                    }
                    case "ca":
                    case "cf": {
                        boolean transition = command.equals("cf");
                        requireCount(values, transition ? 5 : 4, lineNumber);
                        LineBuilder builder = line(builders,
                                nonNegativeInt(values[1], lineNumber, "line ID"));
                        double start = number(values[2], lineNumber, "alpha start beat");
                        int cursor = 3;
                        double end = transition
                                ? number(values[cursor++], lineNumber, "alpha end beat") : start;
                        double target = number(values[cursor], lineNumber, "alpha");
                        builder.command(EventType.ALPHA, start, end,
                                target, 1, transition);
                        break;
                    }
                    default:
                        throw malformed(lineNumber, "Unsupported PEC command " + values[0]);
                }
            } catch (PackageException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw malformed(lineNumber, "Invalid " + command + " command");
            }
        }

        if (!sawBpm) {
            BpmChange bpm = new BpmChange();
            chart.bpmChanges.add(bpm);
        }
        chart.sortBpm();
        if (builders.isEmpty()) line(builders, 0);
        for (LineBuilder builder : builders) {
            builder.finish();
            chart.judgeLines.add(builder.line);
        }
        return chart;
    }

    private static final class LineBuilder {
        final JudgeLine line = new JudgeLine();
        final EventLayer layer = new EventLayer();
        final Map<EventType, List<PecCommand>> commands = new EnumMap<>(EventType.class);
        final List<SpeedPoint> speed = new ArrayList<>();
        int sequence;

        LineBuilder(int id) {
            line.name = "Line " + id;
            line.notes.clear();
            line.eventLayers.clear();
            line.eventLayers.add(layer);
            for (EventType type : EventType.values()) {
                commands.put(type, new ArrayList<>());
            }
        }

        void command(EventType type, double start, double end, double target,
                     int easing, boolean transition) {
            commands.get(type).add(new PecCommand(
                    start, end, target, easing, transition, sequence++));
        }

        void finish() {
            line.sortNotes();
            for (EventType type : new EventType[]{EventType.MOVE_X, EventType.MOVE_Y,
                    EventType.ROTATE, EventType.ALPHA}) {
                buildCommands(commands.get(type), layer.events(type), type);
            }
            buildSpeed(speed, layer.events(EventType.SPEED));
        }
    }

    private static void buildCommands(List<PecCommand> source, List<LineEvent> target,
                                      EventType type) {
        source.sort(Comparator.comparingDouble((PecCommand value) -> value.start)
                .thenComparingInt(value -> value.sequence));
        double state = LineEvent.defaultValue(type);
        double occupiedUntil = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < source.size(); index++) {
            PecCommand command = source.get(index);
            double start = Math.max(0.0, command.start);
            if (!command.transition) {
                state = command.target;
                boolean followedAtSameBeat = index + 1 < source.size()
                        && close(source.get(index + 1).start, command.start);
                if (!followedAtSameBeat) {
                    LineEvent point = event(type, start, start + MIN_DURATION,
                            state, state, 1);
                    addMerged(target, point);
                    occupiedUntil = Math.max(occupiedUntil, point.endTime.toDouble());
                }
                continue;
            }

            start = Math.max(start, occupiedUntil);
            double end = Math.max(start + MIN_DURATION, command.end);
            LineEvent transition = event(type, start, end,
                    state, command.target, command.easing);
            addMerged(target, transition);
            state = command.target;
            occupiedUntil = end;
        }
    }

    private static void buildSpeed(List<SpeedPoint> source, List<LineEvent> target) {
        source.sort(Comparator.comparingDouble(value -> value.beat));
        List<SpeedPoint> unique = new ArrayList<>();
        for (SpeedPoint point : source) {
            double time = Math.max(0.0, point.beat);
            SpeedPoint normalized = new SpeedPoint(time, point.value);
            if (!unique.isEmpty() && close(unique.get(unique.size() - 1).beat, time)) {
                unique.set(unique.size() - 1, normalized);
            } else {
                unique.add(normalized);
            }
        }
        if (unique.isEmpty() || unique.get(0).beat > 0.0) {
            unique.add(0, new SpeedPoint(0.0, 0.0));
        }
        for (int index = 0; index < unique.size(); index++) {
            SpeedPoint point = unique.get(index);
            double end = index + 1 < unique.size()
                    ? unique.get(index + 1).beat : point.beat + MIN_DURATION;
            addMerged(target, event(EventType.SPEED, point.beat,
                    Math.max(point.beat + MIN_DURATION, end), point.value, point.value, 1));
        }
    }

    private static LineEvent event(EventType type, double start, double end,
                                   double startValue, double endValue, int easing) {
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(start);
        event.endTime = beat(end);
        event.start = startValue;
        event.end = endValue;
        event.easingType = easing;
        return event;
    }

    private static void addMerged(List<LineEvent> target, LineEvent event) {
        if (!target.isEmpty()) {
            LineEvent previous = target.get(target.size() - 1);
            double previousDuration = previous.endTime.toDouble() - previous.startTime.toDouble();
            double eventDuration = event.endTime.toDouble() - event.startTime.toDouble();
            double previousSlope = previousDuration <= 0.0
                    ? 0.0 : (previous.end - previous.start) / previousDuration;
            double eventSlope = eventDuration <= 0.0
                    ? 0.0 : (event.end - event.start) / eventDuration;
            if (previous.easingType == event.easingType
                    && close(previous.endTime.toDouble(), event.startTime.toDouble())
                    && close(previous.end, event.start)
                    && (previous.easingType == 1 && close(previousSlope, eventSlope))) {
                previous.endTime = event.endTime;
                previous.end = event.end;
                return;
            }
        }
        target.add(event);
    }

    private static final class PecCommand {
        final double start;
        final double end;
        final double target;
        final int easing;
        final boolean transition;
        final int sequence;

        PecCommand(double start, double end, double target, int easing,
                   boolean transition, int sequence) {
            this.start = start;
            this.end = end;
            this.target = target;
            this.easing = easing;
            this.transition = transition;
            this.sequence = sequence;
        }
    }

    private static final class SpeedPoint {
        final double beat;
        final double value;

        SpeedPoint(double beat, double value) {
            this.beat = beat;
            this.value = value;
        }
    }

    private static LineBuilder line(List<LineBuilder> lines, int id) {
        while (lines.size() <= id) lines.add(new LineBuilder(lines.size()));
        return lines.get(id);
    }

    private static NoteType noteType(String command) {
        switch (command) {
            case "n2": return NoteType.HOLD;
            case "n3": return NoteType.FLICK;
            case "n4": return NoteType.DRAG;
            default: return NoteType.TAP;
        }
    }

    private static int easing(String value, int line) throws PackageException {
        int parsed = nonNegativeInt(value, line, "easing");
        if (parsed > 29) return 1;
        return Math.max(1, parsed);
    }

    private static int binary(String value, int line, String label) throws PackageException {
        int parsed = nonNegativeInt(value, line, label);
        if (parsed != 0 && parsed != 1) throw malformed(line, label + " must be 0 or 1");
        return parsed;
    }

    private static int nonNegativeInt(String value, int line, String label)
            throws PackageException {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw malformed(line, label + " must not be negative");
            return parsed;
        } catch (NumberFormatException exception) {
            throw malformed(line, label + " must be an integer");
        }
    }

    private static double number(String value, int line, String label)
            throws PackageException {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw malformed(line, label + " must be finite");
            return parsed;
        } catch (NumberFormatException exception) {
            throw malformed(line, label + " must be numeric");
        }
    }

    private static double positive(double value, int line, String label)
            throws PackageException {
        if (value <= 0.0) throw malformed(line, label + " must be positive");
        return value;
    }

    private static void requireCount(String[] values, int count, int line)
            throws PackageException {
        if (values.length != count) {
            throw malformed(line, "Expected " + count + " fields, got " + values.length);
        }
    }

    private static void requireAtLeast(String[] values, int count, int line)
            throws PackageException {
        if (values.length < count) {
            throw malformed(line, "Expected at least " + count
                    + " fields, got " + values.length);
        }
    }

    private static int firstContentLine(String[] lines) {
        for (int index = 0; index < lines.length; index++) {
            if (!stripBom(lines[index].trim()).isEmpty()) return index;
        }
        return -1;
    }

    private static String stripBom(String value) {
        return !value.isEmpty() && value.charAt(0) == '\ufeff'
                ? value.substring(1) : value;
    }

    private static BeatTime beat(double value) {
        return BeatTime.fromDouble(value, BEAT_PRECISION);
    }

    private static int offsetMilliseconds(double value) throws PackageException {
        double adjusted = Math.rint(value - 150.0);
        if (adjusted < Integer.MIN_VALUE || adjusted > Integer.MAX_VALUE) {
            throw malformed(1, "PEC offset is out of range");
        }
        return (int) adjusted;
    }

    private static boolean close(double left, double right) {
        double scale = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
        return Math.abs(left - right) <= 1e-8 * scale;
    }

    private static PackageException malformed(int line, String message) {
        String prefix = line > 0 ? "PEC line " + line + ": " : "PEC: ";
        return new PackageException(PackageException.Code.UNSUPPORTED_CHART_FORMAT,
                prefix + message);
    }
}
