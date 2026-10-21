package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import com.xpe.mobile.preview.PreviewRenderer.Point;
import com.xpe.mobile.preview.PreviewRenderer.Projection;

/** PreviewHitEffects responsibilities extracted from PreviewRenderer. */
final class PreviewHitEffects {
    private final java.util.Map<Integer, PrprParticleMotion> particleMotions = new java.util.HashMap<>();

    void reset() { particleMotions.clear(); }
    private final PreviewRenderer delegateOwner;

    PreviewHitEffects(PreviewRenderer delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    void drawHitEffects(Canvas canvas, Projection projection, RenderScene scene) {
        java.util.HashSet<Integer> active = new java.util.HashSet<>();
        // Both emitters batch globally: all sprites, then all squares.
        for (RenderScene.RenderLine line : scene.lines) {
            for (RenderScene.HitEffect effect : line.hitEffects) {
                active.add(effect.seed);
                delegateOwner.previewHitEffects.drawHitEffect(canvas, projection, effect, false);
            }
        }
        for (RenderScene.RenderLine line : scene.lines) {
            for (RenderScene.HitEffect effect : line.hitEffects) delegateOwner.previewHitEffects.drawHitEffect(canvas, projection, effect, true);
        }
        particleMotions.keySet().retainAll(active);
    }

    void drawHitEffect(Canvas canvas, Projection projection,
                               RenderScene.HitEffect effect, boolean squares) {
        Point point = projection.notePoint(effect.lineX, effect.lineY,
                effect.localX, effect.localY, effect.rotationDegrees);
        float progress = (float) Math.max(0.0, Math.min(1.0, effect.progress));
        int alpha = Math.round(PrprParticleMotion.opacity(progress) * effect.alpha);
        float radius = projection.viewport.width() * PrprParticleMotion.FX_SCALE / 10f * delegateOwner.noteScale;
        if (!PreviewRenderer.isNearViewport(projection.viewport, point, projection.viewport.width() * .25f)) return;

        Bitmap hitEffect = delegateOwner.textures.hitEffectBitmap();
        if (!squares && hitEffect != null && !hitEffect.isRecycled()) {
            int columns = delegateOwner.textures.hitEffectColumns();
            int rows = delegateOwner.textures.hitEffectRows();
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
            delegateOwner.texturePaint.setAlpha(alpha);
            delegateOwner.texturePaint.setColorFilter(PreviewRenderer.tint(effect.colorRgb));
            canvas.drawBitmap(hitEffect, source, destination, delegateOwner.texturePaint);
            delegateOwner.texturePaint.setColorFilter(null);
        }

        if (!squares) return;
        PrprParticleMotion motion = particleMotions.get(effect.seed);
        if (motion == null) {
            motion = new PrprParticleMotion();
            particleMotions.put(effect.seed, motion);
        }
        motion.advance(progress * .5);
        delegateOwner.paint.setStyle(Paint.Style.FILL);
        delegateOwner.paint.setColor(PreviewRenderer.argb(alpha, effect.colorRgb));
        for (int index = 0; index < 4; index++) {
            double angle = PreviewHitEffects.particleAngle(effect.seed, index);
            float travel = (float) (projection.viewport.width() * .5 * motion.distance
                    * (1 - .1 * PreviewHitEffects.deterministicFraction(effect.seed ^ 0x2188A513, index))) * delegateOwner.noteScale;
            float x = point.x + (float) Math.cos(angle) * travel;
            float y = point.y + (float) Math.sin(angle) * travel;
            float halfSize = projection.viewport.width() * PrprParticleMotion.FX_SCALE / 88f
                    * (1 - .3f * PreviewHitEffects.deterministicFraction(effect.seed, index)) * delegateOwner.noteScale;
            canvas.drawRect(x - halfSize, y - halfSize, x + halfSize, y + halfSize, delegateOwner.paint);
        }
    }

    static double particleAngle(int seed, int index) {
        return PreviewHitEffects.deterministicFraction(seed ^ 0x6D2B79F5, index) * Math.PI * 2.0;
    }

    static float deterministicFraction(int seed, int index) {
        int value = seed + index * 0x9E3779B9;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        value ^= value >>> 16;
        return (value & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }
}
