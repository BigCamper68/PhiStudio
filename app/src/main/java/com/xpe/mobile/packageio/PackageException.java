package com.xpe.mobile.packageio;

import java.io.File;
import java.io.IOException;

public final class PackageException extends IOException {
    public enum Code {
        UNSAFE_PATH,
        DUPLICATE_PATH,
        ENTRY_COUNT_LIMIT,
        ENTRY_SIZE_LIMIT,
        TOTAL_SIZE_LIMIT,
        COMPRESSED_SIZE_LIMIT,
        ARCHIVE_SIZE_LIMIT,
        INVALID_MANIFEST_PATH,
        MISSING_CHART,
        AMBIGUOUS_CHART,
        UNSUPPORTED_CHART_FORMAT,
        WORKSPACE_ERROR
    }

    private final Code code;
    private final File retainedWorkspace;

    public PackageException(Code code, String message) {
        this(code, message, null, null);
    }

    public PackageException(Code code, String message, Throwable cause) {
        this(code, message, cause, null);
    }

    public PackageException(Code code, String message, File retainedWorkspace) {
        this(code, message, null, retainedWorkspace);
    }

    private PackageException(Code code, String message, Throwable cause, File retainedWorkspace) {
        super(message, cause);
        this.code = code;
        this.retainedWorkspace = retainedWorkspace;
    }

    public Code getCode() {
        return code;
    }

    public File getRetainedWorkspace() {
        return retainedWorkspace;
    }
}
