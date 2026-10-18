package com.xpe.mobile.video;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Active export files must survive Android's automatic cache eviction. */
public final class VideoExportStorage {
    private static final Set<File> active = new HashSet<>();
    private static final long RESERVE_BYTES = 32L * 1024 * 1024;

    public static final class LowSpaceException extends IOException {
        public LowSpaceException() { super("Not enough storage for temporary video files"); }
    }

    private VideoExportStorage() { }

    public static File directory(Context context) throws IOException {
        File base = context.getNoBackupFilesDir();
        if (base == null) throw new IOException("Internal video export storage is unavailable");
        File directory = new File(base, "video-exports");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private video export directory");
        }
        return directory;
    }

    public static void requireSpace(File directory, VideoExportOptions options) throws IOException {
        // Encoded tracks and the final muxed MP4 coexist. PCM is checked again after decoding.
        long required = options.estimatedBytes() * 2 + RESERVE_BYTES;
        if (directory.getUsableSpace() < required) throw new LowSpaceException();
    }

    public static synchronized File createWorkspace(Context context) throws IOException {
        File root = directory(context);
        File[] previous = root.listFiles();
        if (previous != null) for (File candidate : previous) {
            if (candidate.getName().startsWith("phistudio-export-") && candidate.isDirectory()
                    && !Files.isSymbolicLink(candidate.toPath()) && !active.contains(candidate)) {
                removeWorkspace(candidate);
            }
        }
        File directory = Files.createTempDirectory(root.toPath(), "phistudio-export-").toFile();
        active.add(directory);
        return directory;
    }

    public static synchronized void removeWorkspace(File directory) {
        for (String name : new String[]{"video.mp4", "audio.m4a", "music.pcm"}) {
            new File(directory, name).delete();
        }
        directory.delete();
        active.remove(directory);
    }
}
