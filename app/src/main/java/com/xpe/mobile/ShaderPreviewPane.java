package com.xpe.mobile;

import android.annotation.SuppressLint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** ShaderPreviewPane view extracted from ShaderEditorView. */
@SuppressLint("ViewConstructor") // Created programmatically with its owning ShaderEditorView.
final class ShaderPreviewPane extends View {
    private final ShaderEditorView delegateOwner;
    private final RectF area = new RectF();
    private float panDownY;
    private double panAxis;
    private boolean panning, blocked;
    void cancelPan() {
        if (panning) delegateOwner.editor.shaderEndPan();
        panning = false;
    }
    boolean panTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            blocked = false;
            panDownY = event.getY();
            panAxis = delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats);
            delegateOwner.editor.shaderBeginPan();
            panning = true;
            delegateOwner.invalidatePreview();
            return true;
        }
        if (event.getPointerCount() > 1) { cancelPan(); blocked = true; return true; }
        if (action == MotionEvent.ACTION_CANCEL) { cancelPan(); return true; }
        if (blocked) return true;
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            float dy = event.getY() - panDownY;
            if (panning) {
                delegateOwner.editor.shaderPanTo(delegateOwner.document.seconds(Math.max(0, panAxis + dy / delegateOwner.editor.timelineScale()
                        * delegateOwner.editor.timelineScrollMultiplier()), delegateOwner.beats));
                delegateOwner.invalidatePreview();
            }
            if (action == MotionEvent.ACTION_UP) cancelPan();
        }
        return true;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = panTouch(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return handled;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    ShaderPreviewPane(Context c, ShaderEditorView delegateOwner) {
        super(c);
        this.delegateOwner = delegateOwner;
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        double start = -1, end = -1;
        if (delegateOwner.loop.isChecked() && delegateOwner.document.selected() != null) {
            start = delegateOwner.document.start(delegateOwner.document.selected);
            end = delegateOwner.document.end(delegateOwner.document.selected);
        }
        area.set(0, 0, getWidth(), getHeight());
        delegateOwner.editor.drawShaderEditorPreview(canvas, area, delegateOwner.gpu, start, end);
        delegateOwner.timeline.invalidate();
        delegateOwner.playbackSeek.invalidate();
        if (delegateOwner.editor.isPlaying())
            postInvalidateOnAnimation();
        {
            delegateOwner.play.setText(delegateOwner.editor.isPlaying() ? R.string.toolbar_pause : R.string.toolbar_play);
            if (delegateOwner.diagnostic != null) {
                delegateOwner.status.setTextColor(0xffffab91);
                delegateOwner.status.setText(delegateOwner.diagnostic);
            } else {
                delegateOwner.status.setTextColor(Color.WHITE);
                delegateOwner.status.setText(getResources().getString(R.string.shader_clock,
                    String.format(java.util.Locale.US, "%.3f", delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats)),
                    getResources().getString(
                        delegateOwner.beats ? R.string.shader_beats_unit : R.string.shader_seconds_unit),
                    delegateOwner.document.dirty() ? " *" : ""));
            }
        }
    }
}
