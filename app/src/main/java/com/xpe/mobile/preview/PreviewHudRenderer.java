package com.xpe.mobile.preview;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.preview.PreviewRenderer.HudContent;
import com.xpe.mobile.preview.PreviewRenderer.Point;
import com.xpe.mobile.preview.PreviewRenderer.Projection;
import java.util.Locale;

/** PreviewHudRenderer responsibilities extracted from PreviewRenderer. */
final class PreviewHudRenderer {
    private final PreviewRenderer delegateOwner;

    PreviewHudRenderer(PreviewRenderer delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawHud(Canvas canvas, Projection projection, RenderScene.HudState hud) {
        if (hud == null) return;
        RectF viewport = projection.viewport;
        PhiraRenderMetrics.Hud metrics = PhiraRenderMetrics.hud(viewport.width(), viewport.height());
        boolean fix = hud.useAttachUiFix;
        float left = viewport.left + metrics.marginX, right = viewport.right - metrics.marginX;

        // Preserve prpr's compositing order even when attachUI elements overlap.
        PhiraFontMetrics.Layout score = delegateOwner.textPainter.layout(String.format(Locale.US, "%07d", hud.score),
                metrics.scoreTextSize, true, Float.POSITIVE_INFINITY);
        delegateOwner.previewHudRenderer.drawHudText(canvas, projection, hud, AttachedUiElement.SCORE,
                PhiraHudLayout.score(score, right, viewport.top + metrics.scoreTop, fix), true);

        float pauseLeft = viewport.left + metrics.pauseFirstLeft;
        float pauseTop = viewport.top + metrics.pauseTop;
        float pauseCenterX = viewport.left + metrics.pauseCenterX;
        float pauseCenterY = pauseTop + metrics.pauseBarHeight / 2;
        delegateOwner.previewHudRenderer.withHudTransform(canvas, projection, hud, AttachedUiElement.PAUSE,
                pauseLeft, pauseTop, fix ? pauseLeft : pauseCenterX, fix ? pauseTop : pauseCenterY,
                (target, color, alpha) -> {
                    delegateOwner.paint.setStyle(Paint.Style.FILL);
                    delegateOwner.paint.setColor(PreviewRenderer.argb(alpha, color));
                    target.drawRect(pauseLeft, pauseTop, pauseLeft + metrics.pauseBarWidth,
                            pauseTop + metrics.pauseBarHeight, delegateOwner.paint);
                    float second = viewport.left + metrics.pauseSecondLeft;
                    target.drawRect(second, pauseTop, second + metrics.pauseBarWidth,
                            pauseTop + metrics.pauseBarHeight, delegateOwner.paint);
                });

        if (hud.combo >= 3) {
            PhiraFontMetrics.Layout number = delegateOwner.textPainter.layout(Integer.toString(hud.combo),
                    metrics.comboTextSize, true, Float.POSITIVE_INFINITY);
            float defaultNumberAscent = delegateOwner.textPainter.layout(Integer.toString(hud.combo),
                    metrics.comboTextSize, false, Float.POSITIVE_INFINITY).ascent;
            PhiraHudLayout.Box numberBox = PhiraHudLayout.comboNumber(number,
                    viewport.centerX(), viewport.top + metrics.comboTop, defaultNumberAscent, fix);
            delegateOwner.previewHudRenderer.drawHudText(canvas, projection, hud, AttachedUiElement.COMBO_NUMBER, numberBox, true);
            PhiraFontMetrics.Layout label = delegateOwner.textPainter.layout(delegateOwner.autoplayLabel, metrics.comboLabelTextSize,
                    true, Float.POSITIVE_INFINITY);
            // The fixed mode measures COMBO with the normal UI font even during autoplay.
            float defaultLabelAscent = delegateOwner.textPainter.layout("COMBO", metrics.comboLabelTextSize,
                    false, Float.POSITIVE_INFINITY).ascent;
            delegateOwner.previewHudRenderer.drawHudText(canvas, projection, hud, AttachedUiElement.COMBO,
                    PhiraHudLayout.comboLabel(label, numberBox, metrics.comboLabelGap,
                            number.ascent, defaultLabelAscent, fix), true);
        }

        float bottom = viewport.top + metrics.bottomTextBottom;
        PhiraFontMetrics.Layout fullName = delegateOwner.textPainter.layout(hud.name, metrics.bottomTextSize,
                false, Float.POSITIVE_INFINITY);
        PhiraFontMetrics.Layout name = delegateOwner.textPainter.layout(hud.name, metrics.bottomTextSize,
                false, viewport.width() * .4f);
        delegateOwner.previewHudRenderer.drawHudText(canvas, projection, hud, AttachedUiElement.NAME,
                PhiraHudLayout.bottom(name, fullName, left, bottom, false, fix), false);
        // prpr limits only the song title; difficulty is not ellipsized.
        PhiraFontMetrics.Layout level = delegateOwner.textPainter.layout(hud.level, metrics.bottomTextSize,
                false, Float.POSITIVE_INFINITY);
        delegateOwner.previewHudRenderer.drawHudText(canvas, projection, hud, AttachedUiElement.LEVEL,
                PhiraHudLayout.bottom(level, level, right, bottom, true, fix), false);

        float barY = viewport.top, barCenterY = barY + metrics.progressHeight / 2;
        delegateOwner.previewHudRenderer.withHudTransform(canvas, projection, hud, AttachedUiElement.BAR,
                viewport.left, barCenterY, viewport.left, barCenterY,
                (target, color, alpha) -> {
                    float completed = (float) Math.max(0, Math.min(1, hud.progress));
                    float markerX = viewport.left + viewport.width() * completed;
                    delegateOwner.paint.setStyle(Paint.Style.FILL);
                    // prpr deliberately ignores the attached line color for the progress bar.
                    delegateOwner.paint.setColor(PreviewRenderer.argb(Math.round(alpha * .6f), 0xffffff));
                    target.drawRect(viewport.left, barY, markerX, barY + metrics.progressHeight, delegateOwner.paint);
                    delegateOwner.paint.setColor(PreviewRenderer.argb(alpha, 0xffffff));
                    target.drawRect(markerX - metrics.progressMarkerHalfWidth, barY,
                            markerX + metrics.progressMarkerHalfWidth, barY + metrics.progressHeight, delegateOwner.paint);
                });
    }

    void drawHudText(Canvas canvas, Projection projection, RenderScene.HudState hud,
                             AttachedUiElement element, PhiraHudLayout.Box box, boolean numeric) {
        delegateOwner.previewHudRenderer.withHudTransform(canvas, projection, hud, element,
                box.rotationX, box.rotationY, box.scaleX, box.scaleY,
                (target, color, alpha) -> delegateOwner.textPainter.draw(target, box.text, numeric,
                        box.x, box.y, box.anchorX, box.anchorY, color, alpha));
    }

    void withHudTransform(Canvas canvas, Projection projection,
                                  RenderScene.HudState hud, AttachedUiElement element,
                                  float rotationX, float rotationY, float scaleX, float scaleY,
                                  HudContent content) {
        RenderScene.HudTransform transform = hud.transform(element);
        int color = transform == null || transform.colorRgb < 0 ? 0xffffff : transform.colorRgb;
        int alpha = transform == null ? 255 : transform.alpha;
        if (alpha <= 0) return;
        if (transform != null && !PhiraRenderMetrics.hasVisibleLineScale(
                PreviewRenderer.finite(transform.scaleX, 1), PreviewRenderer.finite(transform.scaleY, 1))) return;
        int save = canvas.save();
        if (transform != null) {
            Point translated = projection.point(transform.x, transform.y);
            canvas.translate(translated.x - projection.viewport.centerX(),
                    translated.y - projection.viewport.centerY());
            canvas.rotate(PreviewRenderer.lineScreenAngle(transform.rotationDegrees), rotationX, rotationY);
            canvas.scale((float) PreviewRenderer.finite(transform.scaleX, 1),
                    (float) PreviewRenderer.finite(transform.scaleY, 1), scaleX, scaleY);
        }
        content.draw(canvas, color, alpha);
        canvas.restoreToCount(save);
    }
}
