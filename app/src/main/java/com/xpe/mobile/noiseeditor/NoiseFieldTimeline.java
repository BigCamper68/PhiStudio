package com.xpe.mobile.noiseeditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.EditorTimelineMetrics;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldEdits.Channel;
import com.xpe.mobile.preview.NoiseFieldEvaluator;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** ShaderEditorTimeline's vertical grid, block selection, S/E handles, pan and pinch behavior. */
@SuppressLint("ViewConstructor")
final class NoiseFieldTimeline extends View {
    private final NoiseFieldEditorView owner;
    private final int touchSlop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rightCurve = new RectF();
    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private float pinchSpan, horizontal, downX, downY, lastX, lastY;
    private boolean holdingTimeline, horizontalGesture, dragging, multiTouch;
    private Hit target;
    private int handle;
    private NoiseField original;

    static final class Hit {
        final RectF rect = new RectF();
        int field, phase, key, nextKey;
        double start, end;
        float startY, endY;
        boolean point;
        Channel channel;
    }
    NoiseFieldTimeline(Context context, NoiseFieldEditorView owner) {
        super(context); this.owner = owner;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        UiText.description(this, R.string.noise_timeline);
    }
    void relocalizeUi() { invalidate(); }
    private int laneCount() { return owner.overview ? Math.max(1, owner.document.count()) : 5; }
    private float laneWidth() {
        return Math.max(owner.dp(owner.overview ? 76 : 64), (getWidth() - owner.dp(36)) / (float) laneCount());
    }
    private void lanes() {
        horizontal = Math.max(0, Math.min(horizontal,
                Math.max(0, laneCount() * laneWidth() - getWidth() + owner.dp(36))));
    }
    private void revealLane(int lane) {
        float left = lane * laneWidth(), available = getWidth() - owner.dp(36);
        if (left < horizontal) horizontal = left;
        else if (left + laneWidth() > horizontal + available) horizontal = left + laneWidth() - available;
        lanes(); invalidate();
    }
    void reveal(int index) {
        if (owner.overview && index >= 0 && index < owner.document.count()) revealLane(index);
        else revealLane(owner.document.channel == null ? 0 : owner.document.channel.ordinal() + 2);
    }
    void revealChannel(Channel channel) {
        if (!owner.overview) revealLane(channel == null ? 0 : channel.ordinal() + 2);
    }
    float y(double seconds) {
        double value = EditorTimelineMetrics.baselineY(getHeight(), owner.density)
                - (owner.axis(seconds) - owner.axis(owner.time())) * owner.editor.timelineScale();
        return (float) Math.max(-1000000, Math.min(1000000, value));
    }
    private double axisY(float y) {
        return owner.axis(owner.time()) + (EditorTimelineMetrics.baselineY(getHeight(), owner.density) - y)
                / owner.editor.timelineScale();
    }
    private Hit hit(int field, int phase, int key, int nextKey, double start, double end, int lane) {
        float head = owner.dp(30), gutter = owner.dp(36);
        float x = gutter + lane * laneWidth() - horizontal, a = y(start), b = y(end);
        if (a < head || b > getHeight() || x + laneWidth() < gutter || x > getWidth()) return null;
        if (hitCount == hits.size()) hits.add(new Hit());
        Hit hit = hits.get(hitCount++);
        hit.field = field; hit.phase = phase; hit.key = key; hit.nextKey = nextKey;
        hit.start = start; hit.end = end; hit.startY = a; hit.endY = b;
        hit.point = key >= 0 && nextKey < 0; hit.channel = null;
        hit.rect.set(x + owner.dp(3), b, x + laneWidth() - owner.dp(3), Math.max(b + owner.dp(6), a));
        return hit;
    }
    private boolean selected(Hit hit) {
        return hit.field == owner.document.selected && (hit.key < 0
                || hit.channel == owner.document.channel && hit.key == owner.document.selectedKey);
    }
    private void block(Canvas canvas, Hit hit, int color) {
        if (hit == null) return;
        owner.editor.drawTimelineBlock(canvas, hit.rect, color);
        if (selected(hit)) {
            if (hit.point) owner.editor.drawTimelineKeySelection(canvas, hit.rect, hit.startY);
            else owner.editor.drawTimelineSelection(canvas, hit.rect, hit.startY, hit.endY);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(17, 23, 30));
        hitCount = 0; lanes();
        float head = owner.dp(30), gutter = owner.dp(36);
        double current = owner.axis(owner.time());
        owner.editor.drawTimelineRows(canvas, 0, head, getWidth(), getHeight(), current,
                owner.editor.timelineScale(), owner.beats ? owner.editor.getSubdivision() : 4);
        paint.setStrokeWidth(owner.density); paint.setColor(Color.rgb(58, 67, 76));
        int laneCount = laneCount();
        for (int i = 0; i <= laneCount; i++) {
            float x = gutter + i * laneWidth() - horizontal;
            if (x >= gutter && x <= getWidth()) canvas.drawLine(x, head, x, getHeight(), paint);
        }
        int save = canvas.save(); canvas.clipRect(gutter, head, getWidth(), getHeight());
        if (owner.overview) {
            for (int i = 0; i < owner.document.count(); i++) {
                NoiseField field = i == owner.document.selected ? owner.timelineField() : owner.document.chart.noiseFields.get(i);
                Hit hit = hit(i, 0, -1, -1, field.appear, field.disappear, i);
                block(canvas, hit, field.color == NoiseField.DEFAULT_COLOR
                        ? field.subtract ? 0xff725ea5 : 0xff2c8d81 : field.color);
                if (hit != null) {
                    paint.setColor(Color.WHITE); paint.setTextSize(owner.dp(9));
                    canvas.drawText("#" + (i + 1), hit.rect.left + owner.dp(3),
                            Math.min(hit.rect.bottom - owner.dp(2), Math.max(head + owner.dp(10), hit.rect.top + owner.dp(12))), paint);
                }
            }
        } else {
            NoiseField field = owner.timelineField();
            if (field != null) {
                block(canvas, hit(owner.document.selected, 0, -1, -1, field.appear, field.disappear, 0),
                        field.color == NoiseField.DEFAULT_COLOR ? 0xff2c8d81 : field.color);
                block(canvas, hit(owner.document.selected, 1, -1, -1, field.enable, field.disable, 1), 0xff655d93);
                for (Channel channel : Channel.values()) drawAnimation(canvas, channel, channel.ordinal() + 2);
            }
        }
        canvas.restoreToCount(save);
        save = canvas.save(); canvas.clipRect(gutter, 0, getWidth(), head);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setTextSize(owner.dp(10));
        int[] names = {R.string.noise_field_tab, R.string.noise_activation, R.string.noise_move,
                R.string.noise_scale, R.string.noise_rotate};
        for (int i = 0; i < laneCount; i++) {
            float x = gutter + i * laneWidth() - horizontal;
            if (x + laneWidth() < gutter || x > getWidth()) continue;
            String label = owner.overview
                    ? (i < owner.document.count() ? "#" + (i + 1) + " " + owner.document.name(i) : "")
                    : owner.tr(names[i]);
            int laneSave = canvas.save();
            canvas.clipRect(x + owner.dp(3), 0, x + laneWidth() - owner.dp(3), head);
            canvas.drawText(label, x + owner.dp(3), owner.dp(18), paint);
            canvas.restoreToCount(laneSave);
        }
        canvas.restoreToCount(save);
        paint.setColor(Color.rgb(17, 23, 30)); canvas.drawRect(0, 0, gutter, getHeight(), paint);
        paint.setTextSize(owner.dp(10)); paint.setTextAlign(Paint.Align.RIGHT); paint.setColor(Color.LTGRAY);
        owner.editor.drawTimelineBeatNumbers(canvas, 0, head, gutter, getHeight(), current, owner.editor.timelineScale(), true);
        canvas.drawText(owner.tr(owner.beats ? R.string.shader_axis_beat : R.string.shader_seconds_unit),
                gutter - owner.dp(4), owner.dp(18), paint);
        paint.setTextAlign(Paint.Align.LEFT); owner.editor.drawTimelineBaseline(canvas, getWidth(), getHeight());
    }
    private void drawAnimation(Canvas canvas, Channel channel, int lane) {
        NoiseField field = owner.timelineField();
        List<Integer> keys = NoiseFieldEdits.sortedKeys(field, channel);
        for (int i = 0; i < keys.size(); i++) {
            int index = keys.get(i), next = i + 1 < keys.size() ? keys.get(i + 1) : -1;
            JSONObject key = field.toJson().optJSONArray(channel.json).optJSONObject(index);
            double start = key.optDouble("time"), end = next < 0 ? start
                    : field.toJson().optJSONArray(channel.json).optJSONObject(next).optDouble("time");
            Hit hit = hit(owner.document.selected, 2, index, next, start, end, lane);
            if (hit != null) hit.channel = channel;
            block(canvas, hit, 0xff655d93);
            if (hit == null || hit.point || !owner.editor.timelineCurvesVisible()) continue;
            int easing = key.optInt(channel == Channel.ROTATE ? "easeType" : "easeTypeX");
            owner.editor.drawTimelineEasingCurve(canvas, hit.rect, false, selected(hit),
                    value -> NoiseFieldEvaluator.ease(easing, (float) value));
            if (channel != Channel.ROTATE) {
                int easeY = key.optInt("easeTypeY");
                if (easeY != easing) {
                    rightCurve.set(hit.rect); rightCurve.left = rightCurve.centerX();
                    owner.editor.drawTimelineEasingCurve(canvas, rightCurve, false, selected(hit),
                            value -> NoiseFieldEvaluator.ease(easeY, (float) value));
                }
            }
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false; pinchSpan = 0;
            owner.editor.shaderBeginPan(); holdingTimeline = true; owner.invalidatePreview();
        }
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (!holdingTimeline) return true;
        if (event.getPointerCount() > 1) {
            if (!multiTouch) cancelEditing();
            multiTouch = true;
            float span = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
            if (action == MotionEvent.ACTION_MOVE && pinchSpan > owner.dp(2) && span > owner.dp(2)) {
                owner.editor.scaleShaderTimeline(span / pinchSpan); invalidate();
            }
            pinchSpan = action == MotionEvent.ACTION_POINTER_UP ? 0 : span;
            return true;
        }
        if (multiTouch) { if (action == MotionEvent.ACTION_UP) finishHold(); return true; }
        if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX(); downY = lastY = event.getY();
            horizontalGesture = false; target = null; dragging = false; handle = 0; original = null;
            float bestDistance = Float.MAX_VALUE;
            if (downY > owner.dp(30) && downX >= owner.dp(36) && downX <= getWidth()) {
                for (int i = 0; i < hitCount; i++) {
                    Hit hit = hits.get(i); if (!selected(hit)) continue;
                    float radius = Math.min(owner.dp(26), (hit.rect.width() + owner.dp(6)) * .48f);
                    float a = (float) Math.hypot(downX - hit.rect.centerX(), downY - hit.startY);
                    float b = hit.point ? Float.MAX_VALUE : (float) Math.hypot(downX - hit.rect.centerX(), downY - hit.endY);
                    float distance = Math.min(a, b) - .5f * owner.density;
                    if (distance <= radius && distance <= bestDistance) {
                        target = hit; handle = a <= b ? 1 : 2; bestDistance = distance;
                    }
                }
                if (target == null) for (int i = hitCount - 1; i >= 0; i--) {
                    Hit hit = hits.get(i);
                    if (downX >= hit.rect.left && downX <= hit.rect.right
                            && downY >= hit.rect.top - .18f * owner.editor.timelineScale()
                            && downY <= hit.rect.bottom + .18f * owner.editor.timelineScale()) { target = hit; break; }
                }
            }
            if (target != null) {
                Hit chosen = target; target = new Hit();
                target.rect.set(chosen.rect); target.field = chosen.field; target.phase = chosen.phase;
                target.key = chosen.key; target.nextKey = chosen.nextKey; target.start = chosen.start; target.end = chosen.end;
                target.startY = chosen.startY; target.endY = chosen.endY; target.point = chosen.point; target.channel = chosen.channel;
                original = owner.document.chart.noiseFields.get(target.field);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) { movePointer(event.getX(), event.getY()); return true; }
        if (action == MotionEvent.ACTION_UP) {
            if (!dragging && Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) movePointer(event.getX(), event.getY());
            if (dragging && target != null && handle != 0) { updateHandle(event.getY()); owner.endGesture(); }
            else if (!dragging && target != null) {
                if (target.key >= 0) {
                    owner.document.selectKey(target.channel, target.key); owner.rebuild();
                } else if (owner.overview) {
                    owner.overview = false; owner.selectField(target.field);
                } else if (owner.document.channel != null) owner.selectChannel(null);
                invalidate();
            } else if (!dragging && target == null && downY > owner.dp(30)) {
                boolean clearing = owner.editor.clearTimelineSelectionOnTap();
                if (owner.document.selected >= 0) {
                    clearing = true;
                    if (owner.document.selectedKey >= 0) owner.document.selectedKey = -1;
                    else if (owner.overview) owner.document.select(-1);
                    else clearing = false;
                    owner.rebuild();
                }
                if (!clearing && owner.editor.isTapToSeekEnabled()) {
                    double snap = owner.beats ? 1.0 / owner.editor.getSubdivision() : .01;
                    owner.editor.shaderPanTo(Math.max(0, owner.seconds(Math.round(axisY(event.getY()) / snap) * snap)));
                }
                owner.invalidatePreview();
            }
            finishHold(); if (!dragging) performClick(); dragging = false; return true;
        }
        return true;
    }
    private void movePointer(float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        boolean handleMoved = target != null && handle != 0 && Math.abs(y - downY) >= .5f * owner.density;
        if (!dragging && (handleMoved || Math.hypot(x - downX, y - downY) > touchSlop)) {
            dragging = true;
            horizontalGesture = handle == 0 && (downY < owner.dp(30) || Math.abs(x - downX) > Math.abs(y - downY));
            if (target != null && handle != 0 && !owner.beginGesture()) { target = null; handle = 0; }
        }
        if (!dragging) return;
        lastX = x; lastY = y;
        if (horizontalGesture) { horizontal = Math.max(0, horizontal - dx); lanes(); invalidate(); return; }
        if (handle == 0) {
            owner.editor.shaderPanTo(owner.seconds(Math.max(0, owner.axis(owner.time())
                    + dy / owner.editor.timelineScale() * owner.editor.timelineScrollMultiplier())));
            owner.invalidatePreview(); return;
        }
        updateHandle(y);
    }
    private void updateHandle(float pointerY) {
        if (target == null || original == null) return;
        double delta = -(pointerY - downY) / owner.editor.timelineScale() * owner.editor.eventScrollMultiplier();
        double first = owner.axis(target.start), last = owner.axis(target.end);
        double snap = owner.beats ? 1.0 / owner.editor.getSubdivision() : .01;
        double a = handle == 2 ? first : owner.snap ? Math.round((first + delta) / snap) * snap : first + delta;
        double b = handle == 1 ? last : owner.snap ? Math.round((last + delta) / snap) * snap : last + delta;
        if (!owner.snap) snap = 1e-6;
        if (!target.point) {
            if (handle == 1) a = Math.min(a, b - snap);
            if (handle == 2) b = Math.max(b, a + snap);
        }
        try {
            NoiseField next;
            if (target.key < 0) {
                int phase = target.phase == 0 ? (handle == 1 ? 0 : 3) : (handle == 1 ? 1 : 2);
                next = NoiseFieldEdits.phase(original, phase, owner.seconds(handle == 1 ? a : b));
            } else {
                Channel channel = target.channel;
                int index = handle == 1 ? target.key : target.nextKey;
                if (index < 0) return;
                JSONObject key = NoiseFieldEdits.copy(NoiseFieldEdits.key(original, channel, index))
                        .put("time", owner.seconds(handle == 1 ? a : b));
                next = NoiseFieldEdits.withKey(original, channel, index, key);
            }
            owner.draftGesture(next);
        } catch (JSONException ex) { owner.error(); }
    }
    private void finishHold() {
        if (holdingTimeline) { holdingTimeline = false; owner.editor.shaderEndPan(); }
    }
    private void cancelEditing() {
        owner.cancelGesture(); dragging = false; target = null; original = null; owner.invalidatePreview();
    }
    void cancel() { cancelEditing(); finishHold(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cancel(); lanes();
        if (ow == 0 && w > 0) reveal(owner.document.selected);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
