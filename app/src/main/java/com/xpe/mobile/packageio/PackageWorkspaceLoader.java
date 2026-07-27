package com.xpe.mobile.packageio;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class PackageWorkspaceLoader {
    private final PackageLimits limits;

    public PackageWorkspaceLoader() {
        this(PackageLimits.DEFAULT);
    }

    public PackageWorkspaceLoader(PackageLimits limits) {
        this.limits = limits;
    }

    public ChartPackage load(File workspace, String sourceDisplayName) throws IOException {
        List<ChartPackage.Entry> entries = scanWorkspace(workspace);
        return new PackageImporter(limits).inspectWorkspace(workspace, sourceDisplayName, entries);
    }

    public String inspectProjectName(File workspace, String sourceDisplayName) throws IOException {
        List<ChartPackage.Entry> entries = scanWorkspace(workspace);
        return new PackageImporter(limits)
                .inspectWorkspaceProjectName(workspace, sourceDisplayName, entries);
    }

    public void validateIndexedWorkspace(File workspace, String chartPath) throws IOException {
        List<ChartPackage.Entry> entries = scanWorkspace(workspace);
        String normalized = PackageImporter.normalizePath(chartPath);
        boolean found = false;
        for (ChartPackage.Entry entry : entries) {
            if (!entry.isDirectory() && entry.getPath().equals(normalized)) {
                found = true;
                break;
            }
        }
        if (!found) throw new IOException("Indexed project chart is missing: " + normalized);
        File chart = PackageImporter.resolveInside(workspace, normalized);
        if (!PackageImporter.looksLikeJsonObject(chart)) {
            throw new IOException("Indexed project chart is not a JSON object: " + normalized);
        }
    }

    private List<ChartPackage.Entry> scanWorkspace(File workspace) throws IOException {
        if (workspace == null || !workspace.isDirectory()) {
            throw new IOException("Project workspace is missing");
        }
        List<ChartPackage.Entry> entries = new ArrayList<>();
        long[] totalBytes = new long[]{0L};
        scan(workspace, workspace, entries, totalBytes);
        return entries;
    }

    private void scan(File root, File directory, List<ChartPackage.Entry> entries,
                      long[] totalBytes) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Unable to read project workspace");
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IOException("Symbolic links are not allowed in project workspaces: " + child.getName());
            }
            String path = relativePath(root, child);
            if (entries.size() >= limits.maxEntries) {
                throw new PackageException(PackageException.Code.ENTRY_COUNT_LIMIT,
                        "Workspace has more than " + limits.maxEntries + " entries");
            }
            if (child.isDirectory()) {
                entries.add(new ChartPackage.Entry(path, true, 0L));
                scan(root, child, entries, totalBytes);
            } else if (child.isFile()) {
                long size = child.length();
                if (size > limits.maxEntryBytes) {
                    throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT,
                            "Workspace entry exceeds the size limit: " + path);
                }
                totalBytes[0] += size;
                if (totalBytes[0] > limits.maxTotalBytes) {
                    throw new PackageException(PackageException.Code.TOTAL_SIZE_LIMIT,
                            "Workspace exceeds the total extracted-size limit");
                }
                entries.add(new ChartPackage.Entry(path, false, size));
            } else {
                throw new IOException("Unsupported workspace entry: " + path);
            }
        }
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Workspace entry escapes the project directory");
        }
        String relative = filePath.substring(rootPath.length() + 1)
                .replace(File.separatorChar, '/');
        return PackageImporter.normalizePath(relative);
    }
}
