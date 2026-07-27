package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Bounded decoder for custom judge-line textures stored in an imported project. */
public final class PreviewTextureDecoder {
    private static final int MAX_TEXTURES = 32;
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_TEXTURE_PIXELS = 4_000_000L;
    private static final long MAX_TOTAL_PIXELS = 12_000_000L;

    private PreviewTextureDecoder() {
    }

    public static Map<String, Texture> decode(File workspace, Iterable<String> textureNames) {
        if (workspace == null || textureNames == null) return Collections.emptyMap();
        Map<String, Texture> result = new LinkedHashMap<>();
        Set<String> normalizedNames = new LinkedHashSet<>();
        for (String value : textureNames) {
            String normalized = PreviewTexturePath.normalize(value);
            if (normalized == null || normalized.isEmpty()
                    || "line.png".equalsIgnoreCase(normalized)) continue;
            normalizedNames.add(normalized);
            if (normalizedNames.size() >= MAX_TEXTURES) break;
        }

        long totalPixels = 0L;
        for (String name : normalizedNames) {
            File file = PreviewTexturePath.resolveInside(workspace, name);
            if (file == null || !file.isFile() || file.length() <= 0L
                    || file.length() > MAX_FILE_BYTES) continue;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
                long pixels = (long) bounds.outWidth * bounds.outHeight;
                if (pixels <= 0L || pixels > MAX_TEXTURE_PIXELS
                        || totalPixels + pixels > MAX_TOTAL_PIXELS) continue;

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) {
                    Movie movie = Movie.decodeFile(file.getAbsolutePath());
                    if (movie != null && movie.width() > 0 && movie.height() > 0) {
                        result.put(name, Texture.animated(movie));
                        totalPixels += pixels;
                        continue;
                    }
                }
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                if (bitmap == null) continue;
                result.put(name, Texture.still(bitmap));
                totalPixels += pixels;
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // A malformed or oversized resource must not make the chart unusable.
            }
        }
        return result;
    }

    public static void recycleAll(Map<String, Texture> textures) {
        if (textures == null) return;
        Set<Texture> unique = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        unique.addAll(textures.values());
        for (Texture texture : unique) {
            if (texture != null) texture.recycle();
        }
    }

    public static final class Texture {
        public final Bitmap bitmap;
        public final Movie movie;
        public final int width;
        public final int height;
        private Bitmap animatedFrame;
        private Canvas animatedCanvas;
        private int renderedMovieTime = -1;

        private Texture(Bitmap bitmap, Movie movie, int width, int height) {
            this.bitmap = bitmap;
            this.movie = movie;
            this.width = width;
            this.height = height;
        }

        static Texture still(Bitmap bitmap) {
            return new Texture(bitmap, null, bitmap.getWidth(), bitmap.getHeight());
        }

        static Texture animated(Movie movie) {
            return new Texture(null, movie, movie.width(), movie.height());
        }

        public boolean isAnimated() {
            return movie != null;
        }

        public boolean isUsable() {
            return movie != null || bitmap != null && !bitmap.isRecycled();
        }

        public int durationMs() {
            return movie == null ? 0 : Math.max(1, movie.duration());
        }

        public Bitmap bitmapAt(int movieTimeMs) {
            if (movie == null) return bitmap;
            int safeTime = Math.max(0, movieTimeMs);
            try {
                if (animatedFrame == null || animatedFrame.isRecycled()) {
                    animatedFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    animatedCanvas = new Canvas(animatedFrame);
                    renderedMovieTime = -1;
                }
                if (renderedMovieTime != safeTime) {
                    animatedFrame.eraseColor(Color.TRANSPARENT);
                    movie.setTime(safeTime);
                    movie.draw(animatedCanvas, 0f, 0f);
                    renderedMovieTime = safeTime;
                }
                return animatedFrame;
            } catch (RuntimeException | OutOfMemoryError ignored) {
                return null;
            }
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (animatedFrame != null && !animatedFrame.isRecycled()) animatedFrame.recycle();
            animatedFrame = null;
            animatedCanvas = null;
        }
    }
}
