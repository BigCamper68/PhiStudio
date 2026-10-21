package com.xpe.mobile.packageio;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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

    /**
     * Library rows need the indexed chart header, not every resource in every project.
     * Opening a project still runs the complete inventory, link and size validation.
     */
    public void validateIndexedWorkspace(File workspace, String chartPath) throws IOException {
        Path root = workspaceRoot(workspace);
        String normalized = PackageImporter.normalizePath(chartPath);
        Path chart = root;
        String[] components = normalized.split("/");
        for (int i = 0; i < components.length; i++) {
            chart = chart.resolve(components[i]);
            if (!Files.exists(chart, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Indexed project chart is missing: " + normalized);
            BasicFileAttributes attributes = Files.readAttributes(
                    chart, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) throw new IOException("Symbolic links are not allowed in project workspaces");
            if (i + 1 < components.length) {
                if (!attributes.isDirectory()) throw new IOException("Indexed chart parent is not a directory");
            } else {
                if (!attributes.isRegularFile()) throw new IOException("Indexed project chart is missing: " + normalized);
                if (attributes.size() > limits.maxEntryBytes)
                    throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT, "Indexed chart exceeds the size limit");
            }
        }
        if (!PackageImporter.looksLikeJsonObject(chart.toFile())) {
            throw new IOException("Indexed project chart is not a JSON object: " + normalized);
        }
    }

    private List<ChartPackage.Entry> scanWorkspace(File workspace) throws IOException {
        Path root = workspaceRoot(workspace);
        List<ChartPackage.Entry> entries = new ArrayList<>();
        long[] totalBytes = new long[]{0L};
        scan(root, root, entries, totalBytes);
        return entries;
    }

    private static Path workspaceRoot(File workspace) throws IOException {
        if (workspace == null || !workspace.isDirectory()) throw new IOException("Project workspace is missing");
        if (Files.isSymbolicLink(workspace.toPath())) throw new IOException("Symbolic project workspaces are not allowed");
        return workspace.toPath().toRealPath();
    }

    private void scan(Path root, Path directory, List<ChartPackage.Entry> entries,
                      long[] totalBytes) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (entries.size() + children.size() >= limits.maxEntries)
                    throw new PackageException(PackageException.Code.ENTRY_COUNT_LIMIT,
                            "Workspace has more than " + limits.maxEntries + " entries");
                children.add(child);
            }
        } catch (DirectoryIteratorException exception) {
            throw exception.getCause();
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path child : children) {
            BasicFileAttributes attributes = Files.readAttributes(
                    child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new IOException("Symbolic links are not allowed in project workspaces: " + child.getFileName());
            }
            String path = PackageImporter.normalizePath(root.relativize(child).toString().replace(File.separatorChar, '/'));
            if (entries.size() >= limits.maxEntries) {
                throw new PackageException(PackageException.Code.ENTRY_COUNT_LIMIT,
                        "Workspace has more than " + limits.maxEntries + " entries");
            }
            if (attributes.isDirectory()) {
                entries.add(new ChartPackage.Entry(path, true, 0L));
                scan(root, child, entries, totalBytes);
            } else if (attributes.isRegularFile()) {
                long size = attributes.size();
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

}
