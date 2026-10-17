package com.xpe.mobile.preview;

/** Immutable persistent history: scenes can share old marks without copying or resampling. */
final class PaintHistory {
    final PaintHistory previous;
    final RenderScene.PaintStroke stroke;
    private PaintHistory(PaintHistory previous, RenderScene.PaintStroke stroke) {
        this.previous = previous;
        this.stroke = stroke;
    }
    static PaintHistory empty() { return new PaintHistory(null, null); }
    PaintHistory append(RenderScene.PaintStroke stroke) { return new PaintHistory(this, stroke); }
}
