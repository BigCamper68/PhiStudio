package com.xpe.mobile.preview;

import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bundled prpr fonts shared by HUD/storyboard; editor labels retain their own Paint. */
final class PhiraTextPainter {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Typeface numbers, metadata;
    private final PhiraFontMetrics numberMetrics, metadataMetrics;
    private final GlyphPainter glyphPainter;
    private final LinkedHashMap<String, PhiraFontMetrics.Layout> storyboardCache = new LinkedHashMap<>(128, .75f, true);
    private int storyboardGlyphs;
    private final Map<String, PhiraFontMetrics.Layout> cache = new LinkedHashMap<String, PhiraFontMetrics.Layout>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, PhiraFontMetrics.Layout> entry) {
            return size() > 64;
        }
    };

    PhiraTextPainter(AssetManager assets) {
        if (Build.VERSION.SDK_INT >= 31) {
            // Do not load a second Typeface copy of the large multilingual font.
            numbers = metadata = null;
            glyphPainter = new GlyphPainter(assets);
        } else {
            numbers = Typeface.createFromAsset(assets, "fonts/phigros.ttf");
            metadata = Typeface.createFromAsset(assets, "fonts/phira.ttf");
            glyphPainter = null;
        }
        numberMetrics = read(assets, "fonts/phigros.metrics");
        metadataMetrics = read(assets, "fonts/phira.metrics");
        paint.setHinting(Paint.HINTING_OFF);
        paint.setLinearText(true);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private static PhiraFontMetrics read(AssetManager assets, String path) {
        try (InputStream in = assets.open(path)) { return new PhiraFontMetrics(in); }
        catch (IOException error) { throw new IllegalStateException("Bundled HUD font is missing", error); }
    }

    PhiraFontMetrics.Layout layout(String text, float scale, boolean numeric, float maximumWidth) {
        String key = numeric + ":" + scale + ":" + maximumWidth + ":" + text;
        PhiraFontMetrics.Layout result = cache.get(key);
        if (result == null) {
            result = (numeric ? numberMetrics : metadataMetrics).layout(text, scale, maximumWidth);
            cache.put(key, result);
        }
        return result;
    }

    void draw(Canvas canvas, PhiraFontMetrics.Layout layout, boolean numeric,
              float x, float y, float anchorX, float anchorY, int color, int alpha) {
        if (glyphPainter == null) paint.setTypeface(numeric ? numbers : metadata);
        paint.setTextSize(layout.emSize);
        paint.setColor((alpha << 24) | (color & 0xffffff));
        float left = layout.left(x, anchorX);
        float baseline = layout.baseline(y, anchorY);
        if (Build.VERSION.SDK_INT >= 31 && glyphPainter != null) {
            glyphPainter.draw(canvas, layout, numeric, left, baseline, paint);
            return;
        }
        // On API 26-30 place individual characters at the same reference advances.
        // Older Canvas has no raw-glyph API; unknown/complex characters can still
        // use platform fallback/isolated shaping. API 31+ above bypasses both.
        for (int i = 0; i < layout.codepoints.length; i++) {
            canvas.drawText(layout.glyphTexts[i],
                    left + layout.positions[i], baseline + layout.glyphPositions[i * 2 + 1], paint);
        }
    }

    PhiraFontMetrics.Layout storyboard(String text, float scale) {
        String key = scale + ":" + text;
        PhiraFontMetrics.Layout result = storyboardCache.get(key);
        if (result != null) return result;
        result = metadataMetrics.storyboard(text, scale);
        // Animated strings cannot grow the cache without bound; HUD has a separate cache.
        if (result.codepoints.length > 8192 || (text != null && text.length() > 16384)) return result;
        storyboardCache.put(key, result);
        storyboardGlyphs += result.codepoints.length;
        while (storyboardCache.size() > 128 || storyboardGlyphs > 32768) {
            var entries = storyboardCache.entrySet().iterator();
            storyboardGlyphs -= entries.next().getValue().codepoints.length;
            entries.remove();
        }
        return result;
    }
    /** API 31 batches exact font glyph ids without shaping/fallback, using cached arrays. */
    @android.annotation.TargetApi(31)
    private static final class GlyphPainter {
        private final android.graphics.fonts.Font numbers, metadata;
        GlyphPainter(AssetManager assets) {
            try {
                numbers = new android.graphics.fonts.Font.Builder(assets, "fonts/phigros.ttf").build();
                metadata = new android.graphics.fonts.Font.Builder(assets, "fonts/phira.ttf").build();
            } catch (IOException error) {
                throw new IllegalStateException("Bundled HUD font is missing", error);
            }
        }
        void draw(Canvas canvas, PhiraFontMetrics.Layout layout, boolean numeric,
                  float left, float baseline, Paint paint) {
            if (layout.glyphIds.length == 0) return;
            int saved = canvas.save();
            canvas.translate(left, baseline);
            canvas.drawGlyphs(layout.glyphIds, 0, layout.glyphPositions, 0,
                    layout.glyphIds.length, numeric ? numbers : metadata, paint);
            canvas.restoreToCount(saved);
        }
    }
}
