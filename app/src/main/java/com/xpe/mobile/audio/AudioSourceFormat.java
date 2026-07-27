package com.xpe.mobile.audio;

import java.util.Locale;

public final class AudioSourceFormat {
    private AudioSourceFormat() {
    }

    public static boolean isMp3(String sourceName, String mimeType) {
        String normalizedName = sourceName == null
                ? "" : sourceName.toLowerCase(Locale.ROOT);
        int query = normalizedName.indexOf('?');
        if (query >= 0) normalizedName = normalizedName.substring(0, query);
        int fragment = normalizedName.indexOf('#');
        if (fragment >= 0) normalizedName = normalizedName.substring(0, fragment);
        if (normalizedName.endsWith(".mp3")) return true;

        String normalizedMime = mimeType == null
                ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        int separator = normalizedMime.indexOf(';');
        if (separator >= 0) normalizedMime = normalizedMime.substring(0, separator).trim();
        return normalizedMime.equals("audio/mpeg")
                || normalizedMime.equals("audio/mp3")
                || normalizedMime.equals("audio/x-mp3")
                || normalizedMime.equals("audio/x-mpeg");
    }
}
