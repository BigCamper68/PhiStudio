package com.xpe.mobile.preview;

import com.xpe.mobile.model.AttachedUiElement;
import com.xpe.mobile.model.NoteType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Immutable, Android-independent scene produced for one chart time. */
public final class RenderScene {
    public final double beat;
    public final long chartTimeMs;
    public final List<RenderLine> lines;
    public final HudState hud;
    final Map<Integer, PaintHistory> paintHistories;

    RenderScene(double beat, long chartTimeMs, List<RenderLine> lines, HudState hud) {
        this(beat, chartTimeMs, lines, hud, Collections.emptyMap());
    }

    RenderScene(double beat, long chartTimeMs, List<RenderLine> lines, HudState hud,
                Map<Integer, PaintHistory> paintHistories) {
        this.paintHistories = Collections.unmodifiableMap(new java.util.HashMap<>(paintHistories));
        this.beat = beat;
        this.chartTimeMs = chartTimeMs;
        this.lines = Collections.unmodifiableList(lines);
        this.hud = hud;
    }

    public static final class RenderLine {
        public final int sourceIndex;
        public final int zOrder;
        public final double x;
        public final double y;
        public final double rotationDegrees;
        public final int alpha;
        /** Authored opacity before byte saturation, so normal-line color can multiply it first. */
        public final double rawAlpha;
        /** -1 means use the renderer's default line color. */
        public final int colorRgb;
        public final double scaleX;
        public final double scaleY;
        public final double inclineDegrees;
        public final String text;
        public final String textureName;
        public final boolean paintMode;
        public final boolean gifEnabled;
        public final boolean gifControlled;
        public final double gifProgress;
        public final long gifAnchorTimeMs;
        public final PreviewGifTimeline gifTimeline;
        public final double gifTimeSeconds;
        public final boolean cover;
        public final List<RenderNote> notes;
        public final List<HitEffect> hitEffects;
        public final List<PaintStroke> paintStrokes;

        RenderLine(int sourceIndex, int zOrder, double x, double y,
                   double rotationDegrees, int alpha, int colorRgb,
                   double scaleX, double scaleY, double inclineDegrees,
                   String text, String textureName, boolean paintMode, boolean cover,
                   boolean gifEnabled, boolean gifControlled,
                   double gifProgress, long gifAnchorTimeMs,
                   List<RenderNote> notes, List<HitEffect> hitEffects,
                   List<PaintStroke> paintStrokes) {
            this(sourceIndex, zOrder, x, y, rotationDegrees, alpha, alpha, colorRgb,
                    scaleX, scaleY, inclineDegrees, text, textureName, paintMode, cover,
                    gifEnabled, gifControlled, gifProgress, gifAnchorTimeMs, notes, hitEffects, paintStrokes);
        }

        RenderLine(int sourceIndex, int zOrder, double x, double y,
                   double rotationDegrees, int alpha, double rawAlpha, int colorRgb,
                   double scaleX, double scaleY, double inclineDegrees,
                   String text, String textureName, boolean paintMode, boolean cover,
                   boolean gifEnabled, boolean gifControlled,
                   double gifProgress, long gifAnchorTimeMs,
                   List<RenderNote> notes, List<HitEffect> hitEffects,
                   List<PaintStroke> paintStrokes) {
            this(sourceIndex, zOrder, x, y, rotationDegrees, alpha, rawAlpha, colorRgb,
                    scaleX, scaleY, inclineDegrees, text, textureName, paintMode, cover,
                    gifEnabled, gifControlled, gifProgress, gifAnchorTimeMs, null, 0.0,
                    notes, hitEffects, paintStrokes);
        }

        RenderLine(int sourceIndex, int zOrder, double x, double y,
                   double rotationDegrees, int alpha, double rawAlpha, int colorRgb,
                   double scaleX, double scaleY, double inclineDegrees,
                   String text, String textureName, boolean paintMode, boolean cover,
                   boolean gifEnabled, boolean gifControlled,
                   double gifProgress, long gifAnchorTimeMs,
                   PreviewGifTimeline gifTimeline, double gifTimeSeconds,
                   List<RenderNote> notes, List<HitEffect> hitEffects,
                   List<PaintStroke> paintStrokes) {
            this.sourceIndex = sourceIndex;
            this.zOrder = zOrder;
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
            this.alpha = alpha;
            this.rawAlpha = rawAlpha;
            this.colorRgb = colorRgb;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.inclineDegrees = inclineDegrees;
            this.text = text;
            this.textureName = textureName;
            this.paintMode = paintMode;
            this.gifEnabled = gifEnabled;
            this.gifControlled = gifControlled;
            this.gifProgress = gifProgress;
            this.gifAnchorTimeMs = gifAnchorTimeMs;
            this.gifTimeline = gifTimeline;
            this.gifTimeSeconds = gifTimeSeconds;
            this.cover = cover;
            this.notes = Collections.unmodifiableList(notes);
            this.hitEffects = Collections.unmodifiableList(hitEffects);
            this.paintStrokes = Collections.unmodifiableList(paintStrokes);
        }

        public int gifMovieTimeMillis(int durationMs, long chartTimeMs) {
            if (durationMs <= 0) return 0;
            if (gifEnabled && gifTimeline != null) {
                return gifTimeline.movieTimeMillis(gifTimeSeconds, durationMs);
            }
            double progress = 0.0;
            if (gifEnabled) {
                progress = gifProgress;
                if (!gifControlled) progress += (chartTimeMs - gifAnchorTimeMs) / (double) durationMs;
            }
            if (!Double.isFinite(progress) || progress < 0.0) progress = 0.0;
            return (int) Math.floor(progress * durationMs) % durationMs;
        }
    }

    public static final class RenderNote {
        public final NoteType type;
        public final double x;
        public final double startDistance;
        public final double endDistance;
        public final boolean above;
        public final double size;
        public final double sizeY;
        public final double yOffset;
        public final int alpha;
        public final boolean fake;
        public final boolean multiHit;
        public final boolean holdHeadVisible;
        public final int colorRgb;

        RenderNote(NoteType type, double x, double startDistance, double endDistance,
                   boolean above, double size, int alpha, boolean fake, boolean multiHit,
                   boolean holdHeadVisible, int colorRgb) {
            this(type, x, startDistance, endDistance, above, size, alpha, fake, multiHit,
                    holdHeadVisible, colorRgb, 1.0, 0.0);
        }

        RenderNote(NoteType type, double x, double startDistance, double endDistance,
                   boolean above, double size, int alpha, boolean fake, boolean multiHit,
                   boolean holdHeadVisible, int colorRgb, double sizeY, double yOffset) {
            this.sizeY = sizeY;
            this.yOffset = yOffset;
            this.type = type;
            this.x = x;
            this.startDistance = startDistance;
            this.endDistance = endDistance;
            this.above = above;
            this.size = size;
            this.alpha = alpha;
            this.fake = fake;
            this.multiHit = multiHit;
            this.holdHeadVisible = holdHeadVisible;
            this.colorRgb = colorRgb;
        }

        public boolean isHold() {
            return type == NoteType.HOLD;
        }
    }

    public static final class HitEffect {
        public final double worldX;
        public final double worldY;
        /** Judge-line origin at the hit time, retained while the effect fades. */
        public final double lineX;
        public final double lineY;
        public final double localX;
        public final double localY;
        public final double rotationDegrees;
        public final double progress;
        public final int colorRgb;
        public final int seed;
        public final int alpha;

        HitEffect(double worldX, double worldY, double lineX, double lineY,
                  double progress, int colorRgb, int seed) {
            this(worldX, worldY, lineX, lineY, worldX - lineX, worldY - lineY,
                    0, progress, colorRgb, seed);
        }

        HitEffect(double worldX, double worldY, double lineX, double lineY,
                  double localX, double localY, double rotationDegrees,
                  double progress, int colorRgb, int seed) {
            this(worldX, worldY, lineX, lineY, localX, localY, rotationDegrees,
                    progress, colorRgb, seed, 225);
        }

        HitEffect(double worldX, double worldY, double lineX, double lineY,
                  double localX, double localY, double rotationDegrees,
                  double progress, int colorRgb, int seed, int alpha) {
            this.worldX = worldX;
            this.worldY = worldY;
            this.lineX = lineX;
            this.lineY = lineY;
            this.localX = localX;
            this.localY = localY;
            this.rotationDegrees = rotationDegrees;
            this.progress = progress;
            this.colorRgb = colorRgb;
            this.seed = seed;
            this.alpha = alpha;
        }
    }

    public static final class PaintStroke {
        public final double x;
        public final double y;
        public final double radius;
        public final double rotationDegrees;
        public final double scaleX;
        public final double scaleY;
        public final int colorRgb;
        public final int alpha;

        PaintStroke(double x, double y, double radius, double rotationDegrees,
                    double scaleX, double scaleY, int colorRgb, int alpha) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.rotationDegrees = rotationDegrees;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.colorRgb = colorRgb;
            this.alpha = alpha;
        }
    }

    public static final class HudState {
        public final boolean useAttachUiFix;
        public final String name;
        public final String level;
        public final int combo;
        public final int score;
        public final double progress;
        public final Map<AttachedUiElement, HudTransform> transforms;

        HudState(String name, String level, int combo, int score, double progress,
                 Map<AttachedUiElement, HudTransform> transforms) {
            this(name, level, combo, score, progress, transforms, false);
        }

        HudState(String name, String level, int combo, int score, double progress,
                 Map<AttachedUiElement, HudTransform> transforms, boolean useAttachUiFix) {
            this.useAttachUiFix = useAttachUiFix;
            this.name = name == null ? "" : name;
            this.level = level == null ? "" : level;
            this.combo = combo;
            this.score = score;
            this.progress = progress;
            EnumMap<AttachedUiElement, HudTransform> copied =
                    new EnumMap<>(AttachedUiElement.class);
            copied.putAll(transforms);
            this.transforms = Collections.unmodifiableMap(copied);
        }

        public HudTransform transform(AttachedUiElement element) {
            return transforms.get(element);
        }
    }

    public static final class HudTransform {
        public final int sourceIndex;
        public final double x;
        public final double y;
        public final double rotationDegrees;
        public final int alpha;
        /** -1 means white, matching an absent RPE color event. */
        public final int colorRgb;
        public final double scaleX;
        public final double scaleY;

        HudTransform(int sourceIndex, double x, double y, double rotationDegrees,
                     int alpha, int colorRgb, double scaleX, double scaleY) {
            this.sourceIndex = sourceIndex;
            this.x = x;
            this.y = y;
            this.rotationDegrees = rotationDegrees;
            this.alpha = alpha;
            this.colorRgb = colorRgb;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }
}
