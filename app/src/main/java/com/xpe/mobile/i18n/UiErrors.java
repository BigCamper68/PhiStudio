package com.xpe.mobile.i18n;

import android.content.Context;
import com.xpe.mobile.R;
import com.xpe.mobile.packageio.PackageException;
import com.xpe.mobile.project.ProjectLibrary;
import com.xpe.mobile.video.VideoExportOptions;
import com.xpe.mobile.playtest.PlaytestOptions;
import com.xpe.mobile.video.VideoExportStorage;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.util.Locale;
import java.nio.file.NoSuchFileException;

/** Domain codes stay Android-free; technical details remain available after a translated explanation. */
public final class UiErrors {
    private UiErrors() { }
    public static final class ValidationException extends IllegalArgumentException {
        public final int resource;
        public ValidationException(int resource) { super("UI validation failed"); this.resource = resource; }
    }
    public static String describe(Context context, Throwable error) {
        if (error instanceof ValidationException)
            return UiText.string(context, ((ValidationException) error).resource);
        if (error instanceof PlaytestOptions.ValidationException) {
            int resource = ((PlaytestOptions.ValidationException) error).reason == PlaytestOptions.ValidationError.NUMBER
                    ? R.string.playtest_invalid_number : R.string.playtest_invalid_time;
            return UiText.string(context, resource);
        }
        if (error instanceof VideoExportOptions.ValidationException) {
            int resource = switch (((VideoExportOptions.ValidationException) error).reason) {
                case RESOLUTION -> R.string.video_validation_resolution;
                case FPS -> R.string.video_validation_fps;
                case BITRATE -> R.string.video_validation_bitrate;
                case TIME_RANGE -> R.string.video_validation_time_range;
                case VOLUME -> R.string.video_validation_volume;
            };
            return UiText.string(context, resource);
        }
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
        if (!(error instanceof PackageException)) return detail;
        int resource = switch (((PackageException) error).getCode()) {
            case UNSAFE_PATH, INVALID_MANIFEST_PATH -> R.string.package_error_unsafe_path;
            case DUPLICATE_PATH -> R.string.package_error_duplicate_path;
            case ENTRY_COUNT_LIMIT, ENTRY_SIZE_LIMIT, TOTAL_SIZE_LIMIT, COMPRESSED_SIZE_LIMIT, ARCHIVE_SIZE_LIMIT -> R.string.package_error_limit;
            case MISSING_CHART -> R.string.package_error_missing_chart;
            case AMBIGUOUS_CHART -> R.string.package_error_ambiguous_chart;
            case UNSUPPORTED_CHART_FORMAT -> R.string.package_error_unsupported;
            case WORKSPACE_ERROR -> R.string.package_error_workspace;
        };
        return UiText.string(context, resource, detail);
    }
    public static String workspace(Context context, ProjectLibrary.WorkspaceIssue issue) {
        int resource = switch (issue.getReason()) {
            case MISSING_WORKSPACE -> R.string.workspace_missing;
            case UNSAFE_WORKSPACE -> R.string.workspace_unsafe;
            case RECOVERABLE_WORKSPACE -> R.string.workspace_recoverable;
            case TECHNICAL_DETAIL -> 0;
        };
        return resource == 0 ? issue.getMessage() : UiText.string(context, resource);
    }

    public static String video(Context context, Throwable error) {
        boolean missingTemporary = false;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            String lower = message.toLowerCase(Locale.ROOT);
            boolean noSpace = cause instanceof VideoExportStorage.LowSpaceException
                    || cause instanceof ErrnoException && (((ErrnoException) cause).errno == OsConstants.ENOSPC
                    || ((ErrnoException) cause).errno == OsConstants.EDQUOT)
                    || message.contains("ENOSPC") || message.contains("EDQUOT")
                    || lower.contains("no space left on device") || lower.contains("disk quota exceeded");
            if (noSpace) return UiText.string(context, R.string.video_storage_full);
            boolean missing = cause instanceof NoSuchFileException
                    || cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.ENOENT
                    || message.contains("ENOENT") || lower.contains("no such file or directory");
            if (missing && (message.contains("phistudio-export-") || message.contains("phistudio-video-"))) {
                missingTemporary = true;
            }
        }
        if (missingTemporary) return UiText.string(context, R.string.video_temporary_missing);
        return describe(context, error);
    }
}
