package com.xpe.mobile.model;

import org.json.JSONException;
import org.json.JSONObject;

public final class BpmChange {
    public double bpm = 120.0;
    public BeatTime startTime = BeatTime.zero();
    private JSONObject raw;

    public static BpmChange fromJson(JSONObject object) throws JSONException {
        BpmChange change = new BpmChange();
        change.raw = RawJson.unknownFields(object, "bpm", "startTime");
        change.bpm = object.optDouble("bpm", 120.0);
        change.startTime = BeatTime.fromJson(object.optJSONArray("startTime"));
        return change;
    }

    public BpmChange copy() {
        BpmChange copy = new BpmChange();
        copy.bpm = bpm;
        copy.startTime = startTime;
        copy.raw = raw;
        return copy;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = RawJson.shallowCopy(raw);
        object.put("bpm", bpm);
        object.put("startTime", startTime.toJson());
        return object;
    }
}
