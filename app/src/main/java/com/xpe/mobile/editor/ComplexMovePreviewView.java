package com.xpe.mobile.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Collections;
import java.util.List;

/** Live green-to-red path preview matching the Complex Move manual. */
public final class ComplexMovePreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] pathColor = new float[]{120f, 0.82f, 1f};
    private List<ComplexMoveGenerator.PathPoint> path = Collections.emptyList();
    private final float density;

    public ComplexMovePreviewView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
    }

    public void setPath(List<ComplexMoveGenerator.PathPoint> path) {
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
        paint.setColor(Color.rgb(52, 69, 75));
        for (int index = 0; index <= 4; index++) {
            float x = left + (right - left) * index / 4f;
            float y = top + (bottom - top) * index / 4f;
            canvas.drawLine(x, top, x, bottom, paint);
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setColor(Color.rgb(118, 130, 138));
        canvas.drawLine(mapX(0.0, left, right), top, mapX(0.0, left, right), bottom, paint);
        canvas.drawLine(left, mapY(0.0, top, bottom), right, mapY(0.0, top, bottom), paint);

        paint.setStrokeWidth(3f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int index = 1; index < path.size(); index++) {
            float progress = (index - 1f) / Math.max(1f, path.size() - 2f);
            pathColor[0] = 120f * (1f - progress);
            paint.setColor(Color.HSVToColor(pathColor));
            ComplexMoveGenerator.PathPoint previous = path.get(index - 1);
            ComplexMoveGenerator.PathPoint current = path.get(index);
            canvas.drawLine(mapX(previous.x, left, right), mapY(previous.y, top, bottom),
                    mapX(current.x, left, right), mapY(current.y, top, bottom), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static float mapX(double x, float left, float right) {
        return left + (float) ((x + 675.0) / 1350.0) * (right - left);
    }

    private static float mapY(double y, float top, float bottom) {
        return bottom - (float) ((y + 450.0) / 900.0) * (bottom - top);
    }
}
