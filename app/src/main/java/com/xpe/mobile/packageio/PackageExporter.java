package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageExporter {
    public void exportPackage(ChartPackage chartPackage, ChartDocument editedChart,
                              OutputStream output) throws IOException {
        if (chartPackage == null || editedChart == null) {
            throw new IOException("A loaded chart package is required for package export");
        }
        if (output == null) throw new IOException("Unable to create package output");

        List<ChartPackage.Entry> entries = new ArrayList<>(chartPackage.getEntries());
        entries.sort(Comparator.comparing(ChartPackage.Entry::getPath)
                .thenComparing(ChartPackage.Entry::isDirectory));
        boolean chartWritten = false;
        boolean infoTxtWritten = false;
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (ChartPackage.Entry packageEntry : entries) {
                String path = PackageImporter.normalizePath(packageEntry.getPath());
                if (!packageEntry.isDirectory() && InfoTxtManifestWriter.isInfoTxt(path)) {
                    if (!infoTxtWritten) {
                        writeInfoTxt(zip, chartPackage, editedChart);
                        infoTxtWritten = true;
                    }
                    continue;
                }

                ZipEntry outputEntry = new ZipEntry(packageEntry.isDirectory() ? path + "/" : path);
                outputEntry.setTime(0L);
                zip.putNextEntry(outputEntry);
                if (!packageEntry.isDirectory()) {
                    if (path.equals(chartPackage.getChartPath())) {
                        try {
                            Writer writer = new BufferedWriter(new OutputStreamWriter(
                                    zip, StandardCharsets.UTF_8), 32 * 1024);
                            editedChart.writeJson(writer);
                            writer.flush();
                        } catch (JSONException exception) {
                            throw new IOException("Unable to serialize the edited RPE chart", exception);
                        }
                        chartWritten = true;
                    } else {
                        File source = PackageImporter.resolveInside(chartPackage.getWorkspace(), path);
                        if (!source.isFile()) {
                            throw new IOException("Preserved package entry is missing from the workspace: " + path);
                        }
                        try (InputStream input = new FileInputStream(source)) {
                            byte[] buffer = new byte[32 * 1024];
                            int count;
                            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
            if (!chartWritten) {
                throw new IOException("The selected RPE chart path is missing from the package entry list");
            }
            if (!infoTxtWritten) writeInfoTxt(zip, chartPackage, editedChart);
            zip.finish();
        }
    }

    private static void writeInfoTxt(ZipOutputStream zip, ChartPackage chartPackage,
                                     ChartDocument editedChart) throws IOException {
        ZipEntry entry = new ZipEntry(InfoTxtManifestWriter.FILE_NAME);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        InfoTxtManifestWriter.write(chartPackage, editedChart, zip);
        zip.closeEntry();
    }
}
