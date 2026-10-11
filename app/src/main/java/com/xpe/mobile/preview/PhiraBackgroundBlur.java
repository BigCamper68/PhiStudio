package com.xpe.mobile.preview;

/** RGB blur compatible with the fastblur 0.1.1 implementation used by prpr.
 * Adapted from Felix Schuett's MIT-licensed fastblur; see assets/licenses/fastblur.txt.
 * Call during asset loading, never during draw(). Alpha is discarded, as in prpr.
 */
public final class PhiraBackgroundBlur {
    private PhiraBackgroundBlur() { }

    public static void blurInPlace(int[] pixels, int width, int height, float sigma) {
        if (pixels == null || width <= 0 || height <= 0 || (long) width * height != pixels.length
                || !Float.isFinite(sigma) || sigma < 0 || sigma > 50) {
            throw new IllegalArgumentException("Invalid background blur dimensions or sigma");
        }
        int[] back = pixels.clone();
        int[] radii = radii(sigma);
        for (int pass = 0; pass < radii.length; pass++) {
            horizontal(back, pixels, width, height, radii[pass]);
            // fastblur returns the front buffer, not the final vertical back buffer.
            // Keep this buffer ordering for pixel compatibility; omit the unused pass.
            if (pass < radii.length - 1) vertical(pixels, back, width, height, radii[pass]);
        }
    }

    private static int[] radii(float sigma) {
        if (sigma <= 0) return new int[]{0,0,0};
        float ideal = (float) Math.sqrt(12f * sigma * sigma / 3f) + 1f;
        int lower = (int) Math.floor(ideal);
        if (lower % 2 == 0) lower--;
        float m = (12f*sigma*sigma - 3f*lower*lower - 12f*lower - 9f) / (-4f*lower - 4f);
        int count = Math.round(m);
        int[] radii = new int[3];
        for (int i = 0; i < 3; i++) radii[i] = (lower + (i < count ? 0 : 2) - 1) / 2;
        return radii;
    }

    private static void horizontal(int[] source, int[] target, int width, int height, int radius) {
        for (int y = 0; y < height; y++) filter(source, target, y*width, 1, width, radius);
    }

    private static void vertical(int[] source, int[] target, int width, int height, int radius) {
        for (int x = 0; x < width; x++) filter(source, target, x, width, height, radius);
    }

    private static void filter(int[] source, int[] target, int start, int step, int length, int radius) {
        int red = 0, green = 0, blue = 0;
        for (int offset = -radius; offset <= radius; offset++) {
            int pixel = source[start + Math.max(0, Math.min(length - 1, offset))*step];
            red += pixel >>> 16 & 255;
            green += pixel >>> 8 & 255;
            blue += pixel & 255;
        }
        float inverse = 1f / (radius*2 + 1);
        for (int i = 0; i < length; i++) {
            target[start + i*step] = 0xFF000000 | (round(red*inverse) << 16)
                    | (round(green*inverse) << 8) | round(blue*inverse);
            if (i + 1 < length) {
                int leaving = source[start + Math.max(0, i - radius)*step];
                int entering = source[start + Math.min(length - 1, i + radius + 1)*step];
                red += (entering >>> 16 & 255) - (leaving >>> 16 & 255);
                green += (entering >>> 8 & 255) - (leaving >>> 8 & 255);
                blue += (entering & 255) - (leaving & 255);
            }
        }
    }

    private static int round(float value) {
        // Same IEEE-754 round-to-nearest-even trick as fastblur 0.1.1.
        float shifted = value + 12582912f;
        return (int) (shifted - 12582912f);
    }
}
