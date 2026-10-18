package com.xpe.mobile;

import android.annotation.SuppressLint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import com.xpe.mobile.editor.EditorTimelineMetrics;
import com.xpe.mobile.preview.ShaderTween;
import com.xpe.mobile.shadereditor.ShaderDocument;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** ShaderEditorTimeline view extracted from ShaderEditorView. */
@SuppressLint("ViewConstructor") // Created programmatically with its owning ShaderEditorView.
final class ShaderEditorTimeline extends View {
    private final ShaderEditorView delegateOwner;
    private final int touchSlop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private float pinchSpan;
    private boolean holdingTimeline;
    private float horizontal, downX, downY, lastX, lastY;
    private boolean horizontalGesture;
    private int target = -1, handle;
    private boolean dragging, animation, multiTouch;
    private double originalStart, originalEnd;
    private final List<String> lanes = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private long cachedRevision = -1;
    private int[] effectLanes = new int[0];
    private double[] starts = new double[0], ends = new double[0];
    private String[] labels = new String[0];
    private int hitCount;
    private long animationRevision = -1;
    private int animationSelection = -1;
    private String animationParameter, animationTitle;
    private final List<AnimationBlock> animationBlocks = new ArrayList<>();
    private final class AnimationBlock {
        final double start, end;
        final double[] from, to;
        final int easing;
        final float left, right;
        AnimationBlock(JSONObject event, int size) {
            start = delegateOwner.shaderAnimationForms.eventTime(event, "startTime");
            end = delegateOwner.shaderAnimationForms.eventTime(event, "endTime");
            from = delegateOwner.document.components(event.opt("start"), size);
            to = delegateOwner.document.components(event.opt("end"), size);
            easing = event.optInt("easingType", 1);
            left = (float) event.optDouble("easingLeft", 0);
            right = (float) event.optDouble("easingRight", 1);
        }
    }
    private void prepareAnimation() {
        if (animationRevision == delegateOwner.document.revision() && animationSelection == delegateOwner.document.selected
            && java.util.Objects.equals(animationParameter, delegateOwner.parameter))
            return;
        animationRevision = delegateOwner.document.revision();
        animationSelection = delegateOwner.document.selected;
        animationParameter = delegateOwner.parameter;
        animationTitle = delegateOwner.parameter + " · parameter events";
        animationBlocks.clear();
        int size = 0;
        for (ShaderDocument.Parameter param : delegateOwner.document.parameters())
            if (param.name.equals(delegateOwner.parameter)) {
                size = param.size;
                break;
            }
        if (size == 0)
            return;
        JSONArray events = delegateOwner.document.selected().optJSONObject("vars").optJSONArray(delegateOwner.parameter);
        for (int i = 0; i < events.length(); i++)
            animationBlocks.add(new AnimationBlock(events.optJSONObject(i), size));
    }
    private final class Hit {
        final RectF rect = new RectF();
        int index;
        float startY, endY;
    }
    private Hit hit(float left, float top, float right, float bottom, int index) {
        if (hitCount == hits.size())
            hits.add(new Hit());
        Hit hit = hits.get(hitCount++);
        hit.rect.set(left, top, right, bottom);
        hit.index = index;
        if (animation()) {
            hit.startY = y(animationBlocks.get(index).start);
            hit.endY = y(animationBlocks.get(index).end);
        } else {
            hit.startY = y(starts[index]);
            hit.endY = y(ends[index]);
        }
        return hit;
    }
    ShaderEditorTimeline(Context context, ShaderEditorView delegateOwner) {
        super(context);
        this.delegateOwner = delegateOwner;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setContentDescription("Vertical shader effects timeline");
    }
    private boolean animation() {
        if (delegateOwner.page != 2 || delegateOwner.parameter == null || delegateOwner.document.selected() == null)
            return false;
        Object value = delegateOwner.document.selected().optJSONObject("vars") == null
            ? null
            : delegateOwner.document.selected().optJSONObject("vars").opt(delegateOwner.parameter);
        return delegateOwner.document.animated(value);
    }
    private void lanes() {
        if (cachedRevision != delegateOwner.document.revision()) {
            cachedRevision = delegateOwner.document.revision();
            lanes.clear();
            int count = delegateOwner.document.count();
            effectLanes = new int[count];
            starts = new double[count];
            ends = new double[count];
            labels = new String[count];
            for (int i = 0; i < count; i++) {
                String key = key(i);
                if (!lanes.contains(key))
                    lanes.add(key);
                effectLanes[i] = lanes.indexOf(key);
                starts[i] = delegateOwner.document.start(i);
                ends[i] = delegateOwner.document.end(i);
                labels[i] = "#" + (i + 1);
            }
        }
        horizontal = Math.max(0,
            Math.min(
                horizontal, Math.max(0, lanes.size() * laneWidth() - getWidth() + delegateOwner.dp(36))));
    }
    private float laneWidth() {
        return Math.max(
            delegateOwner.dp(64), (getWidth() - delegateOwner.dp(36)) / (float) Math.max(1, Math.min(5, lanes.size())));
    }
    private String key(int i) {
        JSONObject e = delegateOwner.document.effect(i);
        return (e.optBoolean("global") ? "G · " : "L · ") + e.optString("shader");
    }
    void reveal(int index) {
        if (index < 0)
            return;
        lanes();
        int lane = lanes.indexOf(key(index));
        horizontal = Math.max(0, lane * laneWidth() - getWidth() / 2f + laneWidth() / 2);
    }
    private float y(double seconds) {
        return EditorTimelineMetrics.baselineY(getHeight(), delegateOwner.density)
            - (float) (delegateOwner.document.axis(seconds, delegateOwner.beats)
                  - delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats))
            * delegateOwner.editor.timelineScale();
    }
    private double axisY(float y) {
        return delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats)
            + (EditorTimelineMetrics.baselineY(getHeight(), delegateOwner.density) - y)
            / delegateOwner.editor.timelineScale();
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(17, 23, 30));
        hitCount = 0;
        boolean anim = animation();
        float head = delegateOwner.dp(30), gutter = delegateOwner.dp(36);
        double current = delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats);
        delegateOwner.editor.drawTimelineRows(canvas, 0, head, getWidth(), getHeight(), current,
            delegateOwner.editor.timelineScale(), delegateOwner.beats ? delegateOwner.editor.getSubdivision() : 4);
        lanes();
        paint.setStrokeWidth(delegateOwner.density);
        paint.setColor(Color.rgb(58, 67, 76));
        for (int i = 0; i <= (anim ? 1 : lanes.size()); i++) {
            float x =
                anim ? (i == 0 ? gutter : getWidth()) : gutter + i * laneWidth() - horizontal;
            if (x >= gutter && x <= getWidth())
                canvas.drawLine(x, head, x, getHeight(), paint);
        }
        int save = canvas.save();
        canvas.clipRect(gutter, head, getWidth(), getHeight());
        if (anim) {
            prepareAnimation();
            for (int i = 0; i < animationBlocks.size(); i++) {
                AnimationBlock event = animationBlocks.get(i);
                float a = y(event.start), b = y(event.end);
                if (a < head || b > getHeight())
                    continue;
                RectF rect =
                    hit(gutter + delegateOwner.dp(4), b, getWidth() - delegateOwner.dp(4), Math.max(b + delegateOwner.dp(6), a), i).rect;
                block(canvas, rect, i == delegateOwner.eventIndex, 0xff655d93);
                double[] from = event.from, to = event.to;
                int size = from.length;
                if (size == 1 && delegateOwner.editor.timelineCurvesVisible()) {
                    delegateOwner.editor.drawTimelineEasingCurve(canvas, rect, false, i == delegateOwner.eventIndex,
                        f
                        -> ShaderTween.apply(event.easing, (float) f, event.left, event.right));
                }
                for (int component = 0;
                    component < size && size > 1 && delegateOwner.editor.timelineCurvesVisible();
                    component++) {
                    double min = Math.min(from[component], to[component]),
                           max = Math.max(from[component], to[component]);
                    curve.reset();
                    for (int s = 0; s <= 40; s++) {
                        float f = s / 40f,
                              p = ShaderTween.apply(event.easing, f, event.left, event.right);
                        float x = (float) (rect.left
                            + (component
                                  + (max == min
                                          ? .5
                                          : (from[component]
                                                + (to[component] - from[component]) * p - min)
                                              / (max - min)))
                                * rect.width() / size);
                        if (!Float.isFinite(x))
                            continue;
                        float yy = rect.bottom - f * rect.height();
                        if (s == 0)
                            curve.moveTo(x, yy);
                        else
                            curve.lineTo(x, yy);
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(delegateOwner.dp(1.5f));
                    paint.setColor(ShaderEditorView.CURVE_COLORS[component]);
                    canvas.drawPath(curve, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        } else {
            lanes();
            for (int i = 0; i < delegateOwner.document.count(); i++) {
                int lane = effectLanes[i];
                float x = gutter + lane * laneWidth() - horizontal;
                if (x + laneWidth() < gutter || x > getWidth())
                    continue;
                float a = y(starts[i]), b = y(ends[i]);
                if (a < head || b > getHeight())
                    continue;
                RectF rect =
                    hit(x + delegateOwner.dp(3), b, x + laneWidth() - delegateOwner.dp(3), Math.max(b + delegateOwner.dp(6), a), i).rect;
                block(canvas, rect, i == delegateOwner.document.selected,
                    delegateOwner.document.effect(i).optBoolean("global") ? 0xff725ea5 : 0xff2c8d81);
                paint.setColor(Color.WHITE);
                paint.setTextSize(delegateOwner.dp(9));
                canvas.drawText(labels[i], rect.left + delegateOwner.dp(3),
                    Math.min(rect.bottom - delegateOwner.dp(2), Math.max(head + delegateOwner.dp(10), rect.top + delegateOwner.dp(12))),
                    paint);
            }
        }
        canvas.restoreToCount(save);
        // Names stay in a reserved row, on the same background as the grid.
        save = canvas.save();
        canvas.clipRect(gutter, 0, getWidth(), head);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(delegateOwner.dp(10));
        if (anim)
            canvas.drawText(animationTitle, gutter, delegateOwner.dp(18), paint);
        else
            for (int i = 0; i < lanes.size(); i++) {
                float x = gutter + i * laneWidth() - horizontal;
                if (x + laneWidth() < gutter || x > getWidth())
                    continue;
                String label = lanes.get(i);
                if (label.length() > 13)
                    label = label.substring(0, 12) + "…";
                canvas.drawText(label, x + delegateOwner.dp(3), delegateOwner.dp(18), paint);
            }
        canvas.restoreToCount(save);
        // The beat ruler never moves with the lanes and cannot be covered by blocks.
        paint.setColor(Color.rgb(17, 23, 30));
        canvas.drawRect(0, 0, gutter, getHeight(), paint);
        paint.setTextSize(delegateOwner.dp(10));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(Color.LTGRAY);
        delegateOwner.editor.drawTimelineBeatNumbers(canvas, 0, head, gutter, getHeight(), current,
                delegateOwner.editor.timelineScale(), true);
        paint.setTextSize(delegateOwner.dp(10));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(Color.LTGRAY);
        canvas.drawText(delegateOwner.beats ? "Beat" : "s", gutter - delegateOwner.dp(4), delegateOwner.dp(18), paint);
        paint.setTextAlign(Paint.Align.LEFT);
        delegateOwner.editor.drawTimelineBaseline(canvas, getWidth(), getHeight());
    }
    private void block(Canvas canvas, RectF rect, boolean selected, int color) {
        delegateOwner.editor.drawTimelineBlock(canvas, rect, color);
        if (selected) {
            Hit hit = hits.get(hitCount - 1);
            delegateOwner.editor.drawTimelineSelection(canvas, rect, hit.startY, hit.endY);
        }
    }
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            multiTouch = false;
            pinchSpan = 0;
            delegateOwner.editor.shaderBeginPan();
            holdingTimeline = true;
            delegateOwner.invalidatePreview();
        }
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (!holdingTimeline) return true;
        if (event.getPointerCount() > 1) {
            if (!multiTouch) cancelEditing();
            multiTouch = true;
            float span = (float) Math.hypot(event.getX(1) - event.getX(0),
                    event.getY(1) - event.getY(0));
            if (action == MotionEvent.ACTION_MOVE && pinchSpan > delegateOwner.dp(2) && span > delegateOwner.dp(2)) {
                delegateOwner.editor.scaleShaderTimeline(span / pinchSpan);
                invalidate();
            }
            pinchSpan = action == MotionEvent.ACTION_POINTER_UP ? 0 : span;
            return true;
        }
        if (multiTouch) {
            if (action == MotionEvent.ACTION_UP) finishHold();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            horizontalGesture = false;
            target = -1;
            dragging = false;
            animation = animation();
            handle = 0;
            Hit chosen = null;
            float bestDistance = Float.MAX_VALUE;
            if (downY > delegateOwner.dp(30) && downX >= delegateOwner.dp(36) && downX <= getWidth()) {
                // Only the selected block exposes editable S/E handles.
                for (int i = 0; i < hitCount; i++) {
                    Hit hit = hits.get(i);
                    if (hit.index != (animation ? delegateOwner.eventIndex : delegateOwner.document.selected)) continue;
                    float radius = Math.min(delegateOwner.dp(26), (hit.rect.width() + delegateOwner.dp(6)) * .48f);
                    float startDistance =
                        (float) Math.hypot(downX - hit.rect.centerX(), downY - hit.startY);
                    float endDistance =
                        (float) Math.hypot(downX - hit.rect.centerX(), downY - hit.endY);
                    float distance = Math.min(startDistance, endDistance);
                    distance -= .5f * delegateOwner.density;
                    if (distance <= radius && distance <= bestDistance) {
                        chosen = hit;
                        handle = startDistance <= endDistance ? 1 : 2;
                        bestDistance = distance;
                    }
                }
                if (chosen == null)
                    for (int i = hitCount - 1; i >= 0; i--) {
                        Hit hit = hits.get(i);
                        if (downX >= hit.rect.left && downX <= hit.rect.right
                            && downY >= hit.rect.top - .18f * delegateOwner.editor.timelineScale()
                            && downY <= hit.rect.bottom + .18f * delegateOwner.editor.timelineScale()) {
                            chosen = hit;
                            break;
                        }
                    }
            }
            if (chosen != null) {
                target = chosen.index;
                if (animation) {
                    JSONArray events =
                        delegateOwner.document.selected().optJSONObject("vars").optJSONArray(delegateOwner.parameter);
                    originalStart = delegateOwner.shaderAnimationForms.eventTime(events.optJSONObject(target), "startTime");
                    originalEnd = delegateOwner.shaderAnimationForms.eventTime(events.optJSONObject(target), "endTime");
                } else {
                    originalStart = delegateOwner.document.start(target);
                    originalEnd = delegateOwner.document.end(target);
                }
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            movePointer(event.getX(), event.getY());
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!dragging && Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop)
                movePointer(event.getX(), event.getY());
            if (dragging && target >= 0 && handle != 0) {
                updateHandle(event.getY());
                delegateOwner.document.endGesture();
                delegateOwner.changed(true);
            } else if (!dragging && target >= 0) {
                if (animation) {
                    delegateOwner.eventIndex = target;
                    delegateOwner.buildForm();
                } else if (delegateOwner.document.selected != target) delegateOwner.select(target, false);
                invalidate();
            } else if (!dragging && target < 0 && downY > delegateOwner.dp(30)) {
                boolean clearing = delegateOwner.editor.clearTimelineSelectionOnTap();
                if (delegateOwner.document.selected >= 0) {
                    clearing = true;
                    if (animation && delegateOwner.eventIndex >= 0) delegateOwner.eventIndex = -1;
                    else { delegateOwner.document.selected = -1; delegateOwner.parameter = null; delegateOwner.eventIndex = -1; }
                    delegateOwner.buildForm();
                }
                if (!clearing && delegateOwner.editor.isTapToSeekEnabled()) {
                    double snap = delegateOwner.beats ? 1.0 / delegateOwner.editor.getSubdivision() : .01;
                    delegateOwner.editor.shaderPanTo(Math.max(0,
                            delegateOwner.document.seconds(Math.round(axisY(event.getY()) / snap) * snap, delegateOwner.beats)));
                }
                delegateOwner.invalidatePreview();
            }
            finishHold();
            if (!dragging) performClick();
            dragging = false;
            return true;
        }
        return true;
    }

    private void movePointer(float x, float y) {
        float dx = x - lastX;
        float dy = y - lastY;
        boolean handleMoved =
            target >= 0 && handle != 0 && Math.abs(y - downY) >= .5f * delegateOwner.density;
        if (!dragging
            && (handleMoved
                || Math.hypot(x - downX, y - downY) > touchSlop)) {
            dragging = true;
            horizontalGesture = handle == 0 && (downY < delegateOwner.dp(30)
                    || Math.abs(x - downX) > Math.abs(y - downY));
            if (target >= 0 && handle != 0)
                delegateOwner.document.beginGesture();
        }
        if (!dragging)
            return;
        lastX = x;
        lastY = y;
        if (horizontalGesture) {
            horizontal = Math.max(0, horizontal - dx);
            lanes();
            invalidate();
            return;
        }
        if (handle == 0) {
            delegateOwner.editor.shaderPanTo(delegateOwner.document.seconds(
                Math.max(0,
                    delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats)
                        + dy / delegateOwner.editor.timelineScale() * delegateOwner.editor.timelineScrollMultiplier()),
                delegateOwner.beats));
            delegateOwner.invalidatePreview();
            return;
        }
        updateHandle(y);
    }
    private void updateHandle(float pointerY) {
        double delta = -(pointerY - downY) / delegateOwner.editor.timelineScale()
            * delegateOwner.editor.eventScrollMultiplier(),
               first = delegateOwner.document.axis(originalStart, delegateOwner.beats),
               last = delegateOwner.document.axis(originalEnd, delegateOwner.beats),
               snap = delegateOwner.beats ? 1.0 / delegateOwner.editor.getSubdivision() : .01;
        double a = handle == 2 ? first : Math.round((first + delta) / snap) * snap,
               b = handle == 1 ? last : Math.round((last + delta) / snap) * snap;
        if (handle == 1)
            a = Math.min(a, b - snap);
        if (handle == 2)
            b = Math.max(b, a + snap);
        double start = delegateOwner.document.seconds(a, delegateOwner.beats), end = delegateOwner.document.seconds(b, delegateOwner.beats);
        delegateOwner.safe(() -> {
            if (animation) {
                JSONArray events =
                    ShaderEditorView.copy(delegateOwner.document.selected().optJSONObject("vars").optJSONArray(delegateOwner.parameter));
                JSONObject item = events.optJSONObject(target);
                try {
                    item.put("startTime", delegateOwner.document.timestamp(start))
                        .put("endTime", delegateOwner.document.timestamp(end));
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
                delegateOwner.document.value(delegateOwner.parameter, events);
            } else
                delegateOwner.document.range(target, start, end);
            delegateOwner.changed(false);
        });
    }
    private void finishHold() {
        if (holdingTimeline) {
            holdingTimeline = false;
            delegateOwner.editor.shaderEndPan();
        }
    }
    private void cancelEditing() {
        delegateOwner.document.cancelGesture();
        dragging = false;
        target = -1;
        delegateOwner.changed(false);
    }
    void cancel() {
        cancelEditing();
        finishHold();
    }
    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
