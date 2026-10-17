package com.xpe.mobile.packageio;

import com.xpe.mobile.packageio.PackageImporter.ArchiveLimitIOException;
import com.xpe.mobile.packageio.PackageImporter.BoundedArchiveInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** PackageArchiveReader responsibilities extracted from PackageImporter. */
final class PackageArchiveReader {
    private final PackageImporter delegateOwner;

    PackageArchiveReader(PackageImporter delegateOwner) {
        this.delegateOwner = delegateOwner;
    }

    List<ChartPackage.Entry> extract(InputStream source, File workspace) throws IOException {
        List<ChartPackage.Entry> entries = new ArrayList<>();
        Map<String, Boolean> knownPaths = new HashMap<>();
        long totalBytes = 0L;
        BoundedArchiveInputStream bounded = new BoundedArchiveInputStream(source, delegateOwner.limits.maxArchiveBytes);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(bounded))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= delegateOwner.limits.maxEntries) {
                    throw new PackageException(PackageException.Code.ENTRY_COUNT_LIMIT,
                            "Package has more than " + delegateOwner.limits.maxEntries + " entries");
                }
                if (entry.getCompressedSize() > delegateOwner.limits.maxCompressedEntryBytes) {
                    throw new PackageException(PackageException.Code.COMPRESSED_SIZE_LIMIT,
                            "Compressed entry is too large: " + entry.getName());
                }
                String path = PackageImporter.normalizePath(entry.getName());
                boolean directory = entry.isDirectory() || entry.getName().endsWith("/")
                        || entry.getName().endsWith("\\");
                PackageArchiveReader.registerPath(knownPaths, path, directory);
                File output = PackageImporter.resolveInside(workspace, path);
                if (directory) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new PackageException(PackageException.Code.WORKSPACE_ERROR,
                                "Unable to create workspace directory: " + path);
                    }
                    entries.add(new ChartPackage.Entry(path, true, 0L));
                    zip.closeEntry();
                    continue;
                }

                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new PackageException(PackageException.Code.WORKSPACE_ERROR,
                            "Unable to create workspace directory for: " + path);
                }
                long entryBytes = 0L;
                try (FileOutputStream file = new FileOutputStream(output)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        entryBytes += count;
                        totalBytes += count;
                        if (entryBytes > delegateOwner.limits.maxEntryBytes) {
                            throw new PackageException(PackageException.Code.ENTRY_SIZE_LIMIT,
                                    "Entry exceeds the " + delegateOwner.limits.maxEntryBytes + " byte limit: " + path);
                        }
                        if (totalBytes > delegateOwner.limits.maxTotalBytes) {
                            throw new PackageException(PackageException.Code.TOTAL_SIZE_LIMIT,
                                    "Package exceeds the " + delegateOwner.limits.maxTotalBytes + " byte extracted-size limit");
                        }
                        file.write(buffer, 0, count);
                    }
                }
                entries.add(new ChartPackage.Entry(path, false, entryBytes));
                zip.closeEntry();
            }
        } catch (ArchiveLimitIOException exception) {
            throw new PackageException(PackageException.Code.ARCHIVE_SIZE_LIMIT,
                    "Package exceeds the " + delegateOwner.limits.maxArchiveBytes + " byte compressed-size limit", exception);
        }
        return entries;
    }

    static void registerPath(Map<String, Boolean> known, String path, boolean directory)
            throws PackageException {
        if (known.containsKey(path)) PackageImporter.duplicate(path);
        int slash = path.indexOf('/');
        while (slash >= 0) {
            String parent = path.substring(0, slash);
            if (Boolean.FALSE.equals(known.get(parent))) PackageImporter.duplicate(path);
            slash = path.indexOf('/', slash + 1);
        }
        if (!directory) {
            String prefix = path + "/";
            for (String existing : known.keySet()) {
                if (existing.startsWith(prefix)) PackageImporter.duplicate(path);
            }
        }
        known.put(path, directory);
    }

    static void duplicate(String path) throws PackageException {
        throw new PackageException(PackageException.Code.DUPLICATE_PATH,
                "Package contains duplicate or conflicting normalized path: " + path);
    }
}
