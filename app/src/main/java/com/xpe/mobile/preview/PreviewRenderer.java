package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;

import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.NoteType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Native Canvas renderer for a deterministic {@link RenderScene}. */
public final class PreviewRenderer {
    private static final double NOTE_WIDTH_RATIO = 989.0 / 8000.0;
    private static final NoteType[] NOTE_DRAW_ORDER = {
            NoteType.HOLD, NoteType.DRAG, NoteType.TAP, NoteType.FLICK
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final NoteTextureSet textures;
    private final float density;
    private final String autoplayLabel;
    private final PhiraTextPainter textPainter;
    private Map<String, PreviewTextureDecoder.Texture> lineTextures = Collections.emptyMap();

    public PreviewRenderer(NoteTextureSet textures, float density, String autoplayLabel, AssetManager assets) {
        if (textures == null) throw new IllegalArgumentException("textures are required");
        this.textures = textures;
        this.density = Math.max(0.5f, density);
        this.autoplayLabel = autoplayLabel == null ? "" : autoplayLabel;
        this.textPainter = new PhiraTextPainter(assets);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    public void setLineTextures(Map<String, PreviewTextureDecoder.Texture> textures) {
        lineTextures = textures == null ? Collections.emptyMap() : textures;
    }

    /** Draws the cached blurred image once over the complete preview area. */
    public void drawBackdrop(Canvas canvas, RectF bounds, Bitmap blurredBackground) {
        if (canvas == null || bounds == null || bounds.isEmpty()) return;
        drawBackground(canvas, bounds, blurredBackground);
    }

    public void draw(Canvas canvas, RenderScene scene, RectF viewport,
                     Bitmap blurredBackground, EditorSettings settings, int activeLineIndex) {
        draw(canvas, scene, viewport, viewport, blurredBackground, settings,
                activeLineIndex, PhiraBackgroundMetrics.DEFAULT_CHART_DIM);
    }

    public void draw(Canvas canvas, RenderScene scene, RectF viewport, RectF backgroundArea,
                     Bitmap blurredBackground, EditorSettings settings, int activeLineIndex,
                     double chartBackgroundDim) {
        if (canvas == null || scene == null || viewport == null || viewport.isEmpty()) return;
        paint.setFakeBoldText(false);
        EditorSettings renderSettings = settings == null ? new EditorSettings() : settings;
        drawBackdrop(canvas, backgroundArea, blurredBackground);
        // Phira dims only the gameplay viewport after its full-area 30% veil.
        // Keep the same image crop across the viewport and surrounding margins.
        double dim = PhiraBackgroundMetrics.gameplayDim(chartBackgroundDim);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb((int) Math.round(255 * dim), 0));
        canvas.drawRect(viewport, paint);
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

    /** Local prpr effects go after lines/notes, before hit particles and HUD. */
    public void drawShaderLayers(Canvas gameplay, Canvas overlay, RenderScene scene, RectF viewport,
                                 RectF backgroundArea, Bitmap background, EditorSettings settings,
                                 int activeLineIndex, double chartBackgroundDim) {
        paint.setFakeBoldText(false);
        drawBackdrop(gameplay, backgroundArea, background);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb((int) Math.round(255 * PhiraBackgroundMetrics.gameplayDim(chartBackgroundDim)), 0));
        gameplay.drawRect(viewport, paint);
        drawScene(gameplay, scene, viewport, settings, activeLineIndex, false, false);
        int save = overlay.save();
        overlay.clipRect(viewport);
        Projection projection = new Projection(viewport);
        drawHitEffects(overlay, projection, scene);
        drawHud(overlay, projection, scene.hud);
        overlay.restoreToCount(save);
    }

    private void drawScene(Canvas canvas, RenderScene scene, RectF viewport,
                           EditorSettings settings, int activeLineIndex,
                           boolean includeHud) {
        drawScene(canvas, scene, viewport, settings, activeLineIndex, includeHud, true);
    }

    private void drawScene(Canvas canvas, RenderScene scene, RectF viewport,
                           EditorSettings settings, int activeLineIndex,
                           boolean includeHud, boolean includeParticles) {
        int save = canvas.save();
        canvas.clipRect(viewport);
        Projection projection = new Projection(viewport);
        // zOrder controls judge-line visuals. Like Phira's note buffer, notes
        // are composited globally by type, above every line's artwork.
        for (RenderScene.RenderLine line : scene.lines) {
            drawLineVisual(canvas, projection, line, settings, activeLineIndex,
                    scene.chartTimeMs);
        }
        for (NoteType type : NOTE_DRAW_ORDER) {
            for (RenderScene.RenderLine line : scene.lines) {
                for (RenderScene.RenderNote note : line.notes) {
                    if (note.type == type) {
                        drawNote(canvas, projection, line, note, settings.showFakeNoteOutline);
                    }
                }
            }
        }
        // Hit effects belong to the whole scene, including effects emitted by
        // hidden lines. Later line textures and notes must not cover them.
        if (includeParticles) drawHitEffects(canvas, projection, scene);
        if (settings.markLineId) {
            for (RenderScene.RenderLine line : scene.lines) {
                if (line.alpha > 0 || !line.notes.isEmpty()) {
                    drawLineId(canvas, projection, line);
                }
            }
        }
        if (includeHud) drawHud(canvas, projection, scene.hud);
        canvas.restoreToCount(save);
    }

    private void drawHitEffects(Canvas canvas, Projection projection, RenderScene scene) {
        for (RenderScene.RenderLine line : scene.lines) {
            for (RenderScene.HitEffect effect : line.hitEffects) drawHitEffect(canvas, projection, effect);
        }
    }

    private void drawHud(Canvas canvas, Projection projection, RenderScene.HudState hud) {
        if (hud == null) return;
        RectF viewport = projection.viewport;
        PhiraRenderMetrics.Hud metrics = PhiraRenderMetrics.hud(viewport.width(), viewport.height());
        boolean fix = hud.useAttachUiFix;
        float left = viewport.left + metrics.marginX, right = viewport.right - metrics.marginX;

        // Preserve prpr's compositing order even when attachUI elements overlap.
        PhiraFontMetrics.Layout score = textPainter.layout(String.format(Locale.US, "%07d", hud.score),
                metrics.scoreTextSize, true, Float.POSITIVE_INFINITY);
        drawHudText(canvas, projection, hud, AttachedUiElement.SCORE,
                PhiraHudLayout.score(score, right, viewport.top + metrics.scoreTop, fix), true);

        float pauseLeft = viewport.left + metrics.pauseFirstLeft;
        float pauseTop = viewport.top + metrics.pauseTop;
        float pauseCenterX = viewport.left + metrics.pauseCenterX;
        float pauseCenterY = pauseTop + metrics.pauseBarHeight / 2;
        withHudTransform(canvas, projection, hud, AttachedUiElement.PAUSE,
                pauseLeft, pauseTop, fix ? pauseLeft : pauseCenterX, fix ? pauseTop : pauseCenterY,
                (target, color, alpha) -> {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(argb(alpha, color));
                    target.drawRect(pauseLeft, pauseTop, pauseLeft + metrics.pauseBarWidth,
                            pauseTop + metrics.pauseBarHeight, paint);
                    float second = viewport.left + metrics.pauseSecondLeft;
                    target.drawRect(second, pauseTop, second + metrics.pauseBarWidth,
                            pauseTop + metrics.pauseBarHeight, paint);
                });

        if (hud.combo >= 3) {
            PhiraFontMetrics.Layout number = textPainter.layout(Integer.toString(hud.combo),
                    metrics.comboTextSize, true, Float.POSITIVE_INFINITY);
            float defaultNumberAscent = textPainter.layout(Integer.toString(hud.combo),
                    metrics.comboTextSize, false, Float.POSITIVE_INFINITY).ascent;
            PhiraHudLayout.Box numberBox = PhiraHudLayout.comboNumber(number,
                    viewport.centerX(), viewport.top + metrics.comboTop, defaultNumberAscent, fix);
            drawHudText(canvas, projection, hud, AttachedUiElement.COMBO_NUMBER, numberBox, true);
            PhiraFontMetrics.Layout label = textPainter.layout(autoplayLabel, metrics.comboLabelTextSize,
                    true, Float.POSITIVE_INFINITY);
            // The fixed mode measures COMBO with the normal UI font even during autoplay.
            float defaultLabelAscent = textPainter.layout("COMBO", metrics.comboLabelTextSize,
                    false, Float.POSITIVE_INFINITY).ascent;
            drawHudText(canvas, projection, hud, AttachedUiElement.COMBO,
                    PhiraHudLayout.comboLabel(label, numberBox, metrics.comboLabelGap,
                            number.ascent, defaultLabelAscent, fix), true);
        }

        float bottom = viewport.top + metrics.bottomTextBottom;
        PhiraFontMetrics.Layout fullName = textPainter.layout(hud.name, metrics.bottomTextSize,
                false, Float.POSITIVE_INFINITY);
        PhiraFontMetrics.Layout name = textPainter.layout(hud.name, metrics.bottomTextSize,
                false, viewport.width() * .4f);
        drawHudText(canvas, projection, hud, AttachedUiElement.NAME,
                PhiraHudLayout.bottom(name, fullName, left, bottom, false, fix), false);
        // prpr limits only the song title; difficulty is not ellipsized.
        PhiraFontMetrics.Layout level = textPainter.layout(hud.level, metrics.bottomTextSize,
                false, Float.POSITIVE_INFINITY);
        drawHudText(canvas, projection, hud, AttachedUiElement.LEVEL,
                PhiraHudLayout.bottom(level, level, right, bottom, true, fix), false);

        float barY = viewport.top, barCenterY = barY + metrics.progressHeight / 2;
        withHudTransform(canvas, projection, hud, AttachedUiElement.BAR,
                viewport.left, barCenterY, viewport.left, barCenterY,
                (target, color, alpha) -> {
                    float completed = (float) Math.max(0, Math.min(1, hud.progress));
                    float markerX = viewport.left + viewport.width() * completed;
                    paint.setStyle(Paint.Style.FILL);
                    // prpr deliberately ignores the attached line color for the progress bar.
                    paint.setColor(argb(Math.round(alpha * .6f), 0xffffff));
                    target.drawRect(viewport.left, barY, markerX, barY + metrics.progressHeight, paint);
                    paint.setColor(argb(alpha, 0xffffff));
                    target.drawRect(markerX - metrics.progressMarkerHalfWidth, barY,
                            markerX + metrics.progressMarkerHalfWidth, barY + metrics.progressHeight, paint);
                });
    }

    private void drawHudText(Canvas canvas, Projection projection, RenderScene.HudState hud,
                             AttachedUiElement element, PhiraHudLayout.Box box, boolean numeric) {
        withHudTransform(canvas, projection, hud, element,
                box.rotationX, box.rotationY, box.scaleX, box.scaleY,
                (target, color, alpha) -> textPainter.draw(target, box.text, numeric,
                        box.x, box.y, box.anchorX, box.anchorY, color, alpha));
    }

    private void withHudTransform(Canvas canvas, Projection projection,
                                  RenderScene.HudState hud, AttachedUiElement element,
                                  float rotationX, float rotationY, float scaleX, float scaleY,
                                  HudContent content) {
        RenderScene.HudTransform transform = hud.transform(element);
        int color = transform == null || transform.colorRgb < 0 ? 0xffffff : transform.colorRgb;
        int alpha = transform == null ? 255 : transform.alpha;
        if (alpha <= 0) return;
        if (transform != null && !PhiraRenderMetrics.hasVisibleLineScale(
                finite(transform.scaleX, 1), finite(transform.scaleY, 1))) return;
        int save = canvas.save();
        if (transform != null) {
            Point translated = projection.point(transform.x, transform.y);
            canvas.translate(translated.x - projection.viewport.centerX(),
                    translated.y - projection.viewport.centerY());
            canvas.rotate(lineScreenAngle(transform.rotationDegrees), rotationX, rotationY);
            canvas.scale((float) finite(transform.scaleX, 1),
                    (float) finite(transform.scaleY, 1), scaleX, scaleY);
        }
        content.draw(canvas, color, alpha);
        canvas.restoreToCount(save);
    }

    private interface HudContent {
        void draw(Canvas canvas, int colorRgb, int alpha);
    }

    private void drawBackground(Canvas canvas, RectF bounds, Bitmap background) {
        int save = canvas.save();
        canvas.clipRect(bounds);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRect(bounds, paint);
        if (background != null && !background.isRecycled()) {
            PhiraBackgroundMetrics.Placement placement = PhiraBackgroundMetrics.place(
                    background.getWidth(), background.getHeight(),
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
            backgroundPaint.setAlpha(255);
            canvas.drawBitmap(background, null, new RectF((float) placement.left,
                    (float) placement.top, (float) placement.right, (float) placement.bottom), backgroundPaint);
        }
        paint.setColor(argb((int) Math.round(255 * PhiraBackgroundMetrics.BACKDROP_DIM), 0));
        canvas.drawRect(bounds, paint);
        canvas.restoreToCount(save);
    }

    private void drawLineVisual(Canvas canvas, Projection projection, RenderScene.RenderLine line,
                                EditorSettings settings, int activeLineIndex, long chartTimeMs) {
        Point center = projection.point(line.x, line.y);
        PreviewTextureDecoder.Texture customTexture = customLineTexture(line.textureName);
        boolean active = settings.highlightCurrentLine && line.sourceIndex == activeLineIndex;
        int rgb = PhiraJudgeLineStyle.color(line.colorRgb, line.alpha,
                false, active, settings.lineColorRgb) & 0xFFFFFF;
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
            } else if (line.textureName == null || line.textureName.trim().isEmpty()
                    || "line.png".equalsIgnoreCase(line.textureName.trim())) {
                float halfLength = PhiraJudgeLineStyle.normalHalfLength(
                        projection.viewport.width(), finite(line.scaleX, 1.0));
                float thickness = PhiraJudgeLineStyle.normalThickness(
                        projection.viewport.width(), finite(line.scaleY, 1.0));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(PhiraJudgeLineStyle.color(line.colorRgb, line.alpha,
                        true, active, settings.lineColorRgb));
                int save = canvas.save();
                canvas.translate(center.x, center.y);
                canvas.rotate(lineScreenAngle(line.rotationDegrees));
                // prpr draw_line is a flat-ended quad, with local Scale X/Y.
                // Filling the quad also avoids Canvas's zero-width hairline rule.
                canvas.drawRect(-halfLength, -thickness / 2f,
                        halfLength, thickness / 2f, paint);
                canvas.restoreToCount(save);
            }
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
                          RenderScene.RenderNote note, boolean showFakeNoteOutline) {
        int alpha = note.alpha;
        if (alpha <= 0) return;
        Point start = notePoint(projection, line, note.x, note.startDistance,
                note.above, note.isHold());
        float naturalWidth = (float) (projection.viewport.width() * NOTE_WIDTH_RATIO)
                * textures.widthScale(note.multiHit);
        float width = Math.abs(naturalWidth * (float) note.size);
        if (width < 0.5f) return;
        float angle = lineScreenAngle(line.rotationDegrees)
                + (note.above ? 0f : 180f);

        if (note.isHold()) {
            // Floor distance is signed. A negative-speed section may put the tail
            // behind the active head: Phira omits that body, rather than flipping it.
            float length = (float) ((note.endDistance - note.startDistance)
                    * projection.scaleY);
            int save = canvas.save();
            canvas.translate(start.x, start.y);
            canvas.rotate(angle);
            drawHoldTexture(canvas, 0f, 0f, -length, width, naturalWidth,
                    note.multiHit, note.holdHeadVisible, alpha, note.colorRgb);
            if (note.fake && showFakeNoteOutline && length > 0f) {
                drawFakeMarker(canvas, 0f, -length / 2f, width, length, alpha);
            }
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
        if (note.fake && showFakeNoteOutline) drawFakeMarker(canvas, 0f, 0f, width, height, alpha);
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
        float top = endY;
        float bottom = startY;
        float tailHeight = naturalWidth * tailPixels / bitmap.getWidth();
        float headHeight = naturalWidth * headPixels / bitmap.getWidth();

        texturePaint.setAlpha(alpha);
        texturePaint.setColorFilter(tint(colorRgb));
        if (bottom > top) {
            canvas.drawBitmap(bitmap,
                    new Rect(0, tailPixels, bitmap.getWidth(), bodyBottom),
                    new RectF(left, top, right, bottom), texturePaint);
        }
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
        Point point = projection.notePoint(effect.lineX, effect.lineY,
                effect.localX, effect.localY, effect.rotationDegrees);
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
        double side = above ? 1.0 : -1.0;
        double inclineScale = hold ? 1.0 : 1.0
                - Math.sin(Math.toRadians(line.inclineDegrees)) * distance / 360.0;
        double transformedX = x * inclineScale;
        return projection.notePoint(line.x, line.y, transformedX,
                distance * side, line.rotationDegrees);
    }

    private void drawPaintStrokes(Canvas canvas, Projection projection,
                                  RenderScene.RenderLine line, EditorSettings settings,
                                  boolean active) {
        for (RenderScene.PaintStroke stroke : line.paintStrokes) {
            if (!PhiraRenderMetrics.hasVisibleLineScale(
                    stroke.scaleX, stroke.scaleY)) continue;
            Point point = projection.point(stroke.x, stroke.y);
            // Position follows the chart coordinate area; the brush itself keeps its shape.
            float radiusX = (float) (stroke.radius * Math.abs(stroke.scaleX)
                    * projection.objectScale);
            float radiusY = (float) (stroke.radius * Math.abs(stroke.scaleY)
                    * projection.objectScale);
            if (!isNearViewport(projection.viewport, point, Math.max(radiusX, radiusY))) continue;
            int rgb = PhiraJudgeLineStyle.color(stroke.colorRgb, stroke.alpha,
                    false, active, settings.lineColorRgb) & 0xFFFFFF;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(argb(stroke.alpha, rgb));
            int save = canvas.save();
            canvas.translate(point.x, point.y);
            canvas.rotate(lineScreenAngle(stroke.rotationDegrees));
            canvas.drawOval(-radiusX, -radiusY, radiusX, radiusY, paint);
            canvas.restoreToCount(save);
        }
    }

    private void drawLineText(Canvas canvas, Projection projection,
                              RenderScene.RenderLine line, Point center, int rgb) {
        if (line.text.isEmpty() || line.alpha <= 0) return;
        int save = canvas.save();
        canvas.translate(center.x, center.y);
        canvas.rotate(lineScreenAngle(line.rotationDegrees));
        float scaleX = (float) finite(line.scaleX, 1.0);
        float scaleY = (float) finite(line.scaleY, 1.0);
        canvas.scale(scaleX, scaleY);
        PhiraFontMetrics.Layout layout = textPainter.storyboard(line.text,
                PhiraRenderMetrics.textSize(projection.viewport.width(), 1f));
        textPainter.draw(canvas, layout, false, 0f, 0f, .5f, .5f, rgb, line.alpha);
        canvas.restoreToCount(save);
    }

    private void drawCustomLineTexture(Canvas canvas, Projection projection,
                                       RenderScene.RenderLine line, Point center,
                                       PreviewTextureDecoder.Texture texture, int rgb,
                                       long chartTimeMs) {
        if (line.alpha <= 0 || !texture.isUsable()) return;
        float width = (float) (texture.width * projection.objectScale
                * Math.abs(finite(line.scaleX, 1.0)));
        float height = (float) (texture.height * projection.objectScale
                * Math.abs(finite(line.scaleY, 1.0)));
        if (width < 0.5f || height < 0.5f) return;
        int save = canvas.save();
        canvas.translate(center.x, center.y);
        canvas.rotate(lineScreenAngle(line.rotationDegrees));
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

    private static float lineScreenAngle(double rotationDegrees) {
        // Rotations belong to local geometry, not the normalized chart-position axes.
        return (float) -rotationDegrees;
    }

    private static boolean isNearViewport(RectF viewport, Point point, float margin) {
        return point.x >= viewport.left - margin && point.x <= viewport.right + margin
                && point.y >= viewport.top - margin && point.y <= viewport.bottom + margin;
    }

    private static final class Projection {
        final RectF viewport;
        final double scaleX;
        final double scaleY;
        final double objectScale;

        Projection(RectF viewport) {
            this.viewport = viewport;
            // The editor always shows the complete RPE coordinate area: X ±675, Y ±450.
            scaleX = PreviewCoordinates.scaleX(viewport.width());
            scaleY = PreviewCoordinates.scaleY(viewport.height());
            // Texture pixels and brush radii use ONE scale on both axes. Authored
            // Scale X/Y events may still stretch them intentionally.
            objectScale = scaleX;
        }

        Point localPoint(double lineX, double lineY, double offsetX, double offsetY) {
            Point origin = point(lineX, lineY);
            return new Point(origin.x + (float) (offsetX * objectScale),
                    origin.y - (float) (offsetY * objectScale));
        }

        Point notePoint(double lineX, double lineY, double x, double y, double rotation) {
            Point origin = point(lineX, lineY);
            return new Point(origin.x + (float) PreviewCoordinates.localX(x, y, rotation, scaleX, scaleY),
                    origin.y - (float) PreviewCoordinates.localY(x, y, rotation, scaleX, scaleY));
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
