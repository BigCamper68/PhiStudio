package com.xpe.mobile.editor;

import com.xpe.mobile.model.EventType;
import com.xpe.mobile.model.LineEvent;
import com.xpe.mobile.model.Note;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** View state stays separate from the exported chart and Android's small saved-state Bundle. */
final class EditorSessionState {
    private EditorSessionState() { }

    static JSONObject capture(EditorChromeView view) throws JSONException {
        JSONObject state = new JSONObject();
        state.put("projectName", view.projectName);
        state.put("projectLabel", view.projectNameResource == 0 ? "" : view.getResources().getResourceEntryName(view.projectNameResource));
        state.put("beat", view.currentBeat); state.put("line", view.lineIndex);
        state.put("layer", view.eventLayerIndex); state.put("division", view.subdivision);
        state.put("zoom", view.pixelsPerBeat / view.density); state.put("xGrid", view.verticalGridLines);
        state.put("tab", view.workbenchTab); state.put("panel", view.bottomPanel.name());
        state.put("tool", view.tool.name()); state.put("eventType", view.selectedEventType.name());
        state.put("speed", view.playbackSpeed); state.put("preview", view.previewMode);
        state.put("move", view.noteMoveMode.name()); state.put("rectangle", view.rectangleSelectionMode.name());
        state.put("binding", view.xyBindingEnabled);
        JSONArray notes = new JSONArray(), events = new JSONArray();
        if (view.chart != null) {
            for (Note note : view.selectedNotes) notes.put(view.currentLine().notes.indexOf(note));
            for (LineEvent event : view.selectedEvents) events.put(eventRef(view, event));
            state.put("note", view.currentLine().notes.indexOf(view.selectedNote));
            if (view.selectedEvent != null) state.put("event", eventRef(view, view.selectedEvent));
        }
        state.put("notes", notes); state.put("events", events);
        state.put("clipboard", ChartClipboard.toSessionJson(view.clipboard));
        state.put("storyboard", view.storyboardTimeline.saveSession());
        return state;
    }

    static void restore(EditorChromeView view, JSONObject state) throws JSONException {
        if (state == null || view.chart == null || view.chart.judgeLines.isEmpty()) return;
        view.cancelActiveGesture(); view.clearSelection();
        view.projectName = state.optString("projectName", view.projectName);
        String label = state.optString("projectLabel");
        view.projectNameResource = "project_demo".equals(label) ? com.xpe.mobile.R.string.project_demo
                : "project_autosave".equals(label) ? com.xpe.mobile.R.string.project_autosave : 0;
        view.lineIndex = bound(state.optInt("line"), view.chart.judgeLines.size());
        view.eventLayerIndex = bound(state.optInt("layer"), view.currentLine().eventLayers.size());
        double beat = state.optDouble("beat", 0), zoom = state.optDouble("zoom", 96);
        view.currentBeat = Double.isFinite(beat) ? Math.max(0, beat) : 0;
        view.pixelsPerBeat = (float) (Double.isFinite(zoom) ? Math.max(8, Math.min(280, zoom)) : 96) * view.density;
        view.subdivision = Math.max(1, Math.min(1920, state.optInt("division", 4)));
        view.verticalGridLines = VerticalGrid.changeCount(state.optInt("xGrid", VerticalGrid.defaultCount()), 0);
        view.workbenchTab = bound(state.optInt("tab"), 3);
        view.bottomPanel = value(EditorChromeView.BottomPanel.class, state.optString("panel"), EditorChromeView.BottomPanel.CREATE);
        view.tool = value(EditorSurfaceView.Tool.class, state.optString("tool"), EditorSurfaceView.Tool.SELECT);
        view.selectedEventType = value(EventType.class, state.optString("eventType"), EventType.MOVE_X);
        view.noteMoveMode = value(NoteMoveMode.class, state.optString("move"), NoteMoveMode.OFF);
        view.rectangleSelectionMode = value(EditorSurfaceView.RectangleSelectionMode.class,
                state.optString("rectangle"), EditorSurfaceView.RectangleSelectionMode.NONE);
        view.xyBindingEnabled = state.optBoolean("binding") && XYBindingValidator.isChartSynchronized(view.chart);
        double speed = state.optDouble("speed", 1);
        view.playbackSpeed = (float) (Double.isFinite(speed) ? Math.max(.25, Math.min(2, speed)) : 1);
        view.previewMode = state.optBoolean("preview"); view.playing = false; view.playbackScrubbing = false;
        JSONArray notes = state.optJSONArray("notes"), events = state.optJSONArray("events");
        if (notes != null) for (int i = 0; i < notes.length(); i++) {
            Note note = note(view, notes.optInt(i, -1)); if (note != null) view.selectedNotes.add(note);
        }
        if (events != null) for (int i = 0; i < events.length(); i++) {
            LineEvent event = event(view, events.optJSONObject(i)); if (event != null) view.selectedEvents.add(event);
        }
        view.selectedNote = note(view, state.optInt("note", -1));
        view.selectedEvent = event(view, state.optJSONObject("event"));
        view.clipboard = ChartClipboard.fromSessionJson(state.optJSONObject("clipboard"));
        view.storyboardTimeline.restoreSession(state.optJSONObject("storyboard"));
        view.previewPlayback.reset(); view.invalidate();
    }

    private static Note note(EditorChromeView view, int index) {
        return index >= 0 && index < view.currentLine().notes.size() ? view.currentLine().notes.get(index) : null;
    }
    private static JSONObject eventRef(EditorChromeView view, LineEvent event) throws JSONException {
        return new JSONObject().put("type", event.type.name()).put("index", view.currentLayer().events(event.type).indexOf(event));
    }
    private static LineEvent event(EditorChromeView view, JSONObject ref) {
        if (ref == null) return null;
        EventType type = value(EventType.class, ref.optString("type"), EventType.MOVE_X);
        java.util.List<LineEvent> events = view.currentLayer().events(type);
        int index = ref.optInt("index", -1);
        return index >= 0 && index < events.size() ? events.get(index) : null;
    }
    private static int bound(int value, int count) { return Math.max(0, Math.min(Math.max(0, count - 1), value)); }
    static <T extends Enum<T>> T value(Class<T> type, String name, T fallback) {
        try { return Enum.valueOf(type, name); } catch (IllegalArgumentException exception) { return fallback; }
    }
}
