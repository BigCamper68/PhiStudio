package com.xpe.mobile.editor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** Bounded bitmap decoder used for project illustrations behind the editor grid. */
public final class EditorBackgroundDecoder {
    private static final long MAX_DECODED_PIXELS = 4_000_000L;

    private EditorBackgroundDecoder() {
    }

    public static Bitmap decode(File file, int targetWidth, int targetHeight) {
        if (file == null || !file.isFile() || targetWidth <= 0 || targetHeight <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sampleSize = 1;
        while (sampleSize < 128) {
            int decodedWidth = Math.max(1, bounds.outWidth / sampleSize);
            int decodedHeight = Math.max(1, bounds.outHeight / sampleSize);
            long decodedPixels = (long) decodedWidth * decodedHeight;
            boolean largeForTarget = decodedWidth > targetWidth * 2
                    && decodedHeight > targetHeight * 2;
            if (decodedPixels <= MAX_DECODED_PIXELS && !largeForTarget) break;
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }
}
