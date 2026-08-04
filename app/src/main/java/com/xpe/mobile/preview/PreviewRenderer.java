package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;

import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.CenterCropCalculator;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.NoteType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Native Canvas renderer for a deterministic {@link RenderScene}. */
public final class PreviewRenderer {
    private static final double RPE_WIDTH = 1350.0;
    private static final double NOTE_WIDTH_RATIO = 989.0 / 8000.0;
    private static final double NORMAL_LINE_HALF_LENGTH = RPE_WIDTH * 3.0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path paintPath = new Path();
    private final NoteTextureSet textures;
    private final float density;
    private final String autoplayLabel;
    private Map<String, PreviewTextureDecoder.Texture> lineTextures = Collections.emptyMap();

    public PreviewRenderer(NoteTextureSet textures, float density, String autoplayLabel) {
        if (textures == null) throw new IllegalArgumentException("textures are required");
        this.textures = textures;
        this.density = Math.max(0.5f, density);
        this.autoplayLabel = autoplayLabel == null ? "" : autoplayLabel;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    public void setLineTextures(Map<String, PreviewTextureDecoder.Texture> textures) {
        lineTextures = textures == null ? Collections.emptyMap() : textures;
    }

    /** Draws Phira's full-screen illustration layer behind a fitted gameplay viewport. */
    public void drawBackdrop(Canvas canvas, RectF bounds, Bitmap illustration) {
        if (canvas == null || bounds == null || bounds.isEmpty()) return;
        // Phira draws the illustration first and then applies a 30% black veil.
        drawBackground(canvas, bounds, illustration, Math.round(255f * 0.7f));
    }

    public void draw(Canvas canvas, RenderScene scene, RectF viewport,
                     Bitmap illustration, EditorSettings settings, int activeLineIndex) {
        if (canvas == null || scene == null || viewport == null || viewport.isEmpty()) return;
        paint.setFakeBoldText(false);
        EditorSettings renderSettings = settings == null ? new EditorSettings() : settings;
        drawBackground(canvas, viewport, illustration,
                effectiveBackgroundAlpha(renderSettings));
        drawScene(canvas, scene, viewport, renderSettings, activeLineIndex, true);
    }

    /** Draws gameplay only, for the 50% background viewer under the editor grid. */
    public void drawGameplayOverlay(Canvas canvas, RenderScene scene, RectF viewport,
                                    EditorSettings settings, int activeLineIndex) {
        if (canvas == null || scene == null || viewport == null || viewport.isEmpty()) return;
        paint.setFakeBoldText(false);
        drawScene(canvas, scene, viewport,
                settings == null ? new EditorSettings() : settings,
                activeLineIndex, false);
    }

    private void drawScene(Canvas canvas, RenderScene scene, RectF viewport,
                           EditorSettings settings, int activeLineIndex,
                           boolean includeHud) {
        int save = canvas.save();
        canvas.clipRect(viewport);
        Projection projection = new Projection(viewport);
        for (RenderScene.RenderLine line : scene.lines) {
            drawLine(canvas, projection, line, settings, activeLineIndex,
                    scene.chartTimeMs);
        }
        if (settings.markLineId) {
            for (RenderScene.RenderLine line : scene.lines) {
                drawLineId(canvas, projection, line);
            }
        }
        if (includeHud) drawHud(canvas, projection, scene.hud);
        canvas.restoreToCount(save);
    }

    private static int effectiveBackgroundAlpha(EditorSettings settings) {
        double previewAlpha = Math.max(0.0, Math.min(1.0,
                settings.previewBackgroundAlpha));
        int brightness = Math.max(0, Math.min(255, settings.backgroundBrightness));
        return (int) Math.round(brightness * previewAlpha);
    }

    private void drawHud(Canvas canvas, Projection projection, RenderScene.HudState hud) {
        if (hud == null) return;
        RectF viewport = projection.viewport;
        PhiraRenderMetrics.Hud metrics = PhiraRenderMetrics.hud(
                viewport.width(), viewport.height());
        float left = viewport.left + metrics.marginX;
        float right = viewport.right - metrics.marginX;

        float pauseX = viewport.left + metrics.pauseCenterX;
        float pauseY = viewport.top + metrics.pauseTop;
        withHudTransform(canvas, projection, hud, AttachedUiElement.PAUSE,
                pauseX, pauseY + metrics.pauseBarHeight / 2f,
                (target, color, alpha) -> {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(argb(alpha, color));
                    float firstLeft = viewport.left + metrics.pauseFirstLeft;
                    float secondLeft = viewport.left + metrics.pauseSecondLeft;
                    target.drawRect(firstLeft, pauseY,
                            firstLeft + metrics.pauseBarWidth,
                            pauseY + metrics.pauseBarHeight, paint);
                    target.drawRect(secondLeft, pauseY,
                            secondLeft + metrics.pauseBarWidth,
                            pauseY + metrics.pauseBarHeight, paint);
                });

        float scoreX = right;
        float scoreTop = viewport.top + metrics.scoreTop;
        withHudTransform(canvas, projection, hud, AttachedUiElement.SCORE,
                scoreX, scoreTop, (target, color, alpha) -> {
                    prepareHudText(metrics.scoreTextSize,
                            Paint.Align.RIGHT, color, alpha);
                    float scoreBaseline = scoreTop - paint.ascent();
                    target.drawText(String.format(Locale.US, "%07d", hud.score),
                            scoreX, scoreBaseline, paint);
                });

        if (hud.combo >= 3) {
            float comboNumberX = viewport.centerX();
            float comboTop = viewport.top + metrics.comboTop;
            withHudTransform(canvas, projection, hud, AttachedUiElement.COMBO_NUMBER,
                    comboNumberX, comboTop, (target, color, alpha) -> {
                        prepareHudText(metrics.comboTextSize,
                                Paint.Align.CENTER, color, alpha);
                        float comboBaseline = comboTop - paint.ascent();
                        target.drawText(Integer.toString(hud.combo),
                                comboNumberX, comboBaseline, paint);
                    });
            prepareHudText(metrics.comboTextSize, Paint.Align.CENTER, 0xFFFFFF, 255);
            float comboLabelTop = comboTop - paint.ascent() + metrics.comboLabelGap;
            withHudTransform(canvas, projection, hud, AttachedUiElement.COMBO,
                    comboNumberX, comboLabelTop, (target, color, alpha) -> {
                        prepareHudText(metrics.comboLabelTextSize,
                                Paint.Align.CENTER, color, alpha);
                        float labelBaseline = comboLabelTop - paint.ascent();
                        target.drawText(autoplayLabel,
                                comboNumberX, labelBaseline, paint);
                    });
        }

        float nameBottom = viewport.top + metrics.bottomTextBottom;
        withHudTransform(canvas, projection, hud, AttachedUiElement.NAME,
                left, nameBottom, (target, color, alpha) -> {
                    prepareHudText(metrics.bottomTextSize,
                            Paint.Align.LEFT, color, alpha);
                    float nameBaseline = nameBottom - paint.descent();
                    target.drawText(ellipsize(hud.name, viewport.width() * 0.4f),
                            left, nameBaseline, paint);
                });
        withHudTransform(canvas, projection, hud, AttachedUiElement.LEVEL,
                right, nameBottom, (target, color, alpha) -> {
                    prepareHudText(metrics.bottomTextSize,
                            Paint.Align.RIGHT, color, alpha);
                    float levelBaseline = nameBottom - paint.descent();
                    target.drawText(ellipsize(hud.level, viewport.width() * 0.4f),
                            right, levelBaseline, paint);
                });

        float barY = viewport.top;
        withHudTransform(canvas, projection, hud, AttachedUiElement.BAR,
                viewport.left, barY + metrics.progressHeight / 2f,
                (target, color, alpha) -> {
                    float completed = (float) Math.max(0.0, Math.min(1.0, hud.progress));
                    float markerX = viewport.left + viewport.width() * completed;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(argb(Math.round(alpha * 0.6f), 0xFFFFFF));
                    target.drawRect(viewport.left, barY,
                            markerX, barY + metrics.progressHeight, paint);
                    paint.setColor(argb(alpha, 0xFFFFFF));
                    target.drawRect(markerX - metrics.progressMarkerHalfWidth, barY,
                            markerX + metrics.progressMarkerHalfWidth,
                            barY + metrics.progressHeight, paint);
                });
    }

    private void withHudTransform(Canvas canvas, Projection projection,
                                  RenderScene.HudState hud, AttachedUiElement element,
                                  float pivotX, float pivotY, HudContent content) {
        RenderScene.HudTransform transform = hud.transform(element);
        int color = transform == null || transform.colorRgb < 0
                ? 0xFFFFFF : transform.colorRgb;
        int alpha = transform == null ? 255 : transform.alpha;
        if (alpha <= 0) return;
        if (transform != null && !PhiraRenderMetrics.hasVisibleLineScale(
                finite(transform.scaleX, 1.0), finite(transform.scaleY, 1.0))) {
            return;
        }
        int save = canvas.save();
        if (transform != null) {
            Point translated = projection.point(transform.x, transform.y);
            canvas.translate(translated.x - projection.viewport.centerX(),
                    translated.y - projection.viewport.centerY());
            canvas.rotate(lineScreenAngle(projection, transform.rotationDegrees),
                    pivotX, pivotY);
            canvas.scale((float) finite(transform.scaleX, 1.0),
                    (float) finite(transform.scaleY, 1.0), pivotX, pivotY);
        }
        content.draw(canvas, color, alpha);
        canvas.restoreToCount(save);
    }

    private void prepareHudText(float size, Paint.Align align, int rgb, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        paint.setFakeBoldText(false);
        paint.setColor(argb(alpha, rgb));
    }

    private String ellipsize(String value, float maximumWidth) {
        String text = value == null ? "" : value;
        if (paint.measureText(text) <= maximumWidth) return text;
        String suffix = "…";
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end)
                + paint.measureText(suffix) > maximumWidth) {
            end--;
        }
        return end == 0 ? suffix : text.substring(0, end) + suffix;
    }

    private interface HudContent {
        void draw(Canvas canvas, int colorRgb, int alpha);
    }

    private void drawBackground(Canvas canvas, RectF viewport, Bitmap illustration, int brightness) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRect(viewport, paint);
        if (illustration == null || illustration.isRecycled()) return;
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(
                illustration.getWidth(), illustration.getHeight(),
                viewport.width(), viewport.height());
        Rect source = new Rect(crop.left, crop.top, crop.right, crop.bottom);
        backgroundPaint.setAlpha(Math.max(0, Math.min(255, brightness)));
        canvas.drawBitmap(illustration, source, viewport, backgroundPaint);
    }

    private void drawLine(Canvas canvas, Projection projection, RenderScene.RenderLine line,
                          EditorSettings settings, int activeLineIndex, long chartTimeMs) {
        Point center = projection.point(line.x, line.y);
        double radians = Math.toRadians(line.rotationDegrees);

        PreviewTextureDecoder.Texture customTexture = customLineTexture(line.textureName);
        boolean active = line.sourceIndex == activeLineIndex;
        int rgb = active ? settings.lineColorRgb
                : line.colorRgb >= 0 ? line.colorRgb : 0xFFFFFF;
        boolean visibleLineVisual = line.alpha > 0
                && PhiraRenderMetrics.hasVisibleLineScale(
                finite(line.scaleX, 1.0), finite(line.scaleY, 1.0));
        drawPaintStrokes(canvas, projection, line, settings, active);
        if (visibleLineVisual && !line.paintMode) {
            if (line.text != null) {
                drawLineText(canvas, projection, line, center, rgb);
            } else if (customTexture != null) {
                drawCustomLineTexture(canvas, projection, line, center,
                        customTexture, rgb, chartTimeMs);
            } else {
                double scaledHalfLength = NORMAL_LINE_HALF_LENGTH
                        * finite(line.scaleX, 1.0);
                Point start = projection.point(
                        line.x - Math.cos(radians) * scaledHalfLength,
                        line.y - Math.sin(radians) * scaledHalfLength);
                Point end = projection.point(
                        line.x + Math.cos(radians) * scaledHalfLength,
                        line.y + Math.sin(radians) * scaledHalfLength);
                strokePaint.setColor(argb(line.alpha, rgb));
                double scaledThickness = Math.abs(finite(line.scaleY, 1.0));
                if (Math.abs(scaledThickness - 1.0) > 1.0e-4) {
                    scaledThickness *= 0.76;
                }
                strokePaint.setStrokeWidth((float) (settings.lineDefaultWidth * density
                        * scaledThickness));
                canvas.drawLine(start.x, start.y, end.x, end.y, strokePaint);
            }
        }

        for (RenderScene.RenderNote note : line.notes) {
            if (note.isHold()) drawNote(canvas, projection, line, note);
        }
        for (RenderScene.RenderNote note : line.notes) {
            if (note.type == NoteType.DRAG) drawNote(canvas, projection, line, note);
        }
        for (RenderScene.RenderNote note : line.notes) {
            if (note.type == NoteType.TAP) drawNote(canvas, projection, line, note);
        }
        for (RenderScene.RenderNote note : line.notes) {
            if (note.type == NoteType.FLICK) drawNote(canvas, projection, line, note);
        }
        for (RenderScene.HitEffect effect : line.hitEffects) {
            drawHitEffect(canvas, projection, effect);
        }
    }

    private void drawLineId(Canvas canvas, Projection projection,
                            RenderScene.RenderLine line) {
        Point center = projection.point(line.x, line.y);
        paint.setColor(Color.WHITE);
        paint.setTextSize(10f * density);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(Integer.toString(line.sourceIndex), center.x,
                center.y - 7f * density, paint);
    }

    private void drawNote(Canvas canvas, Projection projection, RenderScene.RenderLine line,
                          RenderScene.RenderNote note) {
        int alpha = note.alpha;
        if (alpha <= 0) return;
        Point start = notePoint(projection, line, note.x, note.startDistance,
                note.above, note.isHold());
        float naturalWidth = (float) (projection.viewport.width() * NOTE_WIDTH_RATIO)
                * textures.widthScale(note.multiHit);
        float width = Math.abs(naturalWidth * (float) note.size);
        if (width < 0.5f) return;
        float angle = lineScreenAngle(projection, line.rotationDegrees)
                + (note.above ? 0f : 180f);

        if (note.isHold()) {
            Point end = notePoint(projection, line, note.x, note.endDistance,
                    note.above, true);
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float length = (float) Math.hypot(dx, dy);
            if (length < 0.5f) return;
            int save = canvas.save();
            canvas.translate(start.x, start.y);
            canvas.rotate((float) Math.toDegrees(Math.atan2(dx, -dy)));
            drawHoldTexture(canvas, 0f, 0f, -length, width, naturalWidth,
                    note.multiHit, note.holdHeadVisible, alpha, note.colorRgb);
            if (note.fake) drawFakeMarker(canvas, 0f, -length / 2f, width, length, alpha);
            canvas.restoreToCount(save);
            return;
        }

        Bitmap bitmap = textures.bitmap(note.type, note.multiHit);
        if (bitmap == null || bitmap.isRecycled()) return;
        float height = naturalWidth * bitmap.getHeight() / bitmap.getWidth();
        if (!isNearViewport(projection.viewport, start, Math.max(width, height))) return;
        int save = canvas.save();
        canvas.translate(start.x, start.y);
        canvas.rotate(angle);
        texturePaint.setAlpha(alpha);
        texturePaint.setColorFilter(tint(note.colorRgb));
        RectF destination = new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f);
        canvas.drawBitmap(bitmap, null, destination, texturePaint);
        texturePaint.setColorFilter(null);
        if (note.fake) drawFakeMarker(canvas, 0f, 0f, width, height, alpha);
        canvas.restoreToCount(save);
    }

    private void drawHoldTexture(Canvas canvas, float x, float startY, float endY,
                                 float width, float naturalWidth, boolean multiHit,
                                 boolean drawHead, int alpha, int colorRgb) {
        Bitmap bitmap = textures.bitmap(NoteType.HOLD, multiHit);
        if (bitmap == null || bitmap.isRecycled()) return;
        int tailPixels = textures.holdTailPixels(multiHit);
        int headPixels = textures.holdHeadPixels(multiHit);
        int bodyBottom = Math.max(tailPixels + 1, bitmap.getHeight() - headPixels);
        float left = x - width / 2f;
        float right = x + width / 2f;
        float top = Math.min(startY, endY);
        float bottom = Math.max(startY, endY);
        float tailHeight = naturalWidth * tailPixels / bitmap.getWidth();
        float headHeight = naturalWidth * headPixels / bitmap.getWidth();

        texturePaint.setAlpha(alpha);
        texturePaint.setColorFilter(tint(colorRgb));
        canvas.drawBitmap(bitmap,
                new Rect(0, tailPixels, bitmap.getWidth(), bodyBottom),
                new RectF(left, top, right, bottom), texturePaint);
        canvas.drawBitmap(bitmap,
                new Rect(0, 0, bitmap.getWidth(), tailPixels),
                new RectF(left, top, right, top + tailHeight), texturePaint);
        if (drawHead) {
            canvas.drawBitmap(bitmap,
                    new Rect(0, bitmap.getHeight() - headPixels,
                            bitmap.getWidth(), bitmap.getHeight()),
                    new RectF(left, bottom - headHeight, right, bottom), texturePaint);
        }
        texturePaint.setColorFilter(null);
    }

    private void drawFakeMarker(Canvas canvas, float centerX, float centerY,
                                float width, float height, int alpha) {
        strokePaint.setColor(Color.argb(alpha, 255, 255, 255));
        strokePaint.setStrokeWidth(1.4f * density);
        canvas.drawRoundRect(new RectF(centerX - width / 2f, centerY - height / 2f,
                        centerX + width / 2f, centerY + height / 2f),
                4f * density, 4f * density, strokePaint);
    }

    private void drawHitEffect(Canvas canvas, Projection projection,
                               RenderScene.HitEffect effect) {
        Point point = projection.point(effect.worldX, effect.worldY);
        float progress = (float) Math.max(0.0, Math.min(1.0, effect.progress));
        int alpha = (int) ((1f - progress) * 255f);
        float radius = projection.viewport.width() * (256f * 6f / 8000f) / 2f;
        if (!isNearViewport(projection.viewport, point, radius * 1.7f)) return;

        Bitmap hitEffect = textures.hitEffectBitmap();
        if (hitEffect != null && !hitEffect.isRecycled()) {
            int columns = textures.hitEffectColumns();
            int rows = textures.hitEffectRows();
            int frameCount = columns * rows;
            int frame = Math.min(frameCount - 1, (int) (progress * frameCount));
            int frameWidth = hitEffect.getWidth() / columns;
            int frameHeight = hitEffect.getHeight() / rows;
            int sourceLeft = frame % columns * frameWidth;
            int sourceTop = frame / columns * frameHeight;
            Rect source = new Rect(sourceLeft, sourceTop,
                    sourceLeft + frameWidth, sourceTop + frameHeight);
            RectF destination = new RectF(point.x - radius, point.y - radius,
                    point.x + radius, point.y + radius);
            texturePaint.setAlpha(255);
            texturePaint.setColorFilter(tint(effect.colorRgb));
            canvas.drawBitmap(hitEffect, source, destination, texturePaint);
            texturePaint.setColorFilter(null);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb((int) (alpha * 0.9f), effect.colorRgb));
        float travel = projection.viewport.width() / 426f * 55f
                * (float) Math.sin(progress * Math.PI / 2.0);
        for (int index = 0; index < 4; index++) {
            double angle = particleAngle(effect.seed, index);
            float x = point.x + (float) Math.cos(angle) * travel;
            float y = point.y + (float) Math.sin(angle) * travel;
            float size = projection.viewport.width() / 426f
                    * (7f + deterministicFraction(effect.seed, index) * 3f)
                    * (1f - progress * 0.35f);
            int save = canvas.save();
            canvas.rotate((float) Math.toDegrees(angle) + 45f, x, y);
            canvas.drawRect(x - size / 2f, y - size / 2f,
                    x + size / 2f, y + size / 2f, paint);
            canvas.restoreToCount(save);
        }
    }

    private static double particleAngle(int seed, int index) {
        return deterministicFraction(seed ^ 0x6D2B79F5, index) * Math.PI * 2.0;
    }

    private static float deterministicFraction(int seed, int index) {
        int value = seed + index * 0x9E3779B9;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        value ^= value >>> 16;
        return (value & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    private static Point notePoint(Projection projection, RenderScene.RenderLine line,
                                   double x, double distance, boolean above, boolean hold) {
        double radians = Math.toRadians(line.rotationDegrees);
        double side = above ? 1.0 : -1.0;
        double inclineScale = hold ? 1.0 : 1.0
                - Math.sin(Math.toRadians(line.inclineDegrees)) * distance / 360.0;
        double transformedX = x * inclineScale;
        double worldX = line.x + Math.cos(radians) * transformedX
                - Math.sin(radians) * distance * side;
        double worldY = line.y + Math.sin(radians) * transformedX
                + Math.cos(radians) * distance * side;
        return projection.point(worldX, worldY);
    }

    private void drawPaintStrokes(Canvas canvas, Projection projection,
                                  RenderScene.RenderLine line, EditorSettings settings,
                                  boolean active) {
        for (RenderScene.PaintStroke stroke : line.paintStrokes) {
            if (!PhiraRenderMetrics.hasVisibleLineScale(
                    stroke.scaleX, stroke.scaleY)) continue;
            Point point = projection.point(stroke.x, stroke.y);
            float radius = (float) (stroke.radius * Math.max(
                    Math.abs(stroke.scaleX) * projection.scaleX,
                    Math.abs(stroke.scaleY) * projection.scaleY));
            if (!isNearViewport(projection.viewport, point, radius)) continue;
            int rgb = active ? settings.lineColorRgb
                    : stroke.colorRgb >= 0 ? stroke.colorRgb : 0xFFFFFF;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(argb(stroke.alpha, rgb));
            if (Math.abs(projection.scaleX - projection.scaleY) < 1.0e-6
                    && Math.abs(Math.abs(stroke.scaleX)
                    - Math.abs(stroke.scaleY)) < 1.0e-6) {
                canvas.drawCircle(point.x, point.y, Math.max(0.5f, radius), paint);
                continue;
            }
            paintPath.reset();
            double rotation = Math.toRadians(stroke.rotationDegrees);
            for (int index = 0; index <= 32; index++) {
                double angle = Math.PI * 2.0 * index / 32.0;
                double localX = Math.cos(angle) * stroke.radius * stroke.scaleX;
                double localY = Math.sin(angle) * stroke.radius * stroke.scaleY;
                double worldX = stroke.x + Math.cos(rotation) * localX
                        - Math.sin(rotation) * localY;
                double worldY = stroke.y + Math.sin(rotation) * localX
                        + Math.cos(rotation) * localY;
                Point sample = projection.point(worldX, worldY);
                if (index == 0) paintPath.moveTo(sample.x, sample.y);
                else paintPath.lineTo(sample.x, sample.y);
            }
            paintPath.close();
            canvas.drawPath(paintPath, paint);
        }
    }

    private void drawLineText(Canvas canvas, Projection projection,
                              RenderScene.RenderLine line, Point center, int rgb) {
        if (line.text.isEmpty() || line.alpha <= 0) return;
        int save = canvas.save();
        canvas.translate(center.x, center.y);
        canvas.rotate(lineScreenAngle(projection, line.rotationDegrees));
        float scaleX = (float) finite(line.scaleX, 1.0);
        float scaleY = (float) finite(line.scaleY, 1.0);
        canvas.scale(scaleX, scaleY);
        paint.setColor(argb(line.alpha, rgb));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(false);
        paint.setTextSize(PhiraRenderMetrics.textSize(
                projection.viewport.width(), 1.0f));
        String[] rows = line.text.split("\\n", -1);
        float lineHeight = paint.getFontSpacing();
        float firstBaseline = -(rows.length - 1) * lineHeight / 2f
                - (paint.ascent() + paint.descent()) / 2f;
        for (int index = 0; index < rows.length; index++) {
            canvas.drawText(rows[index], 0f, firstBaseline + index * lineHeight, paint);
        }
        canvas.restoreToCount(save);
    }

    private void drawCustomLineTexture(Canvas canvas, Projection projection,
                                       RenderScene.RenderLine line, Point center,
                                       PreviewTextureDecoder.Texture texture, int rgb,
                                       long chartTimeMs) {
        if (line.alpha <= 0 || !texture.isUsable()) return;
        float width = (float) (texture.width * projection.scaleX
                * Math.abs(finite(line.scaleX, 1.0)));
        float height = (float) (texture.height * projection.scaleY
                * Math.abs(finite(line.scaleY, 1.0)));
        if (width < 0.5f || height < 0.5f) return;
        int save = canvas.save();
        canvas.translate(center.x, center.y);
        canvas.rotate(lineScreenAngle(projection, line.rotationDegrees));
        canvas.scale(line.scaleX < 0.0 ? -1f : 1f, line.scaleY < 0.0 ? -1f : 1f);
        texturePaint.setAlpha(line.alpha);
        texturePaint.setColorFilter(tint(rgb));
        if (texture.isAnimated()) {
            int duration = texture.durationMs();
            double progress = 0.0;
            if (line.gifEnabled) {
                progress = line.gifProgress;
                if (!line.gifControlled) {
                    progress += (chartTimeMs - line.gifAnchorTimeMs) / (double) duration;
                }
            }
            if (!Double.isFinite(progress) || progress < 0.0) progress = 0.0;
            int movieTime = (int) Math.floor(progress * duration) % duration;
            Bitmap frame = texture.bitmapAt(movieTime);
            if (frame != null) {
                canvas.drawBitmap(frame, null,
                        new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f),
                        texturePaint);
            }
        } else {
            canvas.drawBitmap(texture.bitmap, null,
                    new RectF(-width / 2f, -height / 2f, width / 2f, height / 2f),
                    texturePaint);
        }
        texturePaint.setColorFilter(null);
        canvas.restoreToCount(save);
    }

    private PreviewTextureDecoder.Texture customLineTexture(String textureName) {
        String normalized = PreviewTexturePath.normalize(textureName);
        if (normalized == null || normalized.isEmpty()
                || "line.png".equalsIgnoreCase(normalized)) return null;
        PreviewTextureDecoder.Texture texture = lineTextures.get(normalized);
        return texture == null || !texture.isUsable() ? null : texture;
    }

    private static PorterDuffColorFilter tint(int rgb) {
        if ((rgb & 0xFFFFFF) == 0xFFFFFF) return null;
        return new PorterDuffColorFilter(0xFF000000 | rgb, PorterDuff.Mode.MULTIPLY);
    }

    private static int argb(int alpha, int rgb) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static float lineScreenAngle(Projection projection, double rotationDegrees) {
        double radians = Math.toRadians(rotationDegrees);
        double x = Math.cos(radians) * projection.scaleX;
        double y = -Math.sin(radians) * projection.scaleY;
        return (float) Math.toDegrees(Math.atan2(y, x));
    }

    private static boolean isNearViewport(RectF viewport, Point point, float margin) {
        return point.x >= viewport.left - margin && point.x <= viewport.right + margin
                && point.y >= viewport.top - margin && point.y <= viewport.bottom + margin;
    }

    private static final class Projection {
        final RectF viewport;
        final double scaleX;
        final double scaleY;

        Projection(RectF viewport) {
            this.viewport = viewport;
            // Phira maps RPE units from the canonical 1350-wide coordinate space and
            // crops vertically for wider player ratios. Using one pixel scale for both
            // axes keeps circles circular and preserves authored angles.
            double scale = viewport.width() / RPE_WIDTH;
            scaleX = scale;
            scaleY = scale;
        }

        Point point(double x, double y) {
            return new Point(viewport.centerX() + (float) (x * scaleX),
                    viewport.centerY() - (float) (y * scaleY));
        }
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
