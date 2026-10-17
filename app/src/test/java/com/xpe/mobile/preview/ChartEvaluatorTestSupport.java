package com.xpe.mobile.preview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.BpmChange;
import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.model.EventLayer;
import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import com.xpe.mobile.model.NoteControlEvents;
import com.xpe.mobile.model.NoteType;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

abstract class ChartEvaluatorTestSupport {
    protected static final double EPSILON = 0.002;

    protected static final double SPEED_DISTANCE_RATIO = (100.0 / 0.83175) / 120.0;

    protected static ChartDocument chart() {
        ChartDocument chart = new ChartDocument();
        BpmChange bpm = new BpmChange();
        bpm.bpm = 120.0;
        chart.bpmChanges.add(bpm);
        return chart;
    }

    protected static ChartDocument chartWithEasedSpeed(int rpeVersion) throws Exception {
        JSONObject speed = new JSONObject()
                .put("startTime", new JSONArray().put(0).put(0).put(1))
                .put("endTime", new JSONArray().put(4).put(0).put(1))
                .put("start", 0.0)
                .put("end", 12.0)
                .put("easingType", 5);
        JSONObject note = new JSONObject()
                .put("type", 1)
                .put("above", 1)
                .put("startTime", new JSONArray().put(4).put(0).put(1))
                .put("endTime", new JSONArray().put(4).put(0).put(1))
                .put("positionX", 0.0)
                .put("speed", 1.0)
                .put("size", 1.0)
                .put("alpha", 255)
                .put("visibleTime", 999999.0);
        JSONObject line = new JSONObject()
                .put("eventLayers", new JSONArray().put(new JSONObject()
                        .put("speedEvents", new JSONArray().put(speed))))
                .put("notes", new JSONArray().put(note));
        JSONObject root = new JSONObject()
                .put("META", new JSONObject().put("RPEVersion", rpeVersion))
                .put("BPMList", new JSONArray().put(new JSONObject()
                        .put("bpm", 120.0)
                        .put("startTime", new JSONArray().put(0).put(0).put(1))))
                .put("judgeLineList", new JSONArray().put(line));
        return ChartDocument.fromJson(root.toString());
    }

    protected static Note note(NoteType type, int startBeat) {
        Note note = new Note();
        note.type = type;
        note.startTime = beat(startBeat);
        note.endTime = note.startTime;
        return note;
    }

    protected static BeatTime beat(int whole) {
        return new BeatTime(whole, 0, 1);
    }

    protected static void setConstant(JudgeLine line, EventType type, double value) {
        setEvent(line.eventLayers.get(0), type, value, value, 0, 1);
    }

    protected static void setEvent(EventLayer layer, EventType type, double start, double end,
                                 int startBeat, int endBeat) {
        layer.events(type).clear();
        LineEvent event = new LineEvent();
        event.type = type;
        event.startTime = beat(startBeat);
        event.endTime = beat(endBeat);
        event.start = start;
        event.end = end;
        event.easingType = 1;
        layer.events(type).add(event);
    }

    protected static JSONObject extendedEvent(Object start, Object end,
                                            int startBeat, int endBeat) throws Exception {
        return new JSONObject()
                .put("startTime", new JSONArray().put(startBeat).put(0).put(1))
                .put("endTime", new JSONArray().put(endBeat).put(0).put(1))
                .put("start", start)
                .put("end", end)
                .put("easingType", 1)
                .put("easingLeft", 0.0)
                .put("easingRight", 1.0)
                .put("bezier", 0)
                .put("bezierPoints", new JSONArray().put(0).put(0).put(0).put(0));
    }

    protected static JSONArray control(String key, double value) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("x", 0.0).put("easing", 1).put(key, value));
    }
}
