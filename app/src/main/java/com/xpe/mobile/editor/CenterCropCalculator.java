package com.xpe.mobile.editor;

/** Computes a centered source crop that fills a destination without changing aspect ratio. */
public final class CenterCropCalculator {
    public static final class Crop {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        Crop(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private CenterCropCalculator() {
    }

    public static Crop calculate(int sourceWidth, int sourceHeight,
                                 float destinationWidth, float destinationHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0
                || !Float.isFinite(destinationWidth) || destinationWidth <= 0f
                || !Float.isFinite(destinationHeight) || destinationHeight <= 0f) {
            throw new IllegalArgumentException("Image and destination dimensions must be positive");
        }
        double sourceAspect = sourceWidth / (double) sourceHeight;
        double destinationAspect = destinationWidth / (double) destinationHeight;
        if (sourceAspect > destinationAspect) {
            int cropWidth = Math.max(1, Math.min(sourceWidth,
                    (int) Math.round(sourceHeight * destinationAspect)));
            int left = (sourceWidth - cropWidth) / 2;
            return new Crop(left, 0, left + cropWidth, sourceHeight);
        }
        int cropHeight = Math.max(1, Math.min(sourceHeight,
                (int) Math.round(sourceWidth / destinationAspect)));
        int top = (sourceHeight - cropHeight) / 2;
        return new Crop(0, top, sourceWidth, top + cropHeight);
    }
}
