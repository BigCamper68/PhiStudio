package com.xpe.mobile.preview;

import java.io.File;
import java.io.IOException;

/** Resolves an RPE texture name without allowing it to escape the project workspace. */
public final class PreviewTexturePath {
    private PreviewTexturePath() {
    }

    public static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    public static File resolveInside(File workspace, String textureName) {
        if (workspace == null || textureName == null) return null;
        String normalized = normalize(textureName);
        if (normalized == null || normalized.isEmpty() || normalized.indexOf('\0') >= 0
                || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return null;
        }
        String[] components = normalized.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                return null;
            }
        }
        try {
            File root = workspace.getCanonicalFile();
            File candidate = new File(root, normalized).getCanonicalFile();
            String rootPath = root.getPath();
            String candidatePath = candidate.getPath();
            if (!candidatePath.startsWith(rootPath + File.separator)) return null;
            return candidate;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }
}
