package com.xpe.mobile.preview;

/** Pixel-space judge-line rules from TeamFlos/phira prpr core/line.rs.
 * The gameplay camera maps two horizontal world units to the viewport width.
 */
public final class PhiraJudgeLineStyle {
    /** Built-in resource pack's colorPerfect, including its alpha (core/resource.rs). */
    public static final int PERFECT_COLOR_ARGB = 0xE1FFEC9F;
    private static final double SCALE_EPSILON = 1.0e-4;

    private PhiraJudgeLineStyle() { }

    public static float normalThickness(float viewportWidth, double scaleY) {
        // Compare the SIGNED scale against +1 before applying its magnitude.
        // A mirrored line (-1) uses the 0.0076 branch in prpr too.
        double base = Math.abs(scaleY - 1.0) > SCALE_EPSILON ? 0.0076 : 0.01;
        return (float) (viewportWidth * 0.5 * base * Math.abs(scaleY));
    }

    public static float normalHalfLength(float viewportWidth, double scaleX) {
        // ChartInfo::default().line_length = 6 horizontal world units.
        return (float) (viewportWidth * 3.0 * Math.abs(scaleX));
    }

    /** Only an ordinary line inherits the autoplay/AP color; artwork stays white.
     * An authored color replaces that fallback. Editor highlighting is optional.
     */
    public static int color(int authoredRgb, int lineAlpha, boolean normal,
                            boolean highlight, int highlightRgb) {
        return color(authoredRgb, (double) lineAlpha, normal, highlight, highlightRgb);
    }

    public static int color(int authoredRgb, double rawAlpha, boolean normal,
                            boolean highlight, int highlightRgb) {
        int base = highlight ? 0xFF000000 | (highlightRgb & 0xFFFFFF)
                : authoredRgb >= 0 ? 0xFF000000 | (authoredRgb & 0xFFFFFF)
                : normal ? PERFECT_COLOR_ARGB : 0xFFFFFFFF;
        return withRawAlpha(base, rawAlpha);
    }

    static int withRawAlpha(int baseArgb, double rawAlpha) {
        // RPE maps opacity to f32 using 1/255. Normal lines then multiply the
        // resource color's alpha before macroquad Vertex converts it to a byte.
        float opacity = Math.max(0f, (float) rawAlpha * (1f / 255f));
        float baseAlpha = (baseArgb >>> 24) / 255f;
        int alpha = (int) Math.max(0f, Math.min(255f, baseAlpha * opacity * 255f));
        return (alpha << 24) | (baseArgb & 0xFFFFFF);
    }
}
