package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.preview.PreviewRenderer.Point;
import com.xpe.mobile.preview.PreviewRenderer.Projection;

/** PreviewLineRenderer responsibilities extracted from PreviewRenderer. */
final class PreviewLineRenderer {
    private final PreviewRenderer delegateOwner;

    PreviewLineRenderer(PreviewRenderer delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawLineVisual(Canvas canvas, Projection projection, RenderScene.RenderLine line,
                                EditorSettings settings, int activeLineIndex, long chartTimeMs, PaintHistory history) {
        // Hidden ordinary lines still provide transforms, notes and hit effects, but need no
        // texture/path lookup or drawing preparation. Preserve retained paint independently.
        if (line.alpha <= 0 && history == null && line.paintStrokes.isEmpty()) return;
        Point center = projection.point(line.x, line.y);
        PreviewTextureDecoder.Texture customTexture = delegateOwner.previewLineRenderer.customLineTexture(line.textureName);
        boolean active = settings.highlightCurrentLine && line.sourceIndex == activeLineIndex;
        int rgb = PhiraJudgeLineStyle.color(line.colorRgb, line.alpha,
                false, active, settings.lineColorRgb) & 0xFFFFFF;
        boolean visibleLineVisual = line.alpha > 0
                && PhiraRenderMetrics.hasVisibleLineScale(
                PreviewRenderer.finite(line.scaleX, 1.0), PreviewRenderer.finite(line.scaleY, 1.0));
        if (history == null) delegateOwner.previewPaintRenderer.drawPaintStrokes(canvas, projection, line.paintStrokes);
        else delegateOwner.previewPaintRenderer.drawPaintHistory(canvas, projection, line.sourceIndex, history);
        if (visibleLineVisual && !line.paintMode) {
            if (line.text != null) {
                delegateOwner.previewLineRenderer.drawLineText(canvas, projection, line, center, rgb);
            } else if (customTexture != null) {
                delegateOwner.previewLineRenderer.drawCustomLineTexture(canvas, projection, line, center,
                        customTexture, rgb, chartTimeMs);
            } else if (line.textureName == null || line.textureName.trim().isEmpty()
                    || "line.png".equalsIgnoreCase(line.textureName.trim())) {
                float halfLength = PhiraJudgeLineStyle.normalHalfLength(
                        projection.viewport.width(), PreviewRenderer.finite(line.scaleX, 1.0));
                float thickness = PhiraJudgeLineStyle.normalThickness(
                        projection.viewport.width(), PreviewRenderer.finite(line.scaleY, 1.0));
                delegateOwner.paint.setStyle(Paint.Style.FILL);
                delegateOwner.paint.setColor(PhiraJudgeLineStyle.color(line.colorRgb, line.rawAlpha,
                        true, active, settings.lineColorRgb));
                int save = canvas.save();
                canvas.translate(center.x, center.y);
                canvas.rotate(PreviewRenderer.lineScreenAngle(line.rotationDegrees));
                // prpr draw_line is a flat-ended quad, with local Scale X/Y.
                // Filling the quad also avoids Canvas's zero-width hairline rule.
                canvas.drawRect(-halfLength, -thickness / 2f,
                        halfLength, thickness / 2f, delegateOwner.paint);
                canvas.restoreToCount(save);
            }
        }

    }

    void drawLineId(Canvas canvas, Projection projection,
                            RenderScene.RenderLine line) {
        Point center = projection.point(line.x, line.y);
        delegateOwner.paint.setColor(Color.WHITE);
        delegateOwner.paint.setTextSize(10f * delegateOwner.density);
        delegateOwner.paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Integer.toString(line.sourceIndex), center.x,
                center.y - 7f * delegateOwner.density, delegateOwner.paint);
    }

    void drawLineText(Canvas canvas, Projection projection,
                              RenderScene.RenderLine line, Point center, int rgb) {
        if (line.text.isEmpty() || line.alpha <= 0) return;
        int save = canvas.save();
        canvas.translate(center.x, center.y);
        canvas.rotate(PreviewRenderer.lineScreenAngle(line.rotationDegrees));
        float scaleX = (float) PreviewRenderer.finite(line.scaleX, 1.0);
        float scaleY = (float) PreviewRenderer.finite(line.scaleY, 1.0);
        canvas.scale(scaleX, scaleY);
        PhiraFontMetrics.Layout layout = delegateOwner.textPainter.storyboard(line.text,
                PhiraRenderMetrics.textSize(projection.viewport.width(), 1f));
        delegateOwner.textPainter.draw(canvas, layout, false, 0f, 0f, .5f, .5f, rgb, line.alpha);
        canvas.restoreToCount(save);
    }

    void drawCustomLineTexture(Canvas canvas, Projection projection,
                                       RenderScene.RenderLine line, Point center,
                                       PreviewTextureDecoder.Texture texture, int rgb,
                                       long chartTimeMs) {
        if (line.alpha <= 0 || !texture.isUsable()) return;
        float width = (float) (texture.width * projection.objectScale
                * Math.abs(PreviewRenderer.finite(line.scaleX, 1.0)));
        float height = (float) (texture.height * projection.objectScale
                * Math.abs(PreviewRenderer.finite(line.scaleY, 1.0)));
        if (width < 0.5f || height < 0.5f) return;
        int save = canvas.save();
        canvas.translate(center.x, center.y);
        canvas.rotate(PreviewRenderer.lineScreenAngle(line.rotationDegrees));
        canvas.scale(line.scaleX < 0.0 ? -1f : 1f, line.scaleY < 0.0 ? -1f : 1f);
        delegateOwner.texturePaint.setAlpha(line.alpha);
        delegateOwner.texturePaint.setColorFilter(PreviewRenderer.tint(rgb));
        if (texture.isAnimated()) {
            int duration = texture.durationMs();
            int movieTime = line.gifMovieTimeMillis(duration, chartTimeMs);
            Bitmap frame = texture.bitmapAt(movieTime);
            if (frame != null) {
                canvas.drawBitmap(frame, null,
                        new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f),
                        delegateOwner.texturePaint);
            }
        } else {
            canvas.drawBitmap(texture.bitmap, null,
                    new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f),
                    delegateOwner.texturePaint);
        }
        delegateOwner.texturePaint.setColorFilter(null);
        canvas.restoreToCount(save);
    }

    PreviewTextureDecoder.Texture customLineTexture(String textureName) {
        String normalized = PreviewTexturePath.normalize(textureName);
        if (normalized == null || normalized.isEmpty()
                || "line.png".equalsIgnoreCase(normalized)) return null;
        PreviewTextureDecoder.Texture texture = delegateOwner.lineTextures.get(normalized);
        return texture == null || !texture.isUsable() ? null : texture;
    }
}
