package com.xpe.mobile.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ThumbnailCacheKey {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ThumbnailCacheKey() {
    }

    public static String forIllustration(String projectId, File illustration) throws IOException {
        if (projectId == null || projectId.trim().isEmpty() || illustration == null) {
            throw new IOException("Project ID and illustration are required for thumbnail caching");
        }
        String identity = projectId + "\n" + illustration.getCanonicalPath() + "\n"
                + illustration.length() + "\n" + illustration.lastModified();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                int unsigned = value & 0xff;
                hex.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0f]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
