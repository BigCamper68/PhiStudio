package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.xpe.mobile.preview.PhiraBackgroundBlur;
import com.xpe.mobile.preview.PhiraBackgroundMetrics;

import java.io.File;

/** Bounded bitmap decoder used for project illustrations behind the editor grid. */
public final class EditorBackgroundDecoder {

    private EditorBackgroundDecoder() {
    }

    public static Bitmap decode(File file, int targetWidth, int targetHeight) {
        Background background = decodeForPreview(file, targetWidth, targetHeight);
        if (background == null) return null;
        if (background.blurred != null) background.blurred.recycle();
        return background.original;
    }

    public static final class Background {
        public final Bitmap original;
        public final Bitmap blurred;

        Background(Bitmap original, Bitmap blurred) {
            this.original = original;
            this.blurred = blurred;
        }

        public void recycle() {
            if (original != null && !original.isRecycled()) original.recycle();
            if (blurred != null && blurred != original && !blurred.isRecycled()) blurred.recycle();
        }
    }

    /** Runs in the existing preview asset executor. Full native resolution up to the memory cap. */
    public static Background decodeForPreview(File file, int targetWidth, int targetHeight) {
        if (file == null || !file.isFile() || targetWidth <= 0 || targetHeight <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sampleSize = PhiraBackgroundMetrics.sampleSize(bounds.outWidth, bounds.outHeight);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap original = null;
        Bitmap blurred = null;
        try {
            original = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (original == null) return null;
            int width = original.getWidth(), height = original.getHeight();
            int[] pixels = new int[width * height];
            original.getPixels(pixels, 0, width, 0, 0, width, height);
            PhiraBackgroundBlur.blurInPlace(pixels, width, height,
                    PhiraBackgroundMetrics.BLUR_SIGMA / sampleSize);
            blurred = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            return new Background(original, blurred);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // The editor remains usable; a failed blur must not leak decoded assets.
            if (blurred != null && !blurred.isRecycled()) blurred.recycle();
            return original == null ? null : new Background(original, null);
        }
    }
}
