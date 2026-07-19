package com.xpe.mobile.packageio;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.xpe.mobile.model.ChartDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class AndroidPackageIo {
    private AndroidPackageIo() {
    }

    public static ChartPackage importPackage(ContentResolver resolver, Uri uri, File workspace)
            throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open selected package");
            return new PackageImporter().importPackage(input, workspace, displayName(resolver, uri));
        }
    }

    public static void exportPackage(ContentResolver resolver, Uri uri, File privateTempDirectory,
                                     ChartPackage chartPackage, ChartDocument editedChart)
            throws IOException {
        if (privateTempDirectory == null
                || (!privateTempDirectory.isDirectory() && !privateTempDirectory.mkdirs())) {
            throw new IOException("Unable to create private export workspace");
        }
        File temporary = File.createTempFile("xpe-package-export-", ".zip", privateTempDirectory);
        try {
            try (OutputStream file = new FileOutputStream(temporary)) {
                new PackageExporter().exportPackage(chartPackage, editedChart, file);
            }
            try (InputStream input = new FileInputStream(temporary);
                 OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Unable to create output package");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        } finally {
            temporary.delete();
        }
    }

    public static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the last URI segment for providers without query support.
        }
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty() ? "Imported package.zip" : segment;
    }
}
