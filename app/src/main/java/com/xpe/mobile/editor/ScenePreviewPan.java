package com.xpe.mobile.editor;

import android.view.MotionEvent;
import java.util.function.DoubleUnaryOperator;

/** Original ShaderPreviewPane gesture handler shared by both scene editors. */
public final class ScenePreviewPan {
    private final EditorView editor;
    private final DoubleUnaryOperator axis, seconds;
    private final Runnable invalidate;
    private float panDownY;
    private double panAxis;
    private boolean panning, blocked;
    public ScenePreviewPan(EditorView editor, DoubleUnaryOperator axis, DoubleUnaryOperator seconds, Runnable invalidate) {
        this.editor = editor; this.axis = axis; this.seconds = seconds; this.invalidate = invalidate;
    }
    public void cancel() {
        if (panning) editor.shaderEndPan();
        panning = false;
    }
    public boolean touch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            blocked = false;
            panDownY = event.getY();
            panAxis = axis.applyAsDouble(editor.shaderEditorSeconds());
            editor.shaderBeginPan();
            panning = true;
            invalidate.run();
            return true;
        }
        if (event.getPointerCount() > 1) { cancel(); blocked = true; return true; }
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (blocked) return true;
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            float dy = event.getY() - panDownY;
            if (panning) {
                editor.shaderPanTo(seconds.applyAsDouble(Math.max(0, panAxis + dy / editor.timelineScale()
                        * editor.timelineScrollMultiplier())));
                invalidate.run();
            }
            if (action == MotionEvent.ACTION_UP) cancel();
        }
        return true;
    }
}
