package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Collections;
import java.util.List;

/** Compact live preview of the notes interpolated between the selected endpoints. */
public final class CurveNotePreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private List<CurveNoteGenerator.Point> path = Collections.emptyList();

    public CurveNotePreviewView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
    }

    public void setPath(List<CurveNoteGenerator.Point> path) {
        this.path = path == null ? Collections.emptyList() : path;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(15, 20, 25));
        float inset = 12f * density;
        float left = inset;
        float right = getWidth() - inset;
        float top = inset;
        float bottom = getHeight() - inset;
        if (right <= left || bottom <= top) return;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(Color.rgb(47, 68, 61));
        for (int index = 0; index <= 4; index++) {
            float x = left + (right - left) * index / 4f;
            float y = top + (bottom - top) * index / 4f;
            canvas.drawLine(x, top, x, bottom, paint);
            canvas.drawLine(left, y, right, y, paint);
        }

        paint.setColor(Color.rgb(118, 205, 173));
        paint.setStrokeWidth(2f * density);
        for (int index = 1; index < path.size(); index++) {
            CurveNoteGenerator.Point previous = path.get(index - 1);
            CurveNoteGenerator.Point current = path.get(index);
            canvas.drawLine(mapX(previous.x, left, right), mapY(previous.progress, top, bottom),
                    mapX(current.x, left, right), mapY(current.progress, top, bottom), paint);
        }

        paint.setStyle(Paint.Style.FILL);
        for (int index = 0; index < path.size(); index++) {
            paint.setColor(index == 0 ? Color.rgb(88, 220, 127)
                    : index == path.size() - 1 ? Color.rgb(235, 92, 82)
                    : Color.rgb(244, 223, 109));
            CurveNoteGenerator.Point point = path.get(index);
            canvas.drawCircle(mapX(point.x, left, right), mapY(point.progress, top, bottom),
                    (index == 0 || index == path.size() - 1 ? 5f : 3f) * density, paint);
        }
    }

    private static float mapX(double x, float left, float right) {
        return left + (float) ((x + 675.0) / 1350.0) * (right - left);
    }

    private static float mapY(double progress, float top, float bottom) {
        return bottom - (float) progress * (bottom - top);
    }
}
