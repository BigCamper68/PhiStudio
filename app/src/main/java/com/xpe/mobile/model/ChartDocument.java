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
import java.util.Objects;

public final class ChartDocument {
    public String name = "Untitled";
    public String composer = "";
    public String charter = "";
    public String level = "";
    public String id = "";
    public String song = "";
    public String background = "";
    public static final double DEFAULT_BACKGROUND_DIM = 0.6;
    /** Nullable until package import resolves info.yml; stored per chart, never globally. */
    public Double backgroundDim;
    /** Runtime Phira compatibility flag loaded from info.yml, not an RPE META field. */
    public boolean useAttachUiFix;
    /** ChartInfo noteUniformScale; runtime package option, not chart data. */
    public boolean noteUniformScale;
    /** PhiStudio provenance for converted PEC alpha flags; native RPE players ignore this META field. */
    public boolean phiPecAlphaExtension;
    public int offsetMs;
    public int rpeVersion = 123;
    public final List<BpmChange> bpmChanges = new ArrayList<>();
    public final List<JudgeLine> judgeLines = new ArrayList<>();
    public final List<NoiseField> noiseFields = new ArrayList<>();
    /** Runtime PNG header dimensions for Phira-Pro Line2Area; never serialized. */
    public final java.util.Map<String, int[]> noiseMarkerSizes = new java.util.HashMap<>();
    private boolean hasNoiseFieldList;
    private boolean restoredNoiseSource;
    /** A compatible package was converted back to the editor's native field model. */
    public boolean wasNoiseSourceRestored() { return restoredNoiseSource; }

    public void loadNoiseFields(JSONObject root) throws JSONException {
        hasNoiseFieldList = root.has("blockAreaList");
        if (hasNoiseFieldList && root.optJSONArray("blockAreaList") == null)
            throw new JSONException("blockAreaList must be an array");
        noiseFields.clear();
        noiseFields.addAll(NoiseField.fromJson(root.optJSONArray("blockAreaList")));
    }
    private JSONObject rawRoot;
    private JSONObject rawMeta;
    private JSONObject pecHeights;
    private PackageDisplayMetadata packageDisplayMetadata;
    private long revision;

    /** Package display fields are separate from authored RPE META and never serialized. */
    public void setPackageDisplayMetadata(String name, String level, String composer, String charter) {
        packageDisplayMetadata = new PackageDisplayMetadata(this, name, level, composer, charter);
    }

    public String getDisplayName() {
        return packageDisplayMetadata == null ? name : packageDisplayMetadata.value("name", name);
    }

    public String getDisplayLevel() {
        return packageDisplayMetadata == null ? level : packageDisplayMetadata.value("level", level);
    }

    public String getDisplayComposer() {
        return packageDisplayMetadata == null ? composer : packageDisplayMetadata.value("composer", composer);
    }

    public String getDisplayCharter() {
        return packageDisplayMetadata == null ? charter : packageDisplayMetadata.value("charter", charter);
    }

    /** Save/export uses this to synchronize explicit editor changes, without rewriting imports. */
    public boolean isPackageMetadataEdited(String key) {
        if (packageDisplayMetadata == null) return false;
        String current;
        switch (key) {
            case "name": current = name; break;
            case "level": current = level; break;
            case "composer": current = composer; break;
            case "charter": current = charter; break;
            default: return false;
        }
        int index = packageDisplayMetadata.index(key);
        return !Objects.equals(packageDisplayMetadata.authored[index], current)
                || !Objects.equals(packageDisplayMetadata.saved[index], current);
    }

    /** Called only after workspace metadata and chart data were saved successfully. */
    public void markPackageMetadataSaved() {
        if (packageDisplayMetadata != null) {
            packageDisplayMetadata.saved = new String[]{name, level, composer, charter};
        }
    }

    private static final class PackageDisplayMetadata {
        final String[] authored;
        final String[] display;
        String[] saved;

        PackageDisplayMetadata(ChartDocument chart, String name, String level,
                               String composer, String charter) {
            authored = new String[]{chart.name, chart.level, chart.composer, chart.charter};
            saved = authored;
            display = new String[]{name, level, composer, charter};
        }

        int index(String key) {
            switch (key) {
                case "name": return 0;
                case "level": return 1;
                case "composer": return 2;
                case "charter": return 3;
                default: throw new IllegalArgumentException("Unknown display metadata field: " + key);
            }
        }

        String value(String key, String current) {
            int index = index(key);
            return display[index] != null && Objects.equals(authored[index], current)
                    ? display[index] : current;
        }
    }

    public static ChartDocument fromJson(String json) throws JSONException {
        return fromParsedJson(com.xpe.mobile.preview.NoiseFieldRpeSource.parse(json));
    }

    public static ChartDocument fromParsedJson(JSONObject root) throws JSONException {
        if (root == null) throw new JSONException("Chart JSON root is missing");
        JSONObject originalRoot = root;
        root = com.xpe.mobile.preview.NoiseFieldRpeSource.restore(root);
        ChartDocument chart = new ChartDocument();
        chart.restoredNoiseSource = root != originalRoot;
        chart.rawRoot = RawJson.unknownFields(root, "META", "BPMList", "judgeLineList", "blockAreaList");
        chart.loadNoiseFields(root);

        JSONObject meta = root.optJSONObject("META");
        if (meta != null) {
            chart.rawMeta = RawJson.unknownFields(meta,
                    "RPEVersion", "background", "charter", "composer", "id",
                    "level", "name", "offset", "song", "backgroundDim", "phiPecAlphaExtension", "phiPecHeights");
            chart.name = meta.optString("name", "Untitled");
            chart.composer = meta.optString("composer", "");
            chart.charter = meta.optString("charter", "");
            chart.level = meta.optString("level", "");
            chart.id = meta.optString("id", "");
            chart.song = meta.optString("song", "");
            chart.background = meta.optString("background", "");
            double dim = meta.optDouble("backgroundDim", Double.NaN);
            if (Double.isFinite(dim) && dim >= 0 && dim <= 1) chart.backgroundDim = dim;
            chart.offsetMs = meta.optInt("offset", 0);
            chart.rpeVersion = meta.optInt("RPEVersion", 123);
            chart.phiPecAlphaExtension = meta.optBoolean("phiPecAlphaExtension", false);
            chart.pecHeights = meta.optJSONObject("phiPecHeights");
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
        writeJson(output, null, null);
    }

    /** Export-only line adapter; the editable chart and its history are never modified. */
    public void writeJson(Writer output, List<JSONObject> noiseLines, JSONObject noiseSource)
            throws IOException, JSONException {
        output.write('{');
        boolean hasField = false;
        boolean hasGroups = false;
        if (rawRoot != null) {
            Iterator<String> keys = rawRoot.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("META".equals(key) || "BPMList".equals(key)
                        || "judgeLineList".equals(key)
                        || noiseSource != null && "phiStudioNoiseSource".equals(key)) continue;
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
        meta.put("backgroundDim", getBackgroundDim());
        meta.put("charter", charter);
        meta.put("composer", composer);
        meta.put("id", id);
        meta.put("level", level);
        meta.put("name", name);
        meta.put("offset", offsetMs);
        meta.put("song", song);
        if (phiPecAlphaExtension) meta.put("phiPecAlphaExtension", true);
        if (pecHeights != null) meta.put("phiPecHeights", pecHeights);
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
        if (noiseLines != null) for (int i = 0; i < noiseLines.size(); i++) {
            if (!judgeLines.isEmpty() || i > 0) output.write(',');
            JsonStreamWriter.value(output, noiseLines.get(i));
        }
        output.write(']');
        if (noiseSource != null) {
            output.write(',');
            JsonStreamWriter.value(output, "phiStudioNoiseSource");
            output.write(':');
            JsonStreamWriter.value(output, noiseSource);
        }
        if (noiseLines == null && (hasNoiseFieldList || !noiseFields.isEmpty())) {
            output.write(',');
            JsonStreamWriter.value(output, "blockAreaList");
            output.write(':');
            output.write('[');
            for (int i = 0; i < noiseFields.size(); i++) {
                if (i > 0) output.write(',');
                JsonStreamWriter.value(output, noiseFields.get(i).toExportJson());
            }
            output.write(']');
        }
        if (!hasGroups) {
            output.write(',');
            JsonStreamWriter.value(output, "judgeLineGroup");
            output.write(':');
            JsonStreamWriter.value(output, new JSONArray().put("Default"));
        }
        output.write('}');
    }

    public double getBackgroundDim() {
        return backgroundDim != null && Double.isFinite(backgroundDim)
                && backgroundDim >= 0 && backgroundDim <= 1 ? backgroundDim : DEFAULT_BACKGROUND_DIM;
    }

    /** Original PEC floor keyframes are valid only while their timing/speed inputs match. */
    public void preservePecHeights(JSONArray lines) throws JSONException {
        pecHeights = new JSONObject().put("signature", pecHeightSignature()).put("lines", lines);
    }

    public boolean hasPreservedPecHeights() {
        return pecHeights != null && pecHeightSignature().equals(pecHeights.optString("signature"))
                && pecHeights.optJSONArray("lines") != null
                && pecHeights.optJSONArray("lines").length() == judgeLines.size();
    }

    /** Read-only table; the caller has already verified hasPreservedPecHeights(). */
    public JSONArray preservedPecHeights(JudgeLine line) {
        if (pecHeights == null) return null;
        JSONArray lines = pecHeights.optJSONArray("lines");
        int index = judgeLines.indexOf(line);
        return lines == null || index < 0 ? null : lines.optJSONArray(index);
    }

    private String pecHeightSignature() {
        long h = 17;
        h = h * 31 + bpmChanges.size();
        for (BpmChange bpm : bpmChanges) {
            h = h * 31 + bpm.startTime.hashCode();
            h = h * 31 + Double.doubleToLongBits(bpm.bpm);
        }
        h = h * 31 + judgeLines.size();
        for (JudgeLine line : judgeLines) {
            h = h * 31 + line.notes.size();
            for (Note note : line.notes) {
                h = h * 31 + note.startTime.hashCode();
                h = h * 31 + note.endTime.hashCode();
                h = h * 31 + note.type.ordinal();
            }
            h = h * 31 + line.eventLayers.size();
            for (EventLayer layer : line.eventLayers) {
                for (EventType type : EventType.values()) {
                    List<LineEvent> events = layer.events(type); h = h * 31 + events.size();
                    for (LineEvent event : events) {
                        h = h * 31 + event.startTime.hashCode();
                        h = h * 31 + event.endTime.hashCode();
                        if (type == EventType.SPEED) {
                            h = h * 31 + Double.doubleToLongBits(event.start == 0 ? 0 : event.start);
                            h = h * 31 + Double.doubleToLongBits(event.end == 0 ? 0 : event.end);
                            h = h * 31 + event.sourceSpeedEasing();
                            h = h * 31 + event.pecSpeedStamp();
                        }
                    }
                }
            }
            if (line.extended != null) for (StoryboardEventType type : StoryboardEventType.values()) {
                List<? extends ExtendedLineEvents.TimedEvent> events = line.extended.events(type);
                h = h * 31 + events.size();
                for (ExtendedLineEvents.TimedEvent event : events) {
                    h = h * 31 + event.startTime.hashCode(); h = h * 31 + event.endTime.hashCode();
                }
            }
        }
        return Long.toHexString(h);
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
        return Math.max(0L, Math.round(beatToExactMillis(targetBeat)));
    }

    /** Chart time without millisecond quantization, for prpr shader ranges and uniforms. */
    public double beatToSeconds(double targetBeat) {
        return beatToExactMillis(targetBeat) / 1000.0;
    }

    private double beatToExactMillis(double targetBeat) {
        double beat = Double.isFinite(targetBeat) ? targetBeat : 0.0;
        double seconds = 0.0;
        double cursorBeat = bpmChanges.isEmpty() ? 0.0 : bpmChanges.get(0).startTime.toDouble();
        double currentBpm = bpmChanges.isEmpty() ? 120.0
                : validBpmOrFallback(bpmChanges.get(0).bpm, 120.0);
        // RPE/prpr anchors elapsed time at the first authored BPM beat, not at beat zero.
        for (int index = 1; index < bpmChanges.size(); index++) {
            BpmChange change = bpmChanges.get(index);
            double changeBeat = change.startTime.toDouble();
            if (changeBeat > beat) break;
            seconds += (changeBeat - cursorBeat) * (60.0 / currentBpm);
            cursorBeat = changeBeat;
            currentBpm = validBpmOrFallback(change.bpm, currentBpm);
        }
        seconds += (beat - cursorBeat) * (60.0 / currentBpm);
        return seconds * 1000.0;
    }

    /** Converts elapsed chart milliseconds, excluding META offset, into a beat. */
    public double millisToBeat(long targetMillis) {
        return elapsedMillisToBeat(Math.max(0L, targetMillis));
    }

    /** Fractional chart time for editing; audio timestamps remain integer milliseconds. */
    public double secondsToBeat(double seconds) {
        return elapsedMillisToBeat(Double.isFinite(seconds) ? Math.max(0, seconds) * 1000 : 0);
    }

    private double elapsedMillisToBeat(double milliseconds) {
        double seconds = milliseconds / 1000.0;
        double cursorSeconds = 0.0;
        double cursorBeat = bpmChanges.isEmpty() ? 0.0 : bpmChanges.get(0).startTime.toDouble();
        double currentBpm = bpmChanges.isEmpty() ? 120.0
                : validBpmOrFallback(bpmChanges.get(0).bpm, 120.0);
        for (int index = 1; index < bpmChanges.size(); index++) {
            BpmChange change = bpmChanges.get(index);
            double changeBeat = change.startTime.toDouble();
            double nextSeconds = cursorSeconds + (changeBeat - cursorBeat) * (60.0 / currentBpm);
            if (nextSeconds > seconds) break;
            cursorSeconds = nextSeconds;
            cursorBeat = changeBeat;
            currentBpm = validBpmOrFallback(change.bpm, currentBpm);
        }
        return cursorBeat + (seconds - cursorSeconds) / (60.0 / currentBpm);
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
