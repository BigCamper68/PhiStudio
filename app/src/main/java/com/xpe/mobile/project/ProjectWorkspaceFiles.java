package com.xpe.mobile.project;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** ProjectWorkspaceFiles responsibilities extracted from ProjectLibrary. */
final class ProjectWorkspaceFiles {
    private final ProjectLibrary delegateOwner;

    ProjectWorkspaceFiles(ProjectLibrary delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    File resolveProjectDirectory(String id) throws IOException {
        File result = new File(delegateOwner.projectsDirectory, id);
        String parent = delegateOwner.projectsDirectory.getCanonicalPath();
        String child = result.getCanonicalPath();
        if (!child.startsWith(parent + File.separator)) throw new IOException("Unsafe project ID");
        return result;
    }

    static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("Invalid project ID");
        }
    }

    static String safeName(String value) {
        String normalized = ProjectWorkspaceFiles.sanitizeDisplayText(value, 160);
        return normalized.isEmpty() ? "Imported project" : normalized;
    }

    static String safeSourceName(String value) {
        String normalized = ProjectWorkspaceFiles.sanitizeDisplayText(value, 240);
        return normalized.isEmpty() ? "Imported package.zip" : normalized;
    }

    static String normalizeLocalName(String value) throws IOException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IOException("Project name cannot be empty");
        if (trimmed.length() > 160) throw new IOException("Project name is too long");
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IOException("Project name cannot contain control characters");
            }
        }
        return trimmed;
    }

    static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    static boolean isSafeWorkspaceChildName(String name) {
        return name != null && name.matches("[A-Za-z0-9._-]{1,160}")
                && !name.equals(".") && !name.equals("..");
    }

    static String nextCopyName(String sourceName, List<ProjectRecord> records) {
        Set<String> names = new HashSet<>();
        for (ProjectRecord record : records) names.add(record.getName().toLowerCase(Locale.ROOT));
        String suffixText = " — Copy";
        String baseSource = sourceName.length() > 160 - suffixText.length()
                ? sourceName.substring(0, 160 - suffixText.length()).trim() : sourceName;
        String base = baseSource + suffixText;
        String candidate = base;
        int suffix = 2;
        while (names.contains(candidate.toLowerCase(Locale.ROOT))) {
            String numberedSuffix = " " + suffix++;
            String shortened = base.length() > 160 - numberedSuffix.length()
                    ? base.substring(0, 160 - numberedSuffix.length()).trim() : base;
            candidate = shortened + numberedSuffix;
        }
        return candidate;
    }

    static String sanitizeDisplayText(String value, int maximumLength) {
        String input = value == null ? "" : value.trim();
        StringBuilder sanitized = new StringBuilder(Math.min(input.length(), maximumLength));
        boolean previousWhitespace = false;
        for (int index = 0; index < input.length() && sanitized.length() < maximumLength; index++) {
            char character = input.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                if (!previousWhitespace && sanitized.length() > 0) sanitized.append(' ');
                previousWhitespace = true;
            } else {
                sanitized.append(character);
                previousWhitespace = false;
            }
        }
        return sanitized.toString().trim();
    }

    static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    static void moveDirectory(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    static void cleanupAfterFailedDuplicate(File directory, Throwable original) {
        try {
            ProjectWorkspaceFiles.deleteRecursively(directory);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    void deleteProjectDirectory(File directory) throws IOException {
        if (!directory.exists()) return;
        String parent = delegateOwner.projectsDirectory.getCanonicalPath();
        if (Files.isSymbolicLink(directory.toPath())) {
            File directParent = directory.getAbsoluteFile().getParentFile();
            if (directParent == null || !directParent.getCanonicalPath().equals(parent)) {
                throw new IOException("Unsafe project symbolic link");
            }
            Files.delete(directory.toPath());
            return;
        }
        String child = directory.getCanonicalPath();
        if (!child.startsWith(parent + File.separator)) throw new IOException("Unsafe project directory");
        ProjectWorkspaceFiles.deleteRecursively(directory);
    }

    static void deleteRecursively(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) ProjectWorkspaceFiles.deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Unable to remove local project data");
    }
}
