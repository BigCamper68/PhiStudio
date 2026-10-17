package com.xpe.mobile.preview;

/** Pixel conversion of game.rs::ui, including legacy and useAttachUiFix pivots. */
public final class PhiraHudLayout {
    private PhiraHudLayout() { }

    public static Box score(PhiraFontMetrics.Layout text, float right, float top, boolean fix) {
        return new Box(text, right, top, 1, 0, right, top,
                fix ? right : right - text.width / 2, fix ? top : top + text.ascent / 2);
    }

    public static Box comboNumber(PhiraFontMetrics.Layout text, float center, float top,
                                  float defaultAscent, boolean fix) {
        float y = top + (fix ? defaultAscent / 2 : 0);
        float rotationY = fix ? y : top + text.ascent / 2;
        return new Box(text, center, y, .5f, fix ? .5f : 0,
                center, rotationY, center, rotationY);
    }

    public static Box comboLabel(PhiraFontMetrics.Layout text, Box number, float gap,
                                 float unitAscent, float defaultAscent, boolean fix) {
        float top = number.bottom() + gap;
        float y = top + (fix ? defaultAscent / 2 : 0);
        float rotationY = fix ? y : top + unitAscent * .2f;
        return new Box(text, number.x, y, .5f, fix ? .5f : 0,
                number.x, rotationY, number.x, rotationY);
    }

    public static Box bottom(PhiraFontMetrics.Layout drawn, PhiraFontMetrics.Layout full,
                              float x, float bottom, boolean right, boolean fix) {
        return new Box(drawn, x, bottom, right ? 1 : 0, 1, x, bottom,
                fix ? x : x + (right ? -1 : 1) * full.width / 2,
                fix ? bottom : bottom - full.ascent / 2);
    }

    public static final class Box {
        public final PhiraFontMetrics.Layout text;
        public final float x, y, anchorX, anchorY, rotationX, rotationY, scaleX, scaleY;
        Box(PhiraFontMetrics.Layout text, float x, float y, float anchorX, float anchorY,
            float rotationX, float rotationY, float scaleX, float scaleY) {
            this.text = text; this.x = x; this.y = y;
            this.anchorX = anchorX; this.anchorY = anchorY;
            this.rotationX = rotationX; this.rotationY = rotationY;
            this.scaleX = scaleX; this.scaleY = scaleY;
        }
        public float left() { return x - text.width * anchorX; }
        public float top() { return y - text.ascent * anchorY; }
        public float bottom() { return top() + text.ascent; }
    }

    /** T(translation) * R(rotationPivot) * S(scalePivot), as Chart::with_element. */
    public static double[] transform(double x, double y, double dx, double dy,
                                     double rotation, double sx, double sy,
                                     double rx, double ry, double px, double py) {
        double scaledX = px + (x - px) * sx, scaledY = py + (y - py) * sy;
        double radians = Math.toRadians(rotation), c = Math.cos(radians), s = Math.sin(radians);
        return new double[]{dx + rx + c * (scaledX - rx) - s * (scaledY - ry),
                dy + ry + s * (scaledX - rx) + c * (scaledY - ry)};
    }
}
