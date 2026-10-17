package com.xpe.mobile.editor;

/** Shared geometry for the editor's current-beat baseline. */
public final class EditorTimelineMetrics {
    public static final float BASELINE_BOTTOM_INSET_DP = 28f;

    private EditorTimelineMetrics() {
    }

    public static float baselineY(float editorBottom, float density) {
        if (!Float.isFinite(editorBottom) || !Float.isFinite(density) || density <= 0f) {
            return editorBottom;
        }
        return editorBottom - BASELINE_BOTTOM_INSET_DP * density;
    }
}
