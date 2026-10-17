package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import com.xpe.mobile.preview.PreviewRenderer.Point;
import com.xpe.mobile.preview.PreviewRenderer.Projection;
import java.util.Collections;

/** PreviewPaintRenderer responsibilities extracted from PreviewRenderer. */
final class PreviewPaintRenderer {
    private final java.util.Map<Integer, PaintSurface> paintSurfaces = new java.util.HashMap<>();
    private final PreviewRenderer delegateOwner;

    PreviewPaintRenderer(PreviewRenderer delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void retainScene(java.util.Map<Integer, PaintHistory> histories) {
        java.util.Iterator<java.util.Map.Entry<Integer, PaintSurface>> surfaces = paintSurfaces.entrySet().iterator();
        while (surfaces.hasNext()) {
            java.util.Map.Entry<Integer, PaintSurface> entry = surfaces.next();
            if (!histories.containsKey(entry.getKey())) {
                entry.getValue().bitmap.recycle(); surfaces.remove();
            }
        }
    }

    void clearPaintSurfaces() {
        for (PaintSurface surface : paintSurfaces.values()) surface.bitmap.recycle();
        paintSurfaces.clear();
    }

    void drawPaintHistory(Canvas canvas, Projection projection, int line, PaintHistory history) {
        float width = projection.viewport.width(), height = projection.viewport.height();
        PaintSurface surface = paintSurfaces.get(line);
        if (surface == null || surface.width != width || surface.height != height) {
            if (surface != null) surface.bitmap.recycle();
            surface = new PaintSurface(width, height);
            paintSurfaces.put(line, surface);
        }
        java.util.ArrayList<RenderScene.PaintStroke> pending = new java.util.ArrayList<>();
        PaintHistory cursor = history;
        while (cursor != null && cursor != surface.last) {
            if (cursor.stroke != null) pending.add(cursor.stroke);
            cursor = cursor.previous;
        }
        if (cursor == null) surface.bitmap.eraseColor(Color.TRANSPARENT);
        Collections.reverse(pending);
        delegateOwner.previewPaintRenderer.drawPaintStrokes(surface.canvas, new Projection(new RectF(0, 0, width, height)), pending);
        surface.last = history;
        delegateOwner.texturePaint.setAlpha(255);
        delegateOwner.texturePaint.setColorFilter(null);
        canvas.drawBitmap(surface.bitmap, projection.viewport.left, projection.viewport.top, delegateOwner.texturePaint);
    }

    void drawPaintStrokes(Canvas canvas, Projection projection,
                                  Iterable<RenderScene.PaintStroke> strokes) {
        for (RenderScene.PaintStroke stroke : strokes) {
            if (!PhiraRenderMetrics.hasVisibleLineScale(
                    stroke.scaleX, stroke.scaleY)) continue;
            Point point = projection.point(stroke.x, stroke.y);
            // prpr divides brush size by viewport width before projection: radius is pixels.
            float radiusX = (float) (stroke.radius * Math.abs(stroke.scaleX));
            float radiusY = (float) (stroke.radius * Math.abs(stroke.scaleY));
            if (!PreviewRenderer.isNearViewport(projection.viewport, point, Math.max(radiusX, radiusY))) continue;
            int rgb = stroke.colorRgb < 0 ? 0xFFFFFF : stroke.colorRgb;
            delegateOwner.paint.setStyle(Paint.Style.FILL);
            delegateOwner.paint.setColor(PreviewRenderer.argb(stroke.alpha, rgb));
            int save = canvas.save();
            canvas.translate(point.x, point.y);
            canvas.rotate(PreviewRenderer.lineScreenAngle(stroke.rotationDegrees));
            canvas.drawOval(-radiusX, -radiusY, radiusX, radiusY, delegateOwner.paint);
            canvas.restoreToCount(save);
        }
    }

    static final class PaintSurface {
        final Bitmap bitmap;
        final Canvas canvas;
        final float width, height;
        PaintHistory last;
        PaintSurface(float width, float height) {
            this.width = width; this.height = height;
            bitmap = Bitmap.createBitmap((int) Math.ceil(width), (int) Math.ceil(height), Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
        }
    }
}
