package com.xpe.mobile.project;

public final class ThumbnailSpec {
    private ThumbnailSpec() {
    }

    public static int inSampleSize(int width, int height, int targetWidth,
                                   int targetHeight, long maxPixels) {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0 || maxPixels <= 0L) {
            throw new IllegalArgumentException("Thumbnail dimensions and pixel limit must be positive");
        }
        int sample = 1;
        while (sample <= (1 << 29)) {
            long sampledWidth = Math.max(1L, width / sample);
            long sampledHeight = Math.max(1L, height / sample);
            boolean oversized = sampledWidth > (long) targetWidth * 2L
                    || sampledHeight > (long) targetHeight * 2L
                    || sampledWidth * sampledHeight > maxPixels;
            if (!oversized) return sample;
            sample <<= 1;
        }
        return 1 << 30;
    }
}
