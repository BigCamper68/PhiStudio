package com.xpe.mobile.shadereditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.ScenePreviewPan;
import com.xpe.mobile.i18n.UiText;

/** ShaderPreviewPane view extracted from ShaderEditorView. */
@SuppressLint("ViewConstructor") // Created programmatically with its owning ShaderEditorView.
final class ShaderPreviewPane extends View {
    private final ShaderEditorView delegateOwner;
    private final RectF area = new RectF();
    private final ScenePreviewPan pan;
    void cancelPan() { pan.cancel(); }
    boolean panTouch(MotionEvent event) { return pan.touch(event); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = panTouch(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return handled;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    ShaderPreviewPane(Context c, ShaderEditorView delegateOwner) {
        super(c);
        this.delegateOwner = delegateOwner;
        pan = new ScenePreviewPan(delegateOwner.editor,
                value -> delegateOwner.document.axis(value, delegateOwner.beats),
                value -> delegateOwner.document.seconds(value, delegateOwner.beats), delegateOwner::invalidatePreview);
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
                delegateOwner.status.setText(delegateOwner.statusLabel());
            } else {
                delegateOwner.status.setTextColor(Color.WHITE);
                delegateOwner.status.setText(UiText.resources(getContext()).getString(R.string.shader_clock,
                    String.format(java.util.Locale.US, "%.3f", delegateOwner.document.axis(delegateOwner.editor.shaderEditorSeconds(), delegateOwner.beats)),
                    UiText.resources(getContext()).getString(
                        delegateOwner.beats ? R.string.shader_beats_unit : R.string.shader_seconds_unit),
                    delegateOwner.document.dirty() ? " *" : ""));
            }
        }
    }
}
