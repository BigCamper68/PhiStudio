package com.xpe.mobile.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JudgeLine {
    private static final int RPE_LAYER_SLOTS = 5;
    private static final double CONTROL_SENTINEL_X = 9_999_999.0;

    public int group;
    public String name = "Line";
    public String texture = "line.png";
    public double bpmFactor = 1.0;
    public int father = -1;
    public boolean rotateWithFather;
    public boolean cover = true;
    public int zOrder;
    public AttachedUiElement attachUi;
    public ExtendedLineEvents extended = new ExtendedLineEvents();
    public NoteControlEvents noteControls = new NoteControlEvents();
    public final List<Note> notes = new ArrayList<>();
    public final List<EventLayer> eventLayers = new ArrayList<>();
    private JSONObject raw;

    public JudgeLine() {
        EventLayer baseLayer = new EventLayer();
        for (EventType type : EventType.values()) {
            LineEvent event = new LineEvent();
            event.type = type;
            event.startTime = BeatTime.zero();
            event.endTime = new BeatTime(1, 0, 1);
            event.start = LineEvent.defaultValue(type);
            event.end = event.start;
            baseLayer.events(type).add(event);
        }
        eventLayers.add(baseLayer);
    }

    public static JudgeLine fromJson(JSONObject object) throws JSONException {
        JudgeLine line = new JudgeLine();
        line.eventLayers.clear();
        line.raw = RawJson.unknownFields(object,
                "Group", "Name", "Texture", "bpmfactor", "father", "isCover",
                "zOrder", "notes", "numOfNotes", "eventLayers");
        line.group = object.optInt("Group", 0);
        line.name = object.optString("Name", "Line");
        line.texture = object.optString("Texture", "line.png");
        line.bpmFactor = object.optDouble("bpmfactor", 1.0);
        line.father = object.optInt("father", -1);
        Object rotateWithFather = object.opt("rotateWithFather");
        line.rotateWithFather = rotateWithFather instanceof Boolean
                ? (Boolean) rotateWithFather
                : rotateWithFather instanceof Number
                && ((Number) rotateWithFather).intValue() != 0;
        line.cover = object.optInt("isCover", 1) != 0;
        line.zOrder = object.optInt("zOrder", 0);
        line.attachUi = AttachedUiElement.fromJsonValue(object.opt("attachUI"));
        line.extended = ExtendedLineEvents.fromJson(object.optJSONObject("extended"));
        line.noteControls = NoteControlEvents.fromJson(object);

        JSONArray noteArray = object.optJSONArray("notes");
        if (noteArray != null) {
            for (int index = 0; index < noteArray.length(); index++) {
                JSONObject noteObject = noteArray.optJSONObject(index);
                if (noteObject != null) line.notes.add(Note.fromJson(noteObject));
            }
        }
        line.sortNotes();

        JSONArray layerArray = object.optJSONArray("eventLayers");
        if (layerArray != null) {
            for (int index = 0; index < layerArray.length(); index++) {
                line.eventLayers.add(EventLayer.fromJson(layerArray.optJSONObject(index)));
            }
        }
        if (line.eventLayers.isEmpty()) line.eventLayers.add(new EventLayer());
        return line;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = RawJson.shallowCopy(raw);
        object.put("Group", group);
        object.put("Name", name);
        object.put("Texture", texture);
        object.put("bpmfactor", bpmFactor);
        object.put("father", father);
        object.put("rotateWithFather", rotateWithFather);
        object.put("isCover", cover ? 1 : 0);
        object.put("zOrder", zOrder);

        // Re:PhiEdit's package writer emits these stable line defaults. Supplying them when an
        // older/minimal RPE chart omitted them prevents importers from constructing partially
        // initialized line state while leaving any real source values untouched.
        if (!object.has("anchor")) object.put("anchor", new JSONArray().put(0.5).put(0.5));
        if (!object.has("isGif")) object.put("isGif", false);
        ensureIdentityControl(object, "alphaControl", "alpha", 1.0);
        ensureIdentityControl(object, "posControl", "pos", 1.0);
        ensureIdentityControl(object, "sizeControl", "size", 1.0);
        ensureIdentityControl(object, "yControl", "y", 1.0);
        ensureIdentityControl(object, "skewControl", "skew", 0.0);

        if (extended != null && (raw == null && extended.count() > 0
                || extended.isModified())) {
            JSONObject extendedJson = extended.toJsonValue();
            if (extendedJson.length() == 0) object.remove("extended");
            else object.put("extended", extendedJson);
        }

        JSONArray notesJson = new JSONArray();
        sortNotes();
        int nonHoldNotes = 0;
        for (Note note : notes) {
            notesJson.put(note.toJson());
            if (note.type != NoteType.HOLD) nonHoldNotes++;
        }
        object.put("notes", notesJson);
        object.put("numOfNotes", nonHoldNotes);

        JSONArray layersJson = new JSONArray();
        int layerSlots = Math.max(RPE_LAYER_SLOTS, eventLayers.size());
        for (int index = 0; index < layerSlots; index++) {
            layersJson.put(index < eventLayers.size()
                    ? eventLayers.get(index).toJsonValue() : JSONObject.NULL);
        }
        object.put("eventLayers", layersJson);
        return object;
    }

    private static void ensureIdentityControl(JSONObject object, String arrayKey,
                                              String valueKey, double value)
            throws JSONException {
        if (object.has(arrayKey)) return;
        JSONArray controls = new JSONArray();
        controls.put(new JSONObject().put("easing", 1).put(valueKey, value).put("x", 0.0));
        controls.put(new JSONObject().put("easing", 1).put(valueKey, value)
                .put("x", CONTROL_SENTINEL_X));
        object.put(arrayKey, controls);
    }

    public JudgeLine copyProperties() {
        JudgeLine copy = new JudgeLine();
        copy.notes.clear();
        copy.eventLayers.clear();
        copy.group = group;
        copy.name = name;
        copy.texture = texture;
        copy.bpmFactor = bpmFactor;
        copy.father = father;
        copy.rotateWithFather = rotateWithFather;
        copy.cover = cover;
        copy.zOrder = zOrder;
        copy.attachUi = attachUi;
        copy.raw = raw;
        return copy;
    }

    public EventLayer layer(int index) {
        int safe = Math.max(0, index);
        while (eventLayers.size() <= safe) eventLayers.add(new EventLayer());
        return eventLayers.get(safe);
    }

    public int countEvents() {
        int total = extended == null ? 0 : extended.count();
        if (noteControls != null) total += noteControls.count();
        for (EventLayer layer : eventLayers) total += layer.count();
        return total;
    }

    public double eventValueAt(EventType type, double beat) {
        double total = type == EventType.ALPHA ? 0.0 : 0.0;
        boolean hasLayer = false;
        for (EventLayer layer : eventLayers) {
            if (!layer.events(type).isEmpty()) {
                total += layer.valueAt(type, beat);
                hasLayer = true;
            }
        }
        if (hasLayer) return total;
        return LineEvent.defaultValue(type);
    }

    public void sortNotes() {
        notes.sort(Comparator.comparing(note -> note.startTime));
    }
}
