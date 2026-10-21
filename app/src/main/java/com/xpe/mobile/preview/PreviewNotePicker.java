package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.RectF;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.Note;

/** Read-only editor picking. Does not use Playtest's judgement or noise-field input rules. */
public final class PreviewNotePicker {
    private PreviewNotePicker() { }

    public static final class Hit {
        public final int lineIndex;
        public final Note note;
        private Hit(int lineIndex, Note note) { this.lineIndex = lineIndex; this.note = note; }
    }

    static Hit pick(PreviewRenderer renderer, RenderScene scene, RectF viewport,
                    EditorSettings settings, float x, float y, float padding) {
        if (scene == null || viewport == null || viewport.isEmpty() || !viewport.contains(x, y)) return null;
        PreviewRenderer.Projection projection = new PreviewRenderer.Projection(viewport);
        double scale = settings == null ? 1 : settings.previewNoteScale;
        scale = Double.isFinite(scale) ? Math.max(.8, Math.min(1.2, scale)) : 1;
        double nearest = Math.max(0, padding) * Math.max(0, padding);
        Hit best = null;
        // Reverse the actual global type/line/note drawing order. Direct hits
        // beat padded hits, and ties select the visually topmost note.
        for (int type = PreviewRenderer.NOTE_DRAW_ORDER.length - 1; type >= 0; type--)
            for (int li = scene.lines.size() - 1; li >= 0; li--) {
                var line = scene.lines.get(li);
                for (int ni = line.notes.size() - 1; ni >= 0; ni--) {
                    var note = line.notes.get(ni);
                    if (note.type != PreviewRenderer.NOTE_DRAW_ORDER[type] || note.source == null || note.alpha <= 0) continue;
                    double distance = distance(renderer, projection, line, note, scale, x, y);
                    if (distance == 0) return new Hit(line.sourceIndex, note.source);
                    if (distance < nearest) {
                        nearest = distance; best = new Hit(line.sourceIndex, note.source);
                    }
                }
            }
        return best;
    }

    private static double distance(PreviewRenderer renderer, PreviewRenderer.Projection projection,
                                   RenderScene.RenderLine line, RenderScene.RenderNote note,
                                   double scale, double x, double y) {
        double headDistance = note.isHold()
                ? note.yOffset + (note.startDistance - note.yOffset) * note.sizeY : note.startDistance;
        var start = PreviewNoteRenderer.notePoint(projection, line, note.x, headDistance, note.above, note.isHold());
        float natural = (float) (projection.viewport.width() * PreviewRenderer.NOTE_WIDTH_RATIO)
                * renderer.textures.widthScale(note.multiHit) * (float) scale;
        float width = Math.abs(natural * (float) note.size);
        float sy = (float) note.sizeY * (note.above ? 1f : -1f);
        if (!Float.isFinite(start.x + start.y + width + sy) || width < .5f || sy == 0) return Double.POSITIVE_INFINITY;
        Bitmap bitmap = renderer.textures.bitmap(note.type, note.multiHit);
        if (bitmap == null || bitmap.isRecycled()) return Double.POSITIVE_INFINITY;
        double angle = Math.toRadians(PreviewRenderer.lineScreenAngle(line.rotationDegrees));
        if (!Double.isFinite(angle)) return Double.POSITIVE_INFINITY;
        double c = Math.cos(angle), s = Math.sin(angle), halfWidth = width / 2.;
        if (!note.isHold()) {
            double halfHeight = natural * bitmap.getHeight() / bitmap.getWidth() * Math.abs(sy) / 2;
            return rectangleDistance(projection.viewport, start.x, start.y, c, s,
                    halfWidth, -halfHeight, halfHeight, x, y);
        }
        float length = (float) ((note.endDistance - note.startDistance) * projection.scaleY);
        float tail = natural * renderer.textures.holdTailPixels(note.multiHit) / bitmap.getWidth();
        float head = natural * renderer.textures.holdHeadPixels(note.multiHit) / bitmap.getWidth();
        double result = rectangleDistance(projection.viewport, start.x, start.y, c, s, halfWidth,
                (-length - tail) * sy, -length * sy, x, y);
        if (length > 0) result = Math.min(result, rectangleDistance(projection.viewport, start.x, start.y, c, s,
                halfWidth, -length * sy, 0, x, y));
        if (note.holdHeadVisible) result = Math.min(result, rectangleDistance(projection.viewport, start.x, start.y, c, s,
                halfWidth, 0, head * sy, x, y));
        return result;
    }

    private static double rectangleDistance(RectF viewport, double ox, double oy, double c, double s,
                                            double halfWidth, double y1, double y2, double x, double y) {
        if (!Double.isFinite(y1 + y2)) return Double.POSITIVE_INFINITY;
        double low = Math.min(y1, y2), high = Math.max(y1, y2);
        if (high <= low) return Double.POSITIVE_INFINITY;
        double cy = (low + high) / 2, halfHeight = (high - low) / 2;
        double wx = ox - s * cy, wy = oy + c * cy;
        double dx = viewport.centerX() - wx, dy = viewport.centerY() - wy;
        double vx = viewport.width() / 2., vy = viewport.height() / 2.;
        double ac = Math.abs(c), as = Math.abs(s);
        // SAT rejects fully clipped rectangles before adding the finger tolerance.
        if (Math.abs(dx) > vx + ac * halfWidth + as * halfHeight
                || Math.abs(dy) > vy + as * halfWidth + ac * halfHeight
                || Math.abs(c * dx + s * dy) > halfWidth + ac * vx + as * vy
                || Math.abs(-s * dx + c * dy) > halfHeight + as * vx + ac * vy) return Double.POSITIVE_INFINITY;
        double localX = c * (x - ox) + s * (y - oy);
        double localY = -s * (x - ox) + c * (y - oy);
        double ex = Math.max(0, Math.abs(localX) - halfWidth);
        double ey = Math.max(0, Math.max(low - localY, localY - high));
        return ex * ex + ey * ey;
    }
}
