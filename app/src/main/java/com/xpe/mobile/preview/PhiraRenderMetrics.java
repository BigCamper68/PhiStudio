package com.xpe.mobile.preview;

/** Exact, resolution-independent metrics used by Phira's gameplay renderer. */
public final class PhiraRenderMetrics {
    private static final double LINE_SCALE_EPSILON = 1.0e-6;
    private static final double BASE_TEXT_WIDTH_RATIO = 0.04;

    private PhiraRenderMetrics() {
    }

    /** Phira's text builder uses {@code 0.04 * viewportWidth * requestedSize}. */
    public static float textSize(float viewportWidth, float requestedSize) {
        if (!Float.isFinite(viewportWidth) || !Float.isFinite(requestedSize)
                || viewportWidth <= 0f || requestedSize <= 0f) {
            return 0f;
        }
        return (float) (viewportWidth * BASE_TEXT_WIDTH_RATIO * requestedSize);
    }

    /** A zero scale produces no line geometry in Phira, on either axis. */
    public static boolean hasVisibleLineScale(double scaleX, double scaleY) {
        return Double.isFinite(scaleX) && Double.isFinite(scaleY)
                && Math.abs(scaleX) > LINE_SCALE_EPSILON
                && Math.abs(scaleY) > LINE_SCALE_EPSILON;
    }

    public static Hud hud(float viewportWidth, float viewportHeight) {
        if (!Float.isFinite(viewportWidth) || !Float.isFinite(viewportHeight)
                || viewportWidth <= 0f || viewportHeight <= 0f) {
            return Hud.empty();
        }
        return new Hud(viewportWidth, viewportHeight);
    }

    /** Pixel-space form of the constants in Phira's gameplay HUD. */
    public static final class Hud {
        public final float marginX;
        public final float pauseBarWidth;
        public final float pauseBarHeight;
        public final float pauseTop;
        public final float pauseFirstLeft;
        public final float pauseSecondLeft;
        public final float pauseCenterX;
        public final float scoreTop;
        public final float comboTop;
        public final float comboLabelGap;
        public final float bottomTextBottom;
        public final float progressHeight;
        public final float progressMarkerHalfWidth;
        public final float scoreTextSize;
        public final float comboTextSize;
        public final float comboLabelTextSize;
        public final float bottomTextSize;

        private Hud(float width, float height) {
            // Phira works in a -1..1 horizontal coordinate system. These are the
            // exact pixel equivalents of its gameplay UI constants.
            marginX = width * 0.015f;
            pauseBarWidth = width * 0.0075f;
            pauseBarHeight = width * 0.024f;
            pauseTop = height * 0.035f;
            pauseFirstLeft = width * 0.01875f;
            pauseSecondLeft = width * 0.03375f;
            pauseCenterX = width * 0.03f;
            scoreTop = height * 0.022f;
            comboTop = height * 0.02f;
            comboLabelGap = width * 0.005f;
            bottomTextBottom = height * 0.972f;
            progressHeight = height * 0.01f;
            progressMarkerHalfWidth = width * 0.0015f;
            scoreTextSize = textSize(width, 0.8f);
            comboTextSize = textSize(width, 1.0f);
            comboLabelTextSize = textSize(width, 0.4f);
            bottomTextSize = textSize(width, 0.5f);
        }

        private Hud() {
            marginX = 0f;
            pauseBarWidth = 0f;
            pauseBarHeight = 0f;
            pauseTop = 0f;
            pauseFirstLeft = 0f;
            pauseSecondLeft = 0f;
            pauseCenterX = 0f;
            scoreTop = 0f;
            comboTop = 0f;
            comboLabelGap = 0f;
            bottomTextBottom = 0f;
            progressHeight = 0f;
            progressMarkerHalfWidth = 0f;
            scoreTextSize = 0f;
            comboTextSize = 0f;
            comboLabelTextSize = 0f;
            bottomTextSize = 0f;
        }

        private static Hud empty() {
            return new Hud();
        }
    }
}
