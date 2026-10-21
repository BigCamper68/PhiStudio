package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Rect;
import com.xpe.mobile.editor.CenterCropCalculator;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.editor.NoteTextureSet;
import com.xpe.mobile.model.NoteType;
import java.util.Collections;
import java.util.Map;

/** Native Canvas renderer for a deterministic {@link RenderScene}. */
public final class PreviewRenderer {
    final PreviewHitEffects previewHitEffects = new PreviewHitEffects(this);
    final PreviewHudRenderer previewHudRenderer = new PreviewHudRenderer(this);
    final PreviewLineRenderer previewLineRenderer = new PreviewLineRenderer(this);
    final PreviewNoteRenderer previewNoteRenderer = new PreviewNoteRenderer(this);
    final PreviewPaintRenderer previewPaintRenderer = new PreviewPaintRenderer(this);

    static final double NOTE_WIDTH_RATIO = 989.0 / 8000.0;
    static final NoteType[] NOTE_DRAW_ORDER = {
            NoteType.HOLD, NoteType.DRAG, NoteType.TAP, NoteType.FLICK
    };

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final NoteTextureSet textures;
    final float density;
    final String autoplayLabel;
    final PhiraTextPainter textPainter;
    Map<String, PreviewTextureDecoder.Texture> lineTextures = Collections.emptyMap();
    private boolean showHud = true;
    float noteScale = 1f;

    public void setShowHud(boolean value) { showHud = value; }

    /** Picks from the sampled scene using the same projection, textures and draw order as rendering. */
    public PreviewNotePicker.Hit pickNote(RenderScene scene, RectF viewport, EditorSettings settings,
                                        float x, float y, float padding) {
        return PreviewNotePicker.pick(this, scene, viewport, settings, x, y, padding);
    }

    public void release() {
        previewPaintRenderer.clearPaintSurfaces();
        previewHitEffects.reset();
    }

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
        previewPaintRenderer.clearPaintSurfaces();
        previewHitEffects.reset();
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
        drawShaderOverlay(overlay, scene, viewport);
    }

    /** A separate backdrop lets native DisabledBlock appear below judge lines. */
    public void drawSeparatedShaderLayers(Canvas backdrop, Canvas gameplay, Canvas overlay,
                                         RenderScene scene, RectF viewport, RectF backgroundArea,
                                         Bitmap background, EditorSettings settings,
                                         int activeLineIndex, double chartBackgroundDim) {
        paint.setFakeBoldText(false);
        drawBackdrop(backdrop, backgroundArea, background);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(argb((int) Math.round(255 * PhiraBackgroundMetrics.gameplayDim(chartBackgroundDim)), 0));
        backdrop.drawRect(viewport, paint);
        drawScene(gameplay, scene, viewport, settings, activeLineIndex, false, false);
        drawShaderOverlay(overlay, scene, viewport);
    }

    /** Same illustration/crop as the note grid; no preview veil or chart background dim. */
    public void drawEditorBackdrop(Canvas canvas, RectF bounds, Bitmap background, EditorSettings settings) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(10, 14, 18));
        canvas.drawRect(bounds, paint);
        if (background == null || background.isRecycled() || bounds.isEmpty()) return;
        CenterCropCalculator.Crop crop = CenterCropCalculator.calculate(background.getWidth(),
                background.getHeight(), bounds.width(), bounds.height());
        backgroundPaint.setAlpha(settings.backgroundBrightness);
        canvas.drawBitmap(background, new Rect(crop.left, crop.top, crop.right, crop.bottom), bounds, backgroundPaint);
    }

    /** Under-grid preview keeps its HUD hidden and hit particles after local shaders. */
    public void drawEditorShaderLayers(Canvas backdrop, Canvas gameplay, Canvas overlay,
                                       RenderScene scene, RectF viewport, RectF area, Bitmap background,
                                       EditorSettings settings, int activeLineIndex) {
        paint.setFakeBoldText(false);
        drawEditorBackdrop(backdrop, area, background, settings);
        drawScene(gameplay, scene, viewport, settings, activeLineIndex, false, false);
        int save = overlay.save(); overlay.clipRect(viewport);
        Projection projection = new Projection(viewport);
        drawBadNotes(overlay, projection, scene);
        previewHitEffects.drawHitEffects(overlay, projection, scene);
        overlay.restoreToCount(save);
    }

    private void drawShaderOverlay(Canvas overlay, RenderScene scene, RectF viewport) {
        int save = overlay.save();
        overlay.clipRect(viewport);
        Projection projection = new Projection(viewport);
        drawBadNotes(overlay, projection, scene);
        previewHitEffects.drawHitEffects(overlay, projection, scene);
        if (showHud) previewHudRenderer.drawHud(overlay, projection, scene.hud);
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
        noteScale = Double.isFinite(settings.previewNoteScale)
                ? (float) Math.max(.8, Math.min(1.2, settings.previewNoteScale)) : 1f;
        int save = canvas.save();
        canvas.clipRect(viewport);
        Projection projection = new Projection(viewport);
        previewPaintRenderer.retainScene(scene.paintHistories);
        // zOrder controls judge-line visuals. Like Phira's note buffer, notes
        // are composited globally by type, above every line's artwork.
        for (RenderScene.RenderLine line : scene.lines) {
            previewLineRenderer.drawLineVisual(canvas, projection, line, settings, activeLineIndex,
                    scene.chartTimeMs, scene.paintHistories.get(line.sourceIndex));
        }
        for (NoteType type : NOTE_DRAW_ORDER) {
            for (RenderScene.RenderLine line : scene.lines) {
                for (RenderScene.RenderNote note : line.notes) {
                    if (note.type == type) {
                        previewNoteRenderer.drawNote(canvas, projection, line, note, settings.showFakeNoteOutline);
                    }
                }
            }
        }
        // Hit effects belong to the whole scene, including effects emitted by
        // hidden lines. Later line textures and notes must not cover them.
        if (includeParticles) {
            drawBadNotes(canvas, projection, scene);
            previewHitEffects.drawHitEffects(canvas, projection, scene);
        }
        if (settings.markLineId) {
            for (RenderScene.RenderLine line : scene.lines) {
                if (line.alpha > 0 || !line.notes.isEmpty()) {
                    previewLineRenderer.drawLineId(canvas, projection, line);
                }
            }
        }
        if (includeHud && showHud) previewHudRenderer.drawHud(canvas, projection, scene.hud);
        canvas.restoreToCount(save);
    }

    interface HudContent {
        void draw(Canvas canvas, int colorRgb, int alpha);
    }

    private void drawBadNotes(Canvas canvas, Projection projection, RenderScene scene) {
        for (RenderScene.RenderLine line : scene.badNotes)
            for (RenderScene.RenderNote note : line.notes)
                previewNoteRenderer.drawNote(canvas, projection, line, note, false);
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

    static PorterDuffColorFilter tint(int rgb) {
        if ((rgb & 0xFFFFFF) == 0xFFFFFF) return null;
        return new PorterDuffColorFilter(0xFF000000 | rgb, PorterDuff.Mode.MULTIPLY);
    }

    static int argb(int alpha, int rgb) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

    static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    static float lineScreenAngle(double rotationDegrees) {
        // Rotations belong to local geometry, not the normalized chart-position axes.
        return PrprRotation.screenAngle(rotationDegrees);
    }

    static boolean isNearViewport(RectF viewport, Point point, float margin) {
        return point.x >= viewport.left - margin && point.x <= viewport.right + margin
                && point.y >= viewport.top - margin && point.y <= viewport.bottom + margin;
    }

    static final class Projection {
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

    static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
