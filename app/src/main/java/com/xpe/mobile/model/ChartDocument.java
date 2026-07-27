package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.List;

public final class ChartDocument {
    public String name = "Untitled";
    public String composer = "";
    public String charter = "";
    public String level = "";
    public String id = "";
    public String song = "";
    public String background = "";
    public int offsetMs;
    public int rpeVersion = 123;
    public final List<BpmChange> bpmChanges = new ArrayList<>();
    public final List<JudgeLine> judgeLines = new ArrayList<>();
    private JSONObject rawRoot;
    private JSONObject rawMeta;
    private long revision;

    public static ChartDocument fromJson(String json) throws JSONException {
        return fromParsedJson(new JSONObject(json));
    }

    public static ChartDocument fromParsedJson(JSONObject root) throws JSONException {
        if (root == null) throw new JSONException("Chart JSON root is missing");
        ChartDocument chart = new ChartDocument();
        chart.rawRoot = RawJson.unknownFields(root, "META", "BPMList", "judgeLineList");

        JSONObject meta = root.optJSONObject("META");
        if (meta != null) {
            chart.rawMeta = RawJson.unknownFields(meta,
                    "RPEVersion", "background", "charter", "composer", "id",
                    "level", "name", "offset", "song");
            chart.name = meta.optString("name", "Untitled");
            chart.composer = meta.optString("composer", "");
            chart.charter = meta.optString("charter", "");
            chart.level = meta.optString("level", "");
            chart.id = meta.optString("id", "");
            chart.song = meta.optString("song", "");
            chart.background = meta.optString("background", "");
            chart.offsetMs = meta.optInt("offset", 0);
            chart.rpeVersion = meta.optInt("RPEVersion", 123);
        }

        JSONArray bpmArray = root.optJSONArray("BPMList");
        if (bpmArray != null) {
            for (int index = 0; index < bpmArray.length(); index++) {
                JSONObject bpmObject = bpmArray.optJSONObject(index);
                if (bpmObject != null) chart.bpmChanges.add(BpmChange.fromJson(bpmObject));
            }
        }
        if (chart.bpmChanges.isEmpty()) chart.bpmChanges.add(new BpmChange());
        chart.sortBpm();

        JSONArray linesArray = root.optJSONArray("judgeLineList");
        if (linesArray != null) {
            for (int index = 0; index < linesArray.length(); index++) {
                JSONObject lineObject = linesArray.optJSONObject(index);
                if (lineObject != null) chart.judgeLines.add(JudgeLine.fromJson(lineObject));
            }
        }
        if (chart.judgeLines.isEmpty()) chart.judgeLines.add(new JudgeLine());
        return chart;
    }

    public String toJsonString() throws JSONException {
        StringWriter output = new StringWriter();
        try {
            writeJson(output);
        } catch (IOException impossible) {
            throw new JSONException(impossible.getMessage());
        }
        return output.toString();
    }

    /** Streams compact JSON so large charts never exist as a second full in-memory String. */
    public void writeJson(Writer output) throws IOException, JSONException {
        output.write('{');
        boolean hasField = false;
        boolean hasGroups = false;
        if (rawRoot != null) {
            Iterator<String> keys = rawRoot.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("META".equals(key) || "BPMList".equals(key)
                        || "judgeLineList".equals(key)) continue;
                if (hasField) output.write(',');
                hasField = true;
                JsonStreamWriter.value(output, key);
                output.write(':');
                JsonStreamWriter.value(output, rawRoot.get(key));
                if ("judgeLineGroup".equals(key)) hasGroups = true;
            }
        }

        if (hasField) output.write(',');
        JsonStreamWriter.value(output, "META");
        output.write(':');
        JSONObject meta = RawJson.shallowCopy(rawMeta);
        meta.put("RPEVersion", rpeVersion);
        meta.put("background", background);
        meta.put("charter", charter);
        meta.put("composer", composer);
        meta.put("id", id);
        meta.put("level", level);
        meta.put("name", name);
        meta.put("offset", offsetMs);
        meta.put("song", song);
        JsonStreamWriter.value(output, meta);

        sortBpm();
        output.write(',');
        JsonStreamWriter.value(output, "BPMList");
        output.write(':');
        output.write('[');
        for (int index = 0; index < bpmChanges.size(); index++) {
            if (index > 0) output.write(',');
            JsonStreamWriter.value(output, bpmChanges.get(index).toJson());
        }
        output.write(']');

        output.write(',');
        JsonStreamWriter.value(output, "judgeLineList");
        output.write(':');
        output.write('[');
        for (int index = 0; index < judgeLines.size(); index++) {
            if (index > 0) output.write(',');
            JsonStreamWriter.value(output, judgeLines.get(index).toJson());
        }
        output.write(']');
        if (!hasGroups) {
            output.write(',');
            JsonStreamWriter.value(output, "judgeLineGroup");
            output.write(':');
            JsonStreamWriter.value(output, new JSONArray().put("Default"));
        }
        output.write('}');
    }

    public long revision() {
        return revision;
    }

    public void markEdited() {
        revision++;
    }

    public double bpmAt(double beat) {
        double current = 120.0;
        for (BpmChange change : bpmChanges) {
            if (change.startTime.toDouble() > beat) break;
            current = validBpmOrFallback(change.bpm, current);
        }
        if (!bpmChanges.isEmpty() && bpmChanges.get(0).startTime.toDouble() > beat) {
            current = validBpmOrFallback(bpmChanges.get(0).bpm, current);
        }
        return current;
    }

    /** Converts chart beat to elapsed chart milliseconds, excluding META offset. */
    public long beatToMillis(double targetBeat) {
        double beat = Math.max(0.0, targetBeat);
        if (beat == 0.0) return 0L;

        double milliseconds = 0.0;
        double cursorBeat = 0.0;
        double currentBpm = bpmAt(0.0);
        for (BpmChange change : bpmChanges) {
            double changeBeat = Math.max(0.0, change.startTime.toDouble());
            if (changeBeat <= cursorBeat) {
                currentBpm = validBpmOrFallback(change.bpm, currentBpm);
                continue;
            }
            if (changeBeat >= beat) break;
            milliseconds += (changeBeat - cursorBeat) * 60000.0 / currentBpm;
            cursorBeat = changeBeat;
            currentBpm = validBpmOrFallback(change.bpm, currentBpm);
        }
        milliseconds += (beat - cursorBeat) * 60000.0 / currentBpm;
        return Math.max(0L, Math.round(milliseconds));
    }

    /** Converts elapsed chart milliseconds, excluding META offset, into a beat. */
    public double millisToBeat(long targetMillis) {
        double remaining = Math.max(0L, targetMillis);
        double cursorBeat = 0.0;
        double currentBpm = bpmAt(0.0);
        for (BpmChange change : bpmChanges) {
            double changeBeat = Math.max(0.0, change.startTime.toDouble());
            if (changeBeat <= cursorBeat) {
                currentBpm = validBpmOrFallback(change.bpm, currentBpm);
                continue;
            }
            double segmentMillis = (changeBeat - cursorBeat) * 60000.0 / currentBpm;
            if (remaining <= segmentMillis) {
                return cursorBeat + remaining * currentBpm / 60000.0;
            }
            remaining -= segmentMillis;
            cursorBeat = changeBeat;
            currentBpm = validBpmOrFallback(change.bpm, currentBpm);
        }
        return cursorBeat + remaining * currentBpm / 60000.0;
    }

    public long beatToAudioMillis(double beat) {
        return beatToAudioMillis(beat, 0L);
    }

    /** Converts a beat to audio time using both RPE META.offset and package info.yml offset. */
    public long beatToAudioMillis(double beat, long packageOffsetMs) {
        long combinedOffsetMs = saturatingAdd(offsetMs, packageOffsetMs);
        return Math.max(0L, saturatingAdd(beatToMillis(beat), combinedOffsetMs));
    }

    public double audioMillisToBeat(long audioMillis) {
        return audioMillisToBeat(audioMillis, 0L);
    }

    /** Inverse of {@link #beatToAudioMillis(double, long)}. */
    public double audioMillisToBeat(long audioMillis, long packageOffsetMs) {
        long combinedOffsetMs = saturatingAdd(offsetMs, packageOffsetMs);
        return millisToBeat(Math.max(0L, saturatingSubtract(audioMillis, combinedOffsetMs)));
    }

    public int totalNotes() {
        int total = 0;
        for (JudgeLine line : judgeLines) total += line.notes.size();
        return total;
    }

    public int totalEvents() {
        int total = 0;
        for (JudgeLine line : judgeLines) total += line.countEvents();
        return total;
    }

    private static double validBpmOrFallback(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static long saturatingSubtract(long left, long right) {
        if (right == Long.MIN_VALUE) return left >= 0L ? Long.MAX_VALUE : left - right;
        return saturatingAdd(left, -right);
    }

    public void sortBpm() {
        bpmChanges.sort(Comparator.comparing(change -> change.startTime));
    }
}
