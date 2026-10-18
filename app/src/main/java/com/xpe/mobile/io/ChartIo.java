package com.xpe.mobile.io;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import com.xpe.mobile.model.ChartDocument;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public final class ChartIo {
    private ChartIo() {
    }

    public static ChartDocument readChart(ContentResolver resolver, Uri uri) throws IOException, JSONException {
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("Unable to open selected document");
            return ChartDocument.fromJson(readUtf8(stream));
        }
    }

    public static ChartDocument readAsset(Context context, String name) throws IOException, JSONException {
        try (InputStream stream = context.getAssets().open(name)) {
            return ChartDocument.fromJson(readUtf8(stream));
        }
    }

    public static void writeChart(Context context, Uri uri, ChartDocument chart) throws IOException, JSONException {
        File staging = File.createTempFile("phistudio-chart-export-", ".json", context.getCacheDir());
        try {
            try (OutputStream stream = new FileOutputStream(staging)) { writeUtf8(stream, chart); }
            try (InputStream input = new FileInputStream(staging);
                 OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Unable to create output document");
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        } finally { staging.delete(); }
    }

    private static AtomicFile autosave(Context context) {
        return new AtomicFile(context.getFileStreamPath("autosave.json"));
    }

    public static void writeAutosave(Context context, ChartDocument chart) throws IOException, JSONException {
        AtomicFile file = autosave(context);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            writeUtf8(stream, chart);
            stream.getFD().sync();
            file.finishWrite(stream);
            stream = null;
        } finally {
            if (stream != null) file.failWrite(stream);
        }
    }

    public static ChartDocument readAutosave(Context context) throws IOException, JSONException {
        try (InputStream stream = autosave(context).openRead()) {
            return ChartDocument.fromJson(readUtf8(stream));
        }
    }

    private static String readUtf8(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        String text = output.toString(StandardCharsets.UTF_8.name());
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') return text.substring(1);
        return text;
    }

    private static void writeUtf8(OutputStream stream, ChartDocument chart)
            throws IOException, JSONException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(
                stream, StandardCharsets.UTF_8), 32 * 1024);
        chart.writeJson(writer);
        writer.flush();
    }
}
