package com.xpe.mobile.audio;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** One validated last-song PCM, plus any files leased by a live controller. No audio samples change. */
final class PlaybackAudioCache {
    private static final Map<File, Integer> leases = new HashMap<>();
    static final class Entry {
        final PcmAudioAsset asset;
        final boolean persistent;
        Entry(PcmAudioAsset asset, boolean persistent) { this.asset = asset; this.persistent = persistent; }
    }
    private PlaybackAudioCache() { }

    static String key(Context context, Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath()).getCanonicalFile();
                return file.isFile() ? file.getPath() + ":" + file.length() + ":" + file.lastModified() : null;
            }
            try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.SIZE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                int size = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                int modified = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (size < 0 || modified < 0 || cursor.isNull(size) || cursor.isNull(modified) || cursor.getLong(modified) <= 0) return null;
                return uri + ":" + cursor.getLong(size) + ":" + cursor.getLong(modified);
            }
        } catch (Exception exception) { return null; }
    }

    private static File directory(Context context) throws java.io.IOException {
        File root = new File(context.getNoBackupFilesDir(), "audio-resume").getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Audio resume storage unavailable");
        return root;
    }

    static synchronized Entry find(Context context, String key) {
        if (key == null) return null;
        try {
            File root = directory(context);
            JSONObject data;
            try (java.io.InputStream input = new AtomicFile(new File(root, "index.json")).openRead()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                data = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            }
            if (!key.equals(data.optString("key"))) return null;
            String name = data.getString("file");
            if (!name.startsWith("decoded-") || !name.endsWith(".pcm") || name.contains("/") || name.contains("\\")) return null;
            File pcm = new File(root, name).getCanonicalFile();
            if (!root.equals(pcm.getParentFile())) return null;
            PcmAudioAsset asset = new PcmAudioAsset(pcm, data.getInt("rate"), data.getInt("channels"), data.getLong("frames"));
            if (asset.totalFrames <= 0 || asset.totalFrames > Long.MAX_VALUE / asset.bytesPerFrame()
                    || !pcm.isFile() || pcm.length() != asset.totalFrames * asset.bytesPerFrame()) return null;
            leases.put(pcm, leases.getOrDefault(pcm, 0) + 1);
            return new Entry(asset, true);
        } catch (Exception exception) { return null; }
    }

    static synchronized void discardUnused(Context context) {
        try {
            File root = directory(context); clean(root, null);
            // Keep an index while another controller is still using its PCM.
            if (leases.keySet().stream().noneMatch(file -> root.equals(file.getParentFile())))
                new AtomicFile(new File(root, "index.json")).delete();
        } catch (Exception ignored) { }
    }

    static synchronized Entry remember(Context context, String key, PcmAudioAsset source) {
        if (key == null || source.totalFrames <= 0 || source.totalFrames > Long.MAX_VALUE / source.bytesPerFrame()
                || source.file.length() != source.totalFrames * source.bytesPerFrame()) return new Entry(source, false);
        PcmAudioAsset playable = source;
        try {
            File root = directory(context), pcm = new File(root, "decoded-" + UUID.randomUUID() + ".pcm");
            Files.move(source.file.toPath(), pcm.toPath());
            playable = new PcmAudioAsset(pcm, source.sampleRate, source.channelCount, source.totalFrames);
            JSONObject data = new JSONObject().put("key", key).put("file", pcm.getName())
                    .put("rate", source.sampleRate).put("channels", source.channelCount).put("frames", source.totalFrames);
            AtomicFile index = new AtomicFile(new File(root, "index.json")); FileOutputStream output = null;
            try {
                output = index.startWrite(); output.write(data.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
                index.finishWrite(output); output = null;
            } finally { if (output != null) index.failWrite(output); }
            leases.put(pcm, leases.getOrDefault(pcm, 0) + 1); clean(root, pcm);
            return new Entry(playable, true);
        } catch (Exception exception) {
            if (playable != source) leases.put(playable.file, leases.getOrDefault(playable.file, 0) + 1);
            return new Entry(playable, false);
        }
    }

    static synchronized void release(Entry entry) {
        if (entry == null) return;
        int count = leases.getOrDefault(entry.asset.file, 0);
        if (count <= 1) leases.remove(entry.asset.file); else leases.put(entry.asset.file, count - 1);
    }

    private static void clean(File root, File keep) {
        File[] files = root.listFiles();
        if (files != null) for (File file : files)
            if (file.getName().startsWith("decoded-") && file.getName().endsWith(".pcm")
                    && !file.equals(keep) && !leases.containsKey(file)) file.delete();
    }
}
