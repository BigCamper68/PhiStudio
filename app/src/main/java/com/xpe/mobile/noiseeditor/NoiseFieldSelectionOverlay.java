package com.xpe.mobile.noiseeditor;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.PreviewViewportCalculator;
import com.xpe.mobile.model.NoiseField;
import com.xpe.mobile.noiseeditor.NoiseFieldPresentation.State;
import com.xpe.mobile.preview.NoiseFieldEvaluator;
import com.xpe.mobile.preview.NoiseFieldFrame;
import java.util.List;
import org.json.JSONObject;

/** Selection outline and selected key's screen anchor, drawn above both preview backends. */
@SuppressLint("ViewConstructor")
final class NoiseFieldSelectionOverlay extends View {
    private final NoiseFieldEditorView owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF viewport = new RectF();
    private final DashPathEffect dashed;
    NoiseFieldSelectionOverlay(NoiseFieldEditorView owner) {
        super(owner.getContext());
        this.owner = owner;
        setClickable(false); setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        dashed = new DashPathEffect(new float[] {owner.dp(5), owner.dp(4)}, 0);
    }
    @Override protected void onDraw(Canvas canvas) {
        if (owner.document.valid() && owner.guides) draw(canvas, getWidth(), getHeight());
    }
    static int stateLabel(State state) {
        switch (state) {
            case ACTIVE: return R.string.noise_state_active;
            case READY: return R.string.noise_state_ready;
            case INACTIVE: return R.string.noise_state_inactive;
            default: return R.string.noise_state_outside;
        }
    }
    void draw(Canvas canvas, int width, int height) {
        NoiseField field = owner.displayedField();
        if (field == null || width <= 0 || height <= 0) return;
        var settings = owner.editor.getSettings();
        var fitted = PreviewViewportCalculator.fit(0, 0, width, height, settings.playerWidth, settings.playerHeight);
        viewport.set((float) fitted.left, (float) fitted.top, (float) fitted.right, (float) fitted.bottom);
        if (viewport.isEmpty()) return;
        double aspect = settings.playerWidth > 0 && settings.playerHeight > 0
                ? settings.playerWidth / (double) settings.playerHeight : viewport.width() / (double) viewport.height();
        NoiseFieldFrame.Zone zone = NoiseFieldEvaluator.geometry(field, (float) owner.time(), (float) aspect);
        State state = NoiseFieldPresentation.state(field, owner.time());
        int save = canvas.save(); canvas.clipRect(viewport);
        if (zone != null) {
            List<double[]> edges = NoiseFieldPresentation.outline(zone, aspect);
            paint.setStyle(Paint.Style.STROKE);
            paint.setPathEffect(state == State.OUTSIDE ? dashed : null);
            for (int pass = 0; pass < 2; pass++) {
                paint.setStrokeWidth(owner.dp(pass == 0 ? 3.5f : 1.5f));
                paint.setColor(pass == 0 ? 0xdd08151c : field.color != NoiseField.DEFAULT_COLOR ? field.color : field.subtract ? 0xffffc076 : 0xff65edda);
                for (double[] edge : edges)
                    canvas.drawLine(px(edge[0]), py(edge[1]), px(edge[2]), py(edge[3]), paint);
            }
            paint.setPathEffect(null);
            marker(canvas, zone.x, zone.y, 0xff65edda, false);
        }
        if (owner.document.channel != null && owner.document.channel.anchored() && owner.document.selectedKey >= 0) {
            var keys = field.toJson().optJSONArray(owner.document.channel.json);
            JSONObject key = keys == null ? null : keys.optJSONObject(owner.document.selectedKey);
            JSONObject anchor = key == null ? null : key.optJSONObject("anchor");
            if (anchor != null) marker(canvas, (anchor.optDouble("x") - .5) * 2,
                    (anchor.optDouble("y") - .5) * 2 / aspect, 0xffffd76d, true);
        }
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(owner.dp(11));
        String label = owner.label(owner.document.selected) + " · " + owner.tr(stateLabel(state));
        float inset = owner.dp(6), available = Math.max(0, viewport.width() - inset * 4);
        int count = paint.breakText(label, true, available, null);
        if (count < label.length()) {
            count = paint.breakText(label, true, Math.max(0, available - paint.measureText("…")), null);
            label = label.substring(0, count) + "…";
        }
        float x = viewport.left + inset, y = viewport.top + inset;
        paint.setColor(0xdd0d1822);
        canvas.drawRoundRect(x, y, x + paint.measureText(label) + inset * 2, y + owner.dp(22), owner.dp(3), owner.dp(3), paint);
        paint.setColor(0xffeef7fc); canvas.drawText(label, x + inset, y + owner.dp(15), paint);
        canvas.restoreToCount(save);
    }
    private float px(double x) { return (float) (viewport.centerX() + x * viewport.width() * .5); }
    private float py(double y) { return (float) (viewport.centerY() - y * viewport.width() * .5); }
    private void marker(Canvas canvas, double x, double y, int color, boolean anchor) {
        double maxY = viewport.height() / (double) viewport.width();
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1 || Math.abs(y) > maxY) return;
        float cx = px(x), cy = py(y), radius = owner.dp(anchor ? 6 : 4);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(owner.dp(2)); paint.setColor(0xff08151c);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStrokeWidth(owner.dp(1)); paint.setColor(color);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint);
        if (anchor) canvas.drawCircle(cx, cy, radius, paint);
    }
}
