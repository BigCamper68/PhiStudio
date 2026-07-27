package com.xpe.mobile.packageio;

public final class PackageLimits {
    public static final PackageLimits DEFAULT = new PackageLimits(
            1024,
            128L * 1024L * 1024L,
            512L * 1024L * 1024L,
            128L * 1024L * 1024L,
            256L * 1024L * 1024L);

    public final int maxEntries;
    public final long maxEntryBytes;
    public final long maxTotalBytes;
    public final long maxCompressedEntryBytes;
    public final long maxArchiveBytes;

    public PackageLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        this(maxEntries, maxEntryBytes, maxTotalBytes, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public PackageLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes,
                         long maxCompressedEntryBytes, long maxArchiveBytes) {
        if (maxEntries < 1 || maxEntryBytes < 1L || maxTotalBytes < 1L
                || maxCompressedEntryBytes < 1L || maxArchiveBytes < 1L) {
            throw new IllegalArgumentException("Package limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxCompressedEntryBytes = maxCompressedEntryBytes;
        this.maxArchiveBytes = maxArchiveBytes;
    }
}
