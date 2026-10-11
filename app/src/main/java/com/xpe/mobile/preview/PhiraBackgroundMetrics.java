package com.xpe.mobile.preview;

/** Background composition from prpr scene.rs, scene/game.rs and scene/loading.rs. */
public final class PhiraBackgroundMetrics {
    public static final float BLUR_SIGMA = 50f;
    public static final double BACKDROP_DIM = 0.3;
    public static final double DEFAULT_CHART_DIM = 0.6;
    public static final long MAX_DECODED_PIXELS = 4_000_000L;

    private PhiraBackgroundMetrics() { }

    public static double chartDim(String value) {
        try {
            double dim = Double.parseDouble(value.replaceFirst("\\s+#.*$", "").trim());
            return Double.isFinite(dim) && dim >= 0 && dim <= 1 ? dim : DEFAULT_CHART_DIM;
        } catch (RuntimeException ignored) {
            return DEFAULT_CHART_DIM;
        }
    }

    public static double gameplayDim(double chartDim) {
        double result = chartDim;
        return Double.isFinite(result) ? Math.max(0, Math.min(1, result)) : DEFAULT_CHART_DIM;
    }

    public static int sampleSize(int width, int height) {
        int sample = 1;
        while (((width + (long)sample - 1) / sample) * ((height + (long)sample - 1) / sample)
                > MAX_DECODED_PIXELS) sample *= 2;
        return sample;
    }

    /** Floating-point center-crop destination; do not crop again at the gameplay viewport. */
    public static Placement place(int width, int height, double left, double top, double right, double bottom) {
        double scale = Math.max((right-left)/width, (bottom-top)/height);
        double w = width*scale, h = height*scale;
        double x = (left + right - w)/2, y = (top + bottom - h)/2;
        return new Placement(x, y, x+w, y+h);
    }

    public static final class Placement {
        public final double left, top, right, bottom;
        Placement(double left, double top, double right, double bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }
}
