package com.xpe.mobile.audio;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Session music is required for resume/seek and must not be evicted as app cache. */
final class PlaybackPcmFiles {
    private static final String PREFIX = "phistudio-audio-";
    private static final Set<String> INITIALIZED = new HashSet<>();

    private PlaybackPcmFiles() { }

    static synchronized File create(File noBackupDirectory) throws IOException {
        if (noBackupDirectory == null) throw new IOException("Private audio storage is unavailable");
        File directory = new File(noBackupDirectory, "audio-playback").getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private audio playback directory");
        }
        // Only the first acquisition in this process removes abandoned previous-session
        // files. A second controller must never remove the current controller's PCM.
        if (INITIALIZED.add(directory.getPath())) {
            File[] abandoned = directory.listFiles(file -> file.isFile()
                    && file.getName().startsWith(PREFIX) && file.getName().endsWith(".pcm"));
            if (abandoned != null) for (File file : abandoned) file.delete();
        }
        return File.createTempFile(PREFIX, ".pcm", directory);
    }
}
