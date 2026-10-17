package com.xpe.mobile.packageio;

import com.xpe.mobile.model.HitSoundReference;
import com.xpe.mobile.model.Note;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Package-relative, traversal-safe note sound references. */
public final class PackageHitSoundCatalog {
    private PackageHitSoundCatalog() { }

    public static boolean isAudioPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav")
                || lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".opus");
    }

    public static File resolve(ChartPackage pack, String reference) throws IOException {
        if (pack == null) throw new IOException("A chart package is required for custom hit sounds");
        String normalized = PackageImporter.normalizePath(reference);
        if (!isAudioPath(normalized)) throw new IOException("Unsupported hit-sound file extension");
        boolean found = false;
        for (ChartPackage.Entry entry : pack.getEntries()) {
            if (!entry.isDirectory() && entry.getPath().equals(normalized)) { found = true; break; }
        }
        if (!found) throw new IOException("Hit-sound file is missing from the package: " + reference);
        File file = PackageImporter.resolveInside(pack.getWorkspace(), normalized);
        if (!file.isFile()) throw new IOException("Hit-sound file is unavailable: " + reference);
        return file;
    }

    public static boolean isAvailable(ChartPackage pack, String reference) {
        if (!HitSoundReference.isCustom(reference)) return true;
        try { resolve(pack, reference); return true; }
        catch (IOException | RuntimeException ignored) { return false; }
    }

    public static List<String> choices(ChartPackage pack) {
        List<String> choices = new ArrayList<>();
        choices.add(HitSoundReference.CLICK);
        choices.add(HitSoundReference.FLICK);
        choices.add(HitSoundReference.DRAG);
        if (pack != null) {
            Set<String> paths = new TreeSet<>();
            for (ChartPackage.Entry entry : pack.getEntries()) {
                String path = entry.getPath();
                if (!entry.isDirectory() && isAudioPath(path)
                        && !path.equals(pack.getAudioPath()) && HitSoundReference.isCustom(path)) {
                    paths.add(path);
                }
            }
            choices.addAll(paths);
        }
        return Collections.unmodifiableList(choices);
    }

    public static Set<String> referencedPaths(ChartPackage pack) {
        Set<String> result = new TreeSet<>();
        if (pack != null) for (var line : pack.getChart().judgeLines) for (Note note : line.notes) {
            if (!note.fake && HitSoundReference.isCustom(note.hitSound)) result.add(note.hitSound);
        }
        return result;
    }
}
