package com.xpe.mobile.editor;

/** Per-note RPE size changes only X; texture/head/tail height uses the editor's base size. */
final class EditorNoteMetrics {
    private EditorNoteMetrics() { }

    static float width(float naturalWidth, double size) {
        return naturalWidth * (float) Math.abs(Double.isFinite(size) ? size : 1.0);
    }

    static float height(float naturalWidth, int textureWidth, int textureHeight) {
        return naturalWidth * textureHeight / textureWidth;
    }
}
