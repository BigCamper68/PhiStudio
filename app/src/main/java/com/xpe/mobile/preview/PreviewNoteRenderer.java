package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import com.xpe.mobile.model.NoteType;
import com.xpe.mobile.preview.PreviewRenderer.Point;
import com.xpe.mobile.preview.PreviewRenderer.Projection;

/** PreviewNoteRenderer responsibilities extracted from PreviewRenderer. */
final class PreviewNoteRenderer {
    private final PreviewRenderer delegateOwner;

    PreviewNoteRenderer(PreviewRenderer delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawNote(Canvas canvas, Projection projection, RenderScene.RenderLine line,
                          RenderScene.RenderNote note, boolean showFakeNoteOutline) {
        int alpha = note.alpha;
        if (alpha <= 0) return;
        double headDistance = note.isHold()
                ? note.yOffset + (note.startDistance - note.yOffset) * note.sizeY : note.startDistance;
        Point start = PreviewNoteRenderer.notePoint(projection, line, note.x, headDistance,
                note.above, note.isHold());
        float naturalWidth = (float) (projection.viewport.width() * PreviewRenderer.NOTE_WIDTH_RATIO)
                * delegateOwner.textures.widthScale(note.multiHit) * delegateOwner.noteScale;
        float width = Math.abs(naturalWidth * (float) note.size);
        if (width < 0.5f) return;
        float angle = PreviewRenderer.lineScreenAngle(line.rotationDegrees);

        if (note.isHold()) {
            // Floor distance is signed. A negative-speed section may put the tail
            // behind the active head: Phira omits that body, rather than flipping it.
            float length = (float) ((note.endDistance - note.startDistance)
                    * projection.scaleY);
            int save = canvas.save();
            canvas.translate(start.x, start.y);
            canvas.rotate(angle);
            canvas.scale(note.size < 0 ? -1f : 1f, (float) note.sizeY * (note.above ? 1f : -1f));
            delegateOwner.previewNoteRenderer.drawHoldTexture(canvas, 0f, 0f, -length, width, naturalWidth,
                    note.multiHit, note.holdHeadVisible, alpha, note.colorRgb);
            if (note.fake && showFakeNoteOutline && length > 0f) {
                delegateOwner.previewNoteRenderer.drawFakeMarker(canvas, 0f, -length / 2f, width, length, alpha);
            }
            canvas.restoreToCount(save);
            return;
        }

        Bitmap bitmap = delegateOwner.textures.bitmap(note.type, note.multiHit);
        if (bitmap == null || bitmap.isRecycled()) return;
        float height = naturalWidth * bitmap.getHeight() / bitmap.getWidth();
        if (!PreviewRenderer.isNearViewport(projection.viewport, start, Math.max(width, height * (float) Math.abs(note.sizeY)))) return;
        int save = canvas.save();
        canvas.translate(start.x, start.y);
        canvas.rotate(angle);
        canvas.scale(note.size < 0 ? -1f : 1f, (float) note.sizeY * (note.above ? 1f : -1f));
        delegateOwner.texturePaint.setAlpha(alpha);
        delegateOwner.texturePaint.setColorFilter(PreviewRenderer.tint(note.colorRgb));
        RectF destination = new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f);
        canvas.drawBitmap(bitmap, null, destination, delegateOwner.texturePaint);
        delegateOwner.texturePaint.setColorFilter(null);
        if (note.fake && showFakeNoteOutline) delegateOwner.previewNoteRenderer.drawFakeMarker(canvas, 0f, 0f, width, height, alpha);
        canvas.restoreToCount(save);
    }

    void drawHoldTexture(Canvas canvas, float x, float startY, float endY,
                                 float width, float naturalWidth, boolean multiHit,
                                 boolean drawHead, int alpha, int colorRgb) {
        Bitmap bitmap = delegateOwner.textures.bitmap(NoteType.HOLD, multiHit);
        if (bitmap == null || bitmap.isRecycled()) return;
        int tailPixels = delegateOwner.textures.holdTailPixels(multiHit);
        int headPixels = delegateOwner.textures.holdHeadPixels(multiHit);
        int bodyBottom = Math.max(tailPixels + 1, bitmap.getHeight() - headPixels);
        float left = x - width / 2f;
        float right = x + width / 2f;
        float top = endY;
        float bottom = startY;
        float tailHeight = naturalWidth * tailPixels / bitmap.getWidth();
        float headHeight = naturalWidth * headPixels / bitmap.getWidth();

        delegateOwner.texturePaint.setAlpha(alpha);
        delegateOwner.texturePaint.setColorFilter(PreviewRenderer.tint(colorRgb));
        if (bottom > top) {
            canvas.drawBitmap(bitmap,
                    new Rect(0, tailPixels, bitmap.getWidth(), bodyBottom),
                    new RectF(left, top, right, bottom), delegateOwner.texturePaint);
        }
        canvas.drawBitmap(bitmap,
                new Rect(0, 0, bitmap.getWidth(), tailPixels),
                new RectF(left, top - tailHeight, right, top), delegateOwner.texturePaint);
        if (drawHead) {
            canvas.drawBitmap(bitmap,
                    new Rect(0, bitmap.getHeight() - headPixels,
                            bitmap.getWidth(), bitmap.getHeight()),
                    new RectF(left, bottom, right, bottom + headHeight), delegateOwner.texturePaint);
        }
        delegateOwner.texturePaint.setColorFilter(null);
    }

    void drawFakeMarker(Canvas canvas, float centerX, float centerY,
                                float width, float height, int alpha) {
        delegateOwner.strokePaint.setColor(Color.argb(alpha, 255, 255, 255));
        delegateOwner.strokePaint.setStrokeWidth(1.4f * delegateOwner.density);
        canvas.drawRoundRect(new RectF(centerX - width / 2f, centerY - height / 2f,
                        centerX + width / 2f, centerY + height / 2f),
                4f * delegateOwner.density, 4f * delegateOwner.density, delegateOwner.strokePaint);
    }

    static Point notePoint(Projection projection, RenderScene.RenderLine line,
                                   double x, double distance, boolean above, boolean hold) {
        double side = above ? 1.0 : -1.0;
        double inclineScale = hold ? 1.0 : 1.0
                - Math.sin(Math.toRadians(line.inclineDegrees)) * distance / 360.0;
        double transformedX = x * inclineScale;
        return projection.notePoint(line.x, line.y, transformedX,
                distance * side, line.rotationDegrees);
    }
}
