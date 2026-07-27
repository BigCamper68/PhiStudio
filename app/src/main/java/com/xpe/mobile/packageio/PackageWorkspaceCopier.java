package com.xpe.mobile.packageio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

public final class PackageWorkspaceCopier {
    private final PackageLimits limits;

    public PackageWorkspaceCopier() {
        this(PackageLimits.DEFAULT);
    }

    public PackageWorkspaceCopier(PackageLimits limits) {
        this.limits = limits;
    }

    public void copy(File source, File destination) throws IOException {
        if (source == null || !source.isDirectory()) throw new IOException("Source project workspace is missing");
        if (Files.isSymbolicLink(source.toPath())) throw new IOException("Source workspace cannot be a symbolic link");
        if (destination == null || destination.exists()) throw new IOException("Duplicate workspace already exists");
        if (!destination.mkdirs()) throw new IOException("Unable to create duplicate workspace");
        long[] totals = new long[]{0L, 0L};
        try {
            copyDirectory(source, source, destination, totals);
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
            deleteRecursively(destination);
            throw exception;
        }
    }

    private void copyDirectory(File root, File sourceDirectory, File destinationRoot,
                               long[] totals) throws IOException {
        File[] children = sourceDirectory.listFiles();
        if (children == null) throw new IOException("Unable to read source workspace");
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (++totals[0] > limits.maxEntries) {
                throw new PackageException(PackageException.Code.ENTRY_COUNT_LIMIT,
                        "Project has more than " + limits.maxEntries + " entries");
            }
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IOException("Symbolic links are not allowed in project workspaces: " + child.getName());
            }
            String relative = relativePath(root, child);
            File destination = new File(destinationRoot, relative.replace('/', File.separatorChar));
            ensureInside(destinationRoot, destination);
            if (child.isDirectory()) {
                if (!destination.mkdirs()) throw new IOException("Unable to create duplicate directory: " + relative);
                copyDirectory(root, child, destinationRoot, totals);
                destination.setLastModified(child.lastModified());
            } else if (child.isFile()) {
                long size = child.length();
                if (size > limits.maxEntryBytes) {
                    throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT,
                            "Workspace entry exceeds the size limit: " + relative);
                }
                totals[1] += size;
                if (totals[1] > limits.maxTotalBytes) {
                    throw new PackageException(PackageException.Code.TOTAL_SIZE_LIMIT,
                            "Workspace exceeds the total extracted-size limit");
                }
                File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Unable to create duplicate directory: " + relative);
                }
                copyFile(child, destination);
                destination.setLastModified(child.lastModified());
            } else {
                throw new IOException("Unsupported workspace entry: " + relative);
            }
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        }
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Workspace entry escapes the source project");
        }
        return PackageImporter.normalizePath(filePath.substring(rootPath.length() + 1)
                .replace(File.separatorChar, '/'));
    }

    private static void ensureInside(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Duplicate path escapes the destination workspace");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
