package com.xpe.mobile.model;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Note {
    public int above = 1;
    public int alpha = 255;
    public BeatTime startTime = BeatTime.zero();
    public BeatTime endTime = BeatTime.zero();
    public boolean fake;
    public double positionX;
    public double size = 1.0;
    public double speed = 1.0;
    public NoteType type = NoteType.TAP;
    public double visibleTime = 999999.0;
    public double yOffset;
    public boolean hasTint;
    public int tintRgb = 0xFFFFFF;
    public boolean hasHitEffectTint;
    public int hitEffectTintRgb = 0xFFFFFF;
    public double judgeArea = 1.0;
    private JSONObject raw;

    public static Note fromJson(JSONObject object) throws JSONException {
        Note note = new Note();
        note.raw = RawJson.unknownFields(object,
                "above", "alpha", "startTime", "endTime", "isFake",
                "positionX", "size", "speed", "type", "visibleTime", "yOffset");
        note.above = object.optInt("above", 1);
        note.alpha = object.optInt("alpha", 255);
        note.startTime = BeatTime.fromJson(object.optJSONArray("startTime"));
        note.endTime = BeatTime.fromJson(object.optJSONArray("endTime"));
        note.fake = object.optInt("isFake", 0) != 0;
        note.positionX = object.optDouble("positionX", 0.0);
        note.size = object.optDouble("size", 1.0);
        note.speed = object.optDouble("speed", 1.0);
        note.type = NoteType.fromCode(object.optInt("type", 1));
        note.visibleTime = object.optDouble("visibleTime", 999999.0);
        note.yOffset = object.optDouble("yOffset", 0.0);
        JSONArray tint = object.optJSONArray("tint");
        if (tint != null && tint.length() >= 3) {
            note.hasTint = true;
            note.tintRgb = rgb(tint);
        }
        JSONArray hitTint = object.optJSONArray("tintHitEffects");
        if (hitTint != null && hitTint.length() >= 3) {
            note.hasHitEffectTint = true;
            note.hitEffectTintRgb = rgb(hitTint);
        }
        note.judgeArea = object.optDouble("judgeArea", 1.0);
        return note;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = RawJson.shallowCopy(raw);
        object.put("above", above);
        object.put("alpha", alpha);
        object.put("endTime", endTime.toJson());
        object.put("isFake", fake ? 1 : 0);
        object.put("positionX", positionX);
        object.put("size", size);
        object.put("speed", speed);
        object.put("startTime", startTime.toJson());
        object.put("type", type.rpeCode);
        object.put("visibleTime", visibleTime);
        object.put("yOffset", yOffset);
        return object;
    }

    public Note copy() {
        Note copy = new Note();
        copy.above = above;
        copy.alpha = alpha;
        copy.startTime = startTime;
        copy.endTime = endTime;
        copy.fake = fake;
        copy.positionX = positionX;
        copy.size = size;
        copy.speed = speed;
        copy.type = type;
        copy.visibleTime = visibleTime;
        copy.yOffset = yOffset;
        copy.hasTint = hasTint;
        copy.tintRgb = tintRgb;
        copy.hasHitEffectTint = hasHitEffectTint;
        copy.hitEffectTintRgb = hitEffectTintRgb;
        copy.judgeArea = judgeArea;
        copy.raw = raw;
        return copy;
    }

    private static int rgb(org.json.JSONArray value) {
        int red = Math.max(0, Math.min(255, value.optInt(0, 255)));
        int green = Math.max(0, Math.min(255, value.optInt(1, 255)));
        int blue = Math.max(0, Math.min(255, value.optInt(2, 255)));
        return red << 16 | green << 8 | blue;
    }
}
