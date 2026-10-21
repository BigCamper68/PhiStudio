package com.xpe.mobile.noiseeditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import com.xpe.mobile.R;
import com.xpe.mobile.editor.ScenePreviewPan;
import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.model.NoiseField;

/** Shader preview layout and the exact same pan/hold handler; no separate geometry gestures. */
@SuppressLint("ViewConstructor")
final class NoiseFieldPreviewPane extends View {
    private final NoiseFieldEditorView owner;
    private final RectF area = new RectF();
    private final ScenePreviewPan pan;
    NoiseFieldPreviewPane(Context context, NoiseFieldEditorView owner) {
        super(context); this.owner = owner;
        pan = new ScenePreviewPan(owner.editor, owner::axis, owner::seconds, owner::invalidatePreview);
        UiText.description(this, R.string.noise_preview);
    }
    void cancelPan() { pan.cancel(); }
    boolean panTouch(MotionEvent event) { return pan.touch(event); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = panTouch(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return handled;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!owner.document.valid()) return;
        NoiseField field = owner.document.field();
        double start = owner.loop && field != null ? field.appear : -1;
        double end = owner.loop && field != null ? field.disappear : -1;
        area.set(0, 0, getWidth(), getHeight());
        owner.editor.drawNoiseFieldEditorPreview(canvas, area, owner.gpu, start, end, owner.previewFields());
        owner.timeline.invalidate(); owner.playbackSeek.invalidate(); owner.updateStatus(); UiText.relocalize(owner.play);
        if (owner.editor.isPlaying()) {
            owner.selectionOverlay.invalidate();
            postInvalidateOnAnimation();
        }
    }
}
