package com.xpe.mobile.project;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProjectThumbnailLoader {
    private static final int TARGET_WIDTH = 480;
    private static final int TARGET_HEIGHT = 270;
    private static final long MAX_DECODED_PIXELS = 1_000_000L;
    private static final int MAX_DISK_FILES = 96;
    private static final long MAX_DISK_BYTES = 64L * 1024L * 1024L;

    private final File cacheDirectory;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memoryCache = new LruCache<String, Bitmap>(8 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    public ProjectThumbnailLoader(File cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public void load(String projectId, File illustration, ImageView target, int placeholderColor) {
        if (target == null) return;
        target.setImageDrawable(new ColorDrawable(placeholderColor));
        if (illustration == null || !illustration.isFile()) {
            target.setTag(null);
            return;
        }
        final String key;
        try {
            key = ThumbnailCacheKey.forIllustration(projectId, illustration);
        } catch (IOException exception) {
            target.setTag(null);
            return;
        }
        target.setTag(key);
        Bitmap cached;
        synchronized (memoryCache) {
            cached = memoryCache.get(key);
        }
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        executor.execute(() -> {
            Bitmap bitmap = loadOrCreate(key, illustration);
            if (bitmap != null) {
                synchronized (memoryCache) {
                    memoryCache.put(key, bitmap);
                }
            }
            mainHandler.post(() -> {
                if (key.equals(target.getTag()) && bitmap != null) target.setImageBitmap(bitmap);
            });
        });
    }

    public void shutdown() {
        executor.shutdownNow();
        synchronized (memoryCache) {
            memoryCache.evictAll();
        }
    }

    private Bitmap loadOrCreate(String key, File illustration) {
        try {
            if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) return null;
            File cachedFile = new File(cacheDirectory, key + ".png");
            Bitmap cached = BitmapFactory.decodeFile(cachedFile.getAbsolutePath());
            if (cached != null) {
                cachedFile.setLastModified(System.currentTimeMillis());
                return cached;
            }
            Bitmap decoded = decodeBounded(illustration);
            if (decoded == null) return null;
            writeCacheFile(cachedFile, decoded);
            trimDiskCache();
            return decoded;
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            return null;
        }
    }

    private static Bitmap decodeBounded(File illustration) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(illustration.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = ThumbnailSpec.inSampleSize(bounds.outWidth, bounds.outHeight,
                TARGET_WIDTH, TARGET_HEIGHT, MAX_DECODED_PIXELS);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(illustration.getAbsolutePath(), options);
        if (decoded == null) return null;
        long pixels = (long) decoded.getWidth() * (long) decoded.getHeight();
        if (decoded.getWidth() <= TARGET_WIDTH * 2
                && decoded.getHeight() <= TARGET_HEIGHT * 2
                && pixels <= MAX_DECODED_PIXELS) {
            return decoded;
        }
        double scale = Math.min((double) TARGET_WIDTH * 2.0 / decoded.getWidth(),
                (double) TARGET_HEIGHT * 2.0 / decoded.getHeight());
        scale = Math.min(scale, Math.sqrt((double) MAX_DECODED_PIXELS / pixels));
        int width = Math.max(1, (int) Math.floor(decoded.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(decoded.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    private void writeCacheFile(File target, Bitmap bitmap) throws IOException {
        File temporary = File.createTempFile(".thumbnail-", ".tmp", cacheDirectory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)) {
                    throw new IOException("Unable to encode project thumbnail");
                }
                output.flush();
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
    }

    private void trimDiskCache() {
        File[] files = cacheDirectory.listFiles(file -> file.isFile() && file.getName().endsWith(".png"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0L;
        for (int index = 0; index < files.length; index++) {
            bytes += files[index].length();
            if (index >= MAX_DISK_FILES || bytes > MAX_DISK_BYTES) files[index].delete();
        }
    }
}
