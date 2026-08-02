package com.xpe.mobile.editor;

/** Fits the configured preview-player aspect ratio inside the available editor area. */
public final class PreviewViewportCalculator {
    private PreviewViewportCalculator() {
    }

    public static Result fit(double left, double top, double right, double bottom,
                             int playerWidth, int playerHeight) {
        double availableWidth = Math.max(0.0, right - left);
        double availableHeight = Math.max(0.0, bottom - top);
        double ratio = playerWidth > 0 && playerHeight > 0
                ? playerWidth / (double) playerHeight : 16.0 / 9.0;
        if (!Double.isFinite(ratio) || ratio <= 0.0
                || availableWidth <= 0.0 || availableHeight <= 0.0) {
            return new Result(left, top, right, bottom);
        }
        double fittedWidth = availableWidth;
        double fittedHeight = fittedWidth / ratio;
        if (fittedHeight > availableHeight) {
            fittedHeight = availableHeight;
            fittedWidth = fittedHeight * ratio;
        }
        double horizontalInset = (availableWidth - fittedWidth) / 2.0;
        double verticalInset = (availableHeight - fittedHeight) / 2.0;
        return new Result(left + horizontalInset, top + verticalInset,
                right - horizontalInset, bottom - verticalInset);
    }

    public static final class Result {
        public final double left;
        public final double top;
        public final double right;
        public final double bottom;

        Result(double left, double top, double right, double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
