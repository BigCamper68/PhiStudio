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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Converts Phigros formatVersion 1 and 3 charts into editable RPE documents. */
final class OfficialPhigrosConverter {
    private static final int BEAT_PRECISION = 1_000_000;
    private static final double MIN_DURATION = 1.0 / BEAT_PRECISION;

    private OfficialPhigrosConverter() {
    }

    static ChartDocument convert(JSONObject root) throws PackageException {
        try {
            int version = root.getInt("formatVersion");
            if (version != 1 && version != 3) {
                throw unsupported("Unsupported official Phigros formatVersion " + version
                        + "; only versions 1 and 3 can be converted");
            }
            JSONArray sourceLines = root.getJSONArray("judgeLineList");
            if (sourceLines.length() == 0) {
                throw unsupported("Official Phigros chart has no judge lines");
            }
            JSONObject firstLine = sourceLines.getJSONObject(0);
            double baseBpm = positiveFinite(firstLine.getDouble("bpm"), "line BPM");

            ChartDocument chart = new ChartDocument();
            chart.offsetMs = milliseconds(root.optDouble("offset", 0.0));
            BpmChange bpm = new BpmChange();
            bpm.bpm = baseBpm;
            chart.bpmChanges.add(bpm);

            for (int index = 0; index < sourceLines.length(); index++) {
                JSONObject sourceLine = sourceLines.getJSONObject(index);
                double lineBpm = positiveFinite(sourceLine.getDouble("bpm"), "line BPM");
                double beatScale = baseBpm / lineBpm / 32.0;
                chart.judgeLines.add(convertLine(sourceLine, index, version, beatScale));
            }
            return chart;
        } catch (PackageException exception) {
            throw exception;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new PackageException(PackageException.Code.UNSUPPORTED_CHART_FORMAT,
                    "Official Phigros chart is malformed: " + exception.getMessage(), exception);
        }
    }

    private static JudgeLine convertLine(JSONObject source, int index, int version,
                                         double beatScale) throws JSONException, PackageException {
        JudgeLine line = new JudgeLine();
        line.name = "Line " + index;
        line.notes.clear();
        line.eventLayers.clear();
        EventLayer layer = new EventLayer();
        line.eventLayers.add(layer);

        convertNotes(source.optJSONArray("notesAbove"), line, 1, beatScale);
        convertNotes(source.optJSONArray("notesBelow"), line, 0, beatScale);
        line.sortNotes();

        JSONArray moves = source.optJSONArray("judgeLineMoveEvents");
        if (moves != null) {
            for (int eventIndex = 0; eventIndex < moves.length(); eventIndex++) {
                JSONObject event = moves.getJSONObject(eventIndex);
                double startX;
                double endX;
                double startY;
                double endY;
                if (version == 1) {
                    startX = (Math.round(finite(event.getDouble("start"), "move X") / 1000.0)
                            / 880.0 - 0.5) * 1350.0;
                    endX = (Math.round(finite(event.getDouble("end"), "move X") / 1000.0)
                            / 880.0 - 0.5) * 1350.0;
                    startY = (positiveRemainder(event.getDouble("start"), 1000.0)
                            / 530.0 - 0.5) * 900.0;
                    endY = (positiveRemainder(event.getDouble("end"), 1000.0)
                            / 530.0 - 0.5) * 900.0;
                } else {
                    startX = (finite(event.getDouble("start"), "move X") - 0.5) * 1350.0;
                    endX = (finite(event.getDouble("end"), "move X") - 0.5) * 1350.0;
                    startY = (finite(event.optDouble("start2", 0.5), "move Y") - 0.5) * 900.0;
                    endY = (finite(event.optDouble("end2", 0.5), "move Y") - 0.5) * 900.0;
                }
                addOfficialEvent(layer.events(EventType.MOVE_X), EventType.MOVE_X,
                        event, beatScale, startX, endX);
                addOfficialEvent(layer.events(EventType.MOVE_Y), EventType.MOVE_Y,
                        event, beatScale, startY, endY);
            }
        }
        convertNumericEvents(source.optJSONArray("judgeLineRotateEvents"),
                layer.events(EventType.ROTATE), EventType.ROTATE, beatScale, -1.0);
        convertNumericEvents(source.optJSONArray("judgeLineDisappearEvents"),
                layer.events(EventType.ALPHA), EventType.ALPHA, beatScale, 255.0);

        JSONArray speeds = source.optJSONArray("speedEvents");
        if (speeds != null) {
            for (int eventIndex = 0; eventIndex < speeds.length(); eventIndex++) {
                JSONObject event = speeds.getJSONObject(eventIndex);
                double value = finite(event.getDouble("value"), "speed") * 4.5;
                addOfficialEvent(layer.events(EventType.SPEED), EventType.SPEED,
                        event, beatScale, value, value);
            }
        }
        return line;
    }

    private static void convertNotes(JSONArray source, JudgeLine line, int above,
                                     double beatScale) throws JSONException, PackageException {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject value = source.getJSONObject(index);
            int officialType = value.getInt("type");
            Note note = new Note();
            note.type = noteType(officialType);
            note.above = above;
            double start = finite(value.getDouble("time"), "note time") * beatScale;
            double hold = officialType == 3
                    ? finite(value.optDouble("holdTime", 0.0), "hold time") * beatScale : 0.0;
            if (start < 0.0) continue;
            note.startTime = beat(start);
            note.endTime = beat(officialType == 3
                    ? Math.max(start + MIN_DURATION, start + hold) : start);
            note.positionX = finite(value.optDouble("positionX", 0.0), "note X") * 75.0;
            note.speed = officialType == 3
                    ? 1.0 : finite(value.optDouble("speed", 1.0), "note speed");
            line.notes.add(note);
        }
    }

    private static void convertNumericEvents(JSONArray source, List<LineEvent> target,
                                             EventType type, double beatScale,
                                             double multiplier)
            throws JSONException, PackageException {
        if (source == null) return;
        for (int index = 0; index < source.length(); index++) {
            JSONObject event = source.getJSONObject(index);
            addOfficialEvent(target, type, event, beatScale,
                    finite(event.getDouble("start"), type.label) * multiplier,
                    finite(event.getDouble("end"), type.label) * multiplier);
        }
    }

    private static void addOfficialEvent(List<LineEvent> target, EventType type,
                                         JSONObject source, double beatScale,
                                         double startValue, double endValue)
            throws JSONException, PackageException {
        double originalStart = finite(source.getDouble("startTime"), "event start") * beatScale;
        double originalEnd = finite(source.getDouble("endTime"), "event end") * beatScale;
        if (originalEnd < 0.0) return;
        if (originalStart < 0.0 && originalEnd > originalStart) {
            double progress = (0.0 - originalStart) / (originalEnd - originalStart);
            startValue += (endValue - startValue) * progress;
            originalStart = 0.0;
        }
        double start = Math.max(0.0, originalStart);
        double end = Math.max(start + MIN_DURATION, originalEnd);
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(start);
        event.endTime = beat(end);
        event.start = startValue;
        event.end = endValue;
        addMerged(target, event);
    }

    private static void addMerged(List<LineEvent> target, LineEvent event) {
        if (!target.isEmpty()) {
            LineEvent previous = target.get(target.size() - 1);
            double previousDuration = previous.endTime.toDouble() - previous.startTime.toDouble();
            double eventDuration = event.endTime.toDouble() - event.startTime.toDouble();
            boolean contiguous = close(previous.endTime.toDouble(), event.startTime.toDouble());
            boolean joined = close(previous.end, event.start);
            double previousSlope = previousDuration <= 0.0
                    ? 0.0 : (previous.end - previous.start) / previousDuration;
            double eventSlope = eventDuration <= 0.0
                    ? 0.0 : (event.end - event.start) / eventDuration;
            if (contiguous && joined && close(previousSlope, eventSlope)) {
                previous.endTime = event.endTime;
                previous.end = event.end;
                return;
            }
        }
        target.add(event);
    }

    private static NoteType noteType(int officialType) throws PackageException {
        switch (officialType) {
            case 1: return NoteType.TAP;
            case 2: return NoteType.DRAG;
            case 3: return NoteType.HOLD;
            case 4: return NoteType.FLICK;
            default: throw unsupported("Unknown official Phigros note type " + officialType);
        }
    }

    private static BeatTime beat(double value) {
        return BeatTime.fromDouble(value, BEAT_PRECISION);
    }

    private static double positiveFinite(double value, String label) throws PackageException {
        double result = finite(value, label);
        if (result <= 0.0) throw unsupported(label + " must be positive");
        return result;
    }

    private static double finite(double value, String label) throws PackageException {
        if (!Double.isFinite(value)) throw unsupported(label + " must be finite");
        return value;
    }

    private static double positiveRemainder(double value, double divisor)
            throws PackageException {
        finite(value, "packed move value");
        double remainder = value % divisor;
        return remainder < 0.0 ? remainder + divisor : remainder;
    }

    private static int milliseconds(double seconds) throws PackageException {
        finite(seconds, "chart offset");
        double value = Math.rint(seconds * 1000.0);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw unsupported("Official Phigros chart offset is out of range");
        }
        return (int) value;
    }

    private static boolean close(double left, double right) {
        double scale = Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
        return Math.abs(left - right) <= 1e-8 * scale;
    }

    private static PackageException unsupported(String message) {
        return new PackageException(PackageException.Code.UNSUPPORTED_CHART_FORMAT, message);
    }
}
