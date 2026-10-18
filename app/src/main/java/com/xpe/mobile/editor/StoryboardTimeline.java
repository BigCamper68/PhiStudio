package com.xpe.mobile.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.xpe.mobile.R;
import com.xpe.mobile.model.BeatTime;
import com.xpe.mobile.model.ExtendedLineEvents;
import com.xpe.mobile.model.JudgeLine;
import com.xpe.mobile.model.StoryboardEventType;

import java.util.List;
import java.util.Locale;

/** Synchronized vertical beat timeline, horizontally scrollable lanes and transactional gestures. */
final class StoryboardTimeline {
    private static final StoryboardEventType[] TYPES = StoryboardEventType.editableTypes();
    private static final int[] LABELS = {R.string.storyboard_type_scale_x, R.string.storyboard_type_scale_y, R.string.storyboard_type_color, R.string.storyboard_type_text, R.string.storyboard_type_paint, R.string.storyboard_lane_gif};
    private static final float HEADER_TOUCH_HEIGHT_DP = 22f;
    private static final int[] COLORS = {0xFF4EBAA8, 0xFF559BCB, 0xFFCF89C5,
            0xFF8B93E0, 0xFFE0AF60, 0xFFE08772};
    private enum Gesture { NONE, SELECT, PAN, START, END, CREATE }
    private final EditorChromeView view;
    private JudgeLine scope;
    private ExtendedLineEvents.TimedEvent selection;
    private StoryboardEventType type = TYPES[0];
    private ExtendedLineEvents.TimedEvent candidate;
    private Gesture gesture = Gesture.NONE;
    private float left, top, right, bottom, scroll, initialScroll, downX, downY;
    private double initialBeat;
    private boolean moved, horizontal, handleMoved;
    private final java.util.LinkedHashMap<ExtendedLineEvents.TimedEvent, StoryboardEventType> selectedItems
            = new java.util.LinkedHashMap<>();
    private final java.util.List<Item> copied = new java.util.ArrayList<>();
    private boolean clipboardActive;
    private static final class Item {
        final StoryboardEventType type;
        final ExtendedLineEvents.TimedEvent event;
        Item(StoryboardEventType type, ExtendedLineEvents.TimedEvent event) { this.type = type; this.event = event; }
    }

    StoryboardTimeline(EditorChromeView view) { this.view = view; }
    boolean active() { return gesture != Gesture.NONE; }
    void reset() { scope = null; selection = null; selectedItems.clear(); copied.clear(); clipboardActive = false;
        type = TYPES[0]; scroll = 0; cancel(); }
    void cancel() { gesture = Gesture.NONE; candidate = null; }
    void clearSelection() { selection = null; selectedItems.clear(); candidate = null; view.invalidate(); }
    void select(StoryboardEventType type, ExtendedLineEvents.TimedEvent event) {
        syncScope();
        if (scope == null || scope.extended == null || !scope.extended.contains(type, event)) return;
        selectedItems.clear(); selectedItems.put(event, type);
        this.type = type; selection = event;
        int index = java.util.Arrays.asList(TYPES).indexOf(type);
        float x = index * laneWidth();
        if (x < scroll || x + laneWidth() > scroll + right - left)
            scroll = StoryboardTimelineGeometry.clampScroll(x, right - left, view.density, TYPES.length);
    }
    ExtendedLineEvents.TimedEvent selected() {
        syncScope();
        return selection;
    }
    int selectionCount() { syncScope(); return selectedItems.size(); }
    private void primarySelection() {
        selection = selectedItems.isEmpty() ? null : selectedItems.keySet().iterator().next();
        if (selection != null) type = selectedItems.get(selection);
    }
    private void syncScope() {
        JudgeLine line = view.getCurrentJudgeLine();
        if (scope != line) { scope = line; selection = null; selectedItems.clear(); cancel(); }
        selectedItems.entrySet().removeIf(item -> line == null || line.extended == null
                || !line.extended.contains(item.getValue(), item.getKey()));
        primarySelection();
    }
    String typeLabel() {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i] == type) return view.getResources().getString(LABELS[i]);
        return type.name();
    }
    String selectionSummary() {
        if (selectedItems.size() > 1) return view.getResources().getString(R.string.storyboard_selected_summary, selectedItems.size());
        return selection == null ? view.getResources().getString(R.string.storyboard_type_summary, typeLabel())
                : typeLabel() + " · " + selection.startTime + " → " + selection.endTime;
    }
    private float laneWidth() {
        return StoryboardTimelineGeometry.laneWidth(right - left, view.density);
    }
    private int laneAt(float x) {
        return StoryboardTimelineGeometry.laneAt(x, left, right - left, view.density, scroll, TYPES.length);
    }
    private double beatAt(float y) { return view.yToBeat(y, view.timelineY(bottom)); }
    private BeatTime snapped(float y) {
        return BeatTime.fromDouble(view.snapBeat(beatAt(y)), view.subdivision);
    }
    private RectF rect(int index, ExtendedLineEvents.TimedEvent event) {
        float start = view.beatToY(event.startTime.toDouble(), view.timelineY(bottom));
        float end = view.beatToY(event.endTime.toDouble(), view.timelineY(bottom));
        float x = left + index * laneWidth() - scroll;
        return new RectF(x + 3 * view.density, end, x + laneWidth() - 3 * view.density,
                Math.max(start, end + 6 * view.density));
    }

    void draw(Canvas canvas, float left, float top, float right, float bottom) {
        syncScope();
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        scroll = StoryboardTimelineGeometry.clampScroll(scroll, right - left, view.density, TYPES.length);
        float d = view.density;
        double minimumBeat = beatAt(bottom), maximumBeat = beatAt(top);
        Paint paint = view.paint;
        view.prepareTimelineGridPaint();
        int save = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        TimelineGridRange.Range range = TimelineGridRange.visible(view.currentBeat,
                view.timelineY(bottom), top, bottom, view.pixelsPerBeat, view.subdivision);
        for (int step = range.firstStep; step <= range.lastStep; step++) {
            float y = view.beatToY(step / (double) view.subdivision, view.timelineY(bottom));
            if (y < top || y > bottom) continue;
            paint.setColor(Math.floorMod(step, view.subdivision) == 0
                    ? EditorSurfaceView.GRID_MAJOR : EditorSurfaceView.GRID_MINOR);
            canvas.drawLine(left, y, right, y, paint);
        }
        for (int index = 0; index < TYPES.length; index++) {
            float x = left + index * laneWidth() - scroll;
            if (x > right || x + laneWidth() < left) continue;
            paint.setColor(Color.rgb(58, 67, 76));
            canvas.drawLine(x, top, x, bottom, paint);
            if (scope == null || scope.extended == null) continue;
            List<? extends ExtendedLineEvents.TimedEvent> events = scope.extended.events(TYPES[index]);
            for (ExtendedLineEvents.TimedEvent event : events) {
                ExtendedLineEvents.TimedEvent shown = event == selection && candidate != null ? candidate : event;
                if (shown.endTime.toDouble() < minimumBeat || shown.startTime.toDouble() > maximumBeat) continue;
                RectF rect = rect(index, shown);
                if (rect.bottom < top || rect.top > bottom) continue;
                boolean invalid = candidate != null && event == selection && !valid(candidate);
                drawEvent(canvas, rect, shown, index, selectedItems.containsKey(event), invalid);
            }
        }
        if (gesture == Gesture.CREATE && candidate != null) {
            int index = java.util.Arrays.asList(TYPES).indexOf(type);
            drawEvent(canvas, rect(index, candidate), candidate, index, true, !valid(candidate));
        }
        paint.setColor(Color.rgb(58, 67, 76));
        canvas.drawLine(right, top, right, bottom, paint);
        view.drawCurrentBeatLine(canvas, left, right, bottom);
        canvas.restoreToCount(save);

        save = canvas.save();
        canvas.clipRect(left, top, right, top + HEADER_TOUCH_HEIGHT_DP * d);
        for (int index = 0; index < TYPES.length; index++) {
            float x = left + index * laneWidth() - scroll;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(10.5f * d);
            paint.setColor(Color.LTGRAY);
            canvas.drawText(view.getResources().getString(LABELS[index]), x + laneWidth() / 2, top + 17 * d, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restoreToCount(save);
    }

    private void drawEvent(Canvas canvas, RectF rect, ExtendedLineEvents.TimedEvent event,
                           int index, boolean selected, boolean invalid) {
        float d = view.density;
        Paint paint = view.paint;
        paint.setColor(invalid ? EditorSurfaceView.INVALID_PREVIEW : COLORS[index]);
        canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
        int save = canvas.save();
        canvas.clipRect(rect);
        if (view.settings.drawEventCurves) {
            double startBeat = event.startTime.toDouble();
            double duration = event.endTime.toDouble() - startBeat;
            view.drawTimelineEasingCurve(canvas, rect, invalid, selected,
                    t -> event.progressAt(startBeat + duration * t));
        }
        if (view.settings.drawEventNumbers && rect.height() > 30 * d) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(7.5f * d);
            paint.setColor(Color.WHITE);
            canvas.drawText(view.ellipsize(value(event, false), rect.width() - 8 * d),
                    rect.centerX(), rect.top + 9 * d, paint);
            canvas.drawText(view.ellipsize(value(event, true), rect.width() - 8 * d),
                    rect.centerX(), rect.bottom - 3 * d, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        canvas.restoreToCount(save);
        if (selected) {
            view.drawSelection(canvas, rect);
            if (selectedItems.size() <= 1) view.drawEventHandles(canvas, rect.centerX(),
                    view.beatToY(event.startTime.toDouble(), view.timelineY(bottom)),
                    view.beatToY(event.endTime.toDouble(), view.timelineY(bottom)), invalid);
        }
    }

    private static String value(ExtendedLineEvents.TimedEvent event, boolean start) {
        if (event instanceof ExtendedLineEvents.NumericEvent) {
            ExtendedLineEvents.NumericEvent n = (ExtendedLineEvents.NumericEvent) event;
            return EditorSurfaceView.compactValue(start ? n.start : n.end);
        }
        if (event instanceof ExtendedLineEvents.ColorEvent) {
            ExtendedLineEvents.ColorEvent c = (ExtendedLineEvents.ColorEvent) event;
            return String.format(Locale.US, "#%06X", (start ? c.startRgb : c.endRgb) & 0xFFFFFF);
        }
        ExtendedLineEvents.TextEvent t = (ExtendedLineEvents.TextEvent) event;
        String text = start ? t.start : t.end;
        return text == null ? "" : text.replace('\n', ' ');
    }

    boolean down(float x, float y) {
        syncScope();
        if (view.tool != EditorSurfaceView.Tool.SELECT && view.tool != EditorSurfaceView.Tool.EVENT) return true;
        int index = laneAt(x);
        if (index < 0) return true;
        downX = x; downY = y; initialBeat = view.currentBeat; initialScroll = scroll;
        moved = false; horizontal = false; handleMoved = false; candidate = null;
        if (view.tool == EditorSurfaceView.Tool.EVENT) {
            view.clearSelection();
            type = TYPES[index]; candidate = fresh(type, snapped(y)); gesture = Gesture.CREATE;
            view.invalidate(); return true;
        }
        if (selectionCount() == 1 && type == TYPES[index]) {
            float sy = view.beatToY(selection.startTime.toDouble(), view.timelineY(bottom));
            float ey = view.beatToY(selection.endTime.toDouble(), view.timelineY(bottom));
            if (Math.abs(y - sy) <= 12 * view.density || Math.abs(y - ey) <= 12 * view.density) {
                gesture = Math.abs(y - sy) <= Math.abs(y - ey) ? Gesture.START : Gesture.END;
                candidate = selection.copy();
                return true;
            }
        }
        ExtendedLineEvents.TimedEvent hit = hit(index, x, y);
        if (hit != null) {
            view.clearSelection();
            type = TYPES[index]; selection = hit; selectedItems.put(hit, type);
            gesture = Gesture.SELECT;
            view.activateEditPanel();
        } else {
            if (selectedItems.isEmpty()) type = TYPES[index];
            gesture = Gesture.PAN;
            view.beginPanGesture();
        }
        view.invalidate();
        return true;
    }

    boolean move(float x, float y) {
        if (gesture == Gesture.NONE) return false;
        float dx = x - downX, dy = y - downY;
        if (!moved && Math.max(Math.abs(dx), Math.abs(dy)) > 3 * view.density) {
            moved = true;
            horizontal = gesture == Gesture.PAN && Math.abs(dx) > Math.abs(dy) * 1.2f;
        }
        if (horizontal) {
            scroll = StoryboardTimelineGeometry.clampScroll(initialScroll - dx,
                    right - left, view.density, TYPES.length);
            candidate = null;
        } else if (gesture == Gesture.PAN && moved) {
            view.currentBeat = Math.max(0, initialBeat + dy / view.pixelsPerBeat * view.settings.timelineScrollSpeed);
        } else if (candidate != null) {
            if (gesture == Gesture.START || gesture == Gesture.END) {
                handleMoved |= Math.abs(dy) >= .5f * view.density;
                if (gesture == Gesture.START) candidate.startTime = snapped(y);
                else candidate.endTime = snapped(y);
            }
            else if (gesture == Gesture.CREATE) {
                creationEnd(y);
            }
        }
        view.invalidate();
        return true;
    }

    boolean up(float x, float y) {
        Gesture previous = gesture;
        if (previous == Gesture.NONE) return false;
        if (previous == Gesture.CREATE) creationEnd(y);
        ExtendedLineEvents.TimedEvent edited = candidate;
        cancel();
        if (previous == Gesture.CREATE && edited != null) {
            if (result(view.addStoryboardEvent(type, edited))) {
                select(type, edited); view.activateEditPanel();
            }
        } else if (handleMoved && edited != null && selection != null
                && (previous == Gesture.START || previous == Gesture.END)) {
            result(view.applyStoryboardEvent(type, selection, edited));
        } else if (previous == Gesture.PAN) {
            if (!moved) {
                boolean clearing = view.clearTimelineSelectionOnTap();
                if (!clearing && view.isTapToSeekEnabled())
                    view.currentBeat = Math.max(0, view.snapBeat(beatAt(y)));
            }
            view.finishPlaybackScrub();
        }
        view.gestureMode = EditorSurfaceView.GestureMode.NONE;
        view.invalidate();
        return true;
    }

    private boolean valid(ExtendedLineEvents.TimedEvent event) {
        return scope != null && StoryboardEventValidator.validate(scope.extended, type,
                event, gesture == Gesture.CREATE ? null : selection) == StoryboardEventValidator.Error.NONE;
    }

    private void creationEnd(float y) {
        candidate.endTime = snapped(y);
        if (candidate.endTime.compareTo(candidate.startTime) <= 0)
            candidate.endTime = candidate.startTime.plus(new BeatTime(0, 1, view.subdivision));
    }

    private ExtendedLineEvents.TimedEvent fresh(StoryboardEventType type, BeatTime beat) {
        ExtendedLineEvents.TimedEvent event;
        if (type == StoryboardEventType.COLOR) event = new ExtendedLineEvents.ColorEvent();
        else if (type == StoryboardEventType.TEXT) event = new ExtendedLineEvents.TextEvent();
        else {
            ExtendedLineEvents.NumericEvent numeric = new ExtendedLineEvents.NumericEvent();
            if (type == StoryboardEventType.SCALE_X || type == StoryboardEventType.SCALE_Y) numeric.start = numeric.end = 1;
            event = numeric;
        }
        event.startTime = beat;
        event.endTime = beat.plus(new BeatTime(0, 1, view.subdivision));
        return event;
    }
    private void open(ExtendedLineEvents.TimedEvent event, boolean creating) {
        if (view.callback != null) view.callback.requestStoryboardProperties(type, event, creating);
    }
    boolean hasClipboard() { return !copied.isEmpty() && clipboardActive; }
    void deactivateClipboard() { clipboardActive = false; }
    void copy() {
        syncScope();
        if (selectedItems.isEmpty()) return;
        copied.clear();
        for (java.util.Map.Entry<ExtendedLineEvents.TimedEvent, StoryboardEventType> item : selectedItems.entrySet())
            copied.add(new Item(item.getValue(), item.getKey().copy()));
        clipboardActive = true;
    }
    void cut() { if (selected() != null) { copy(); delete(); } }
    void paste() {
        if (!hasClipboard()) return;
        syncScope();
        if (scope == null) return;
        if (scope.extended == null) scope.extended = new ExtendedLineEvents();
        BeatTime first = copied.get(0).event.startTime;
        for (Item item : copied) if (item.event.startTime.compareTo(first) < 0) first = item.event.startTime;
        BeatTime delta = view.getCurrentBeatTime().minus(first);
        java.util.List<EditHistory.Command> commands = new java.util.ArrayList<>();
        java.util.LinkedHashMap<ExtendedLineEvents.TimedEvent, StoryboardEventType> added = new java.util.LinkedHashMap<>();
        for (Item item : copied) {
            ExtendedLineEvents.TimedEvent event = item.event.copy();
            event.startTime = event.startTime.plus(delta); event.endTime = event.endTime.plus(delta);
            StoryboardEventValidator.Error error = StoryboardEventValidator.validate(scope.extended, item.type, event, null);
            for (java.util.Map.Entry<ExtendedLineEvents.TimedEvent, StoryboardEventType> prior : added.entrySet())
                if (prior.getValue() == item.type && event.startTime.compareTo(prior.getKey().endTime) < 0
                        && prior.getKey().startTime.compareTo(event.endTime) < 0)
                    error = StoryboardEventValidator.Error.EVENT_OVERLAP;
            if (error != StoryboardEventValidator.Error.NONE) {
                result(error == StoryboardEventValidator.Error.EVENT_OVERLAP
                        ? EditorDocumentView.StoryboardApplyResult.EVENT_OVERLAP
                        : EditorDocumentView.StoryboardApplyResult.INVALID_VALUES);
                return;
            }
            added.put(event, item.type);
            commands.add(StoryboardEditCommand.add(scope.extended, item.type, event));
        }
        execute(commands, added); view.activateEditPanel();
    }
    void edit() { if (selectionCount() == 1) open(selection, false); }
    void duplicate() {
        if (selectionCount() != 1) return;
        ExtendedLineEvents.TimedEvent copy = selection.copy();
        copy.startTime = selection.endTime;
        copy.endTime = copy.startTime.plus(selection.endTime.minus(selection.startTime));
        open(copy, true);
    }
    void delete() {
        syncScope();
        if (selectedItems.isEmpty()) return;
        java.util.List<EditHistory.Command> commands = new java.util.ArrayList<>();
        for (java.util.Map.Entry<ExtendedLineEvents.TimedEvent, StoryboardEventType> item : selectedItems.entrySet())
            commands.add(StoryboardEditCommand.delete(scope.extended, item.getValue(), item.getKey()));
        execute(commands, new java.util.LinkedHashMap<>());
    }
    private void execute(java.util.List<EditHistory.Command> commands,
                         java.util.LinkedHashMap<ExtendedLineEvents.TimedEvent, StoryboardEventType> after) {
        java.util.LinkedHashMap<ExtendedLineEvents.TimedEvent, StoryboardEventType> before = new java.util.LinkedHashMap<>(selectedItems);
        JudgeLine owner = scope;
        view.history.execute(new EditHistory.Command() {
            @Override public void apply() {
                for (EditHistory.Command command : commands) command.apply();
                restoreSelection(owner, after);
            }
            @Override public void revert() {
                for (int index = commands.size() - 1; index >= 0; index--) commands.get(index).revert();
                restoreSelection(owner, before);
            }
        });
        view.invalidate();
    }
    private void restoreSelection(JudgeLine owner,
                                  java.util.Map<ExtendedLineEvents.TimedEvent, StoryboardEventType> items) {
        syncScope();
        if (scope != owner) return;
        selectedItems.clear(); selectedItems.putAll(items); primarySelection();
    }
    private ExtendedLineEvents.TimedEvent hit(int index, float x, float y) {
        ExtendedLineEvents.TimedEvent hit = null;
        if (scope != null && scope.extended != null)
            for (ExtendedLineEvents.TimedEvent event : scope.extended.events(TYPES[index]))
                if (rect(index, event).contains(x, y)) hit = event;
        return hit;
    }
    boolean addSelectionAt(float x, float y) {
        syncScope();
        int index = laneAt(x);
        if (index < 0) return false;
        ExtendedLineEvents.TimedEvent event = hit(index, x, y);
        if (event == null) return false;
        selectedItems.put(event, TYPES[index]); primarySelection(); view.activateEditPanel();
        return true;
    }
    void selectRectangle(RectF bounds, boolean add) {
        syncScope();
        if (scope == null || scope.extended == null) return;
        for (int index = 0; index < TYPES.length; index++) {
            float laneLeft = left + index * laneWidth() - scroll;
            if (laneLeft >= right || laneLeft + laneWidth() <= left) continue;
            if (laneLeft + laneWidth() < bounds.left || laneLeft > bounds.right) continue;
            for (ExtendedLineEvents.TimedEvent event : scope.extended.events(TYPES[index])) {
                RectF eventRect = rect(index, event);
                if (eventRect.bottom >= bounds.top && eventRect.top <= bounds.bottom) {
                    if (add) selectedItems.put(event, TYPES[index]); else selectedItems.remove(event);
                }
            }
        }
        primarySelection(); view.invalidate();
    }
    void glue() { if (selectionCount() == 1) result(view.glueStoryboardEvent(type, selection)); }
    void split() { if (selectionCount() == 1) result(view.splitStoryboardEvent(type, selection, view.getCurrentBeatTime())); }
    private boolean result(EditorDocumentView.StoryboardApplyResult result) {
        if (result == EditorDocumentView.StoryboardApplyResult.APPLIED) return true;
        int message;
        switch (result) {
            case EVENT_OVERLAP: message = R.string.validation_event_overlap; break;
            case NO_PREVIOUS: message = R.string.storyboard_no_previous; break;
            case NO_CHANGE: message = R.string.storyboard_glue_no_change; break;
            case SPLIT_OUTSIDE: message = R.string.storyboard_split_inside; break;
            case TARGET_NOT_FOUND: message = R.string.validation_target_changed; break;
            default: message = R.string.validation_invalid_number_or_beat;
        }
        if (view.callback != null) view.callback.showMessage(view.getResources().getString(message));
        return false;
    }
}
