package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;
import com.xpe.mobile.preview.NoiseFieldRpeExport;

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
        exportPackage(chartPackage, editedChart, output, Double.NaN,
                NoiseFieldRpeExport.hasCustomColors(editedChart));
    }

    /** Explicit export target; workspace/autosave and the imported archive stay untouched. */
    public void exportPackage(ChartPackage chartPackage, ChartDocument editedChart,
                              OutputStream output, double aspect, boolean phiraProColors) throws IOException {
        if (chartPackage == null || editedChart == null) {
            throw new IOException("A loaded chart package is required for package export");
        }
        if (output == null) throw new IOException("Unable to create package output");

        List<ChartPackage.Entry> entries = new ArrayList<>(chartPackage.getEntries());
        String dimPath = BackgroundDimManifestWriter.path(chartPackage);
        String dimText = PackageMetadataManifestWriter.text(chartPackage, editedChart,
                BackgroundDimManifestWriter.text(chartPackage, editedChart));
        if (Double.isFinite(aspect)) {
            if (aspect < .25 || aspect > 4) throw new IOException("Invalid export aspect ratio");
            if (dimText == null) dimText = BackgroundDimManifestWriter.create(chartPackage, editedChart);
            dimText = PackageMetadataManifestWriter.scalar(dimText, "aspectRatio", Double.toString(aspect));
            dimText = PackageMetadataManifestWriter.scalar(dimText, "forceAspectRatio", "true");
        }
        NoiseFieldRpeExport noise = null;
        String textureFolder = "phistudio-noise";
        if (phiraProColors && !editedChart.noiseFields.isEmpty()) {
            for (int index = 1; hasFolder(entries, textureFolder); index++) textureFolder = "phistudio-noise-" + index;
            try { noise = new NoiseFieldRpeExport(editedChart, textureFolder); }
            catch (JSONException ex) { throw new IOException("Unable to export Phira-Pro noise fields", ex); }
            if (dimText == null) dimText = BackgroundDimManifestWriter.create(chartPackage, editedChart);
            dimText = PackageMetadataManifestWriter.scalar(dimText, "format", "rpe");
        }
        if (dimText != null && entries.stream().noneMatch(entry -> entry.getPath().equals(dimPath))) {
            entries.add(new ChartPackage.Entry(dimPath, false, 0));
        }
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
                            editedChart.writeJson(writer, noise == null ? null : noise.lines,
                                    noise == null ? null : noise.source);
                            writer.flush();
                        } catch (JSONException exception) {
                            throw new IOException("Unable to serialize the edited RPE chart", exception);
                        }
                        chartWritten = true;
                    } else if (path.equals(dimPath) && dimText != null) {
                        zip.write(dimText.getBytes(StandardCharsets.UTF_8));
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
            if (noise != null) {
                // A 1x1 guide keeps scale in canvas pixels; runtime reads its dimensions only.
                byte[] png = guidePng();
                for (int invert = 0; invert < 2; invert++) {
                    ZipEntry guide = new ZipEntry(textureFolder + "/isSubtract" + invert + ".png");
                    guide.setTime(0L); zip.putNextEntry(guide); zip.write(png); zip.closeEntry();
                }
            }
            zip.finish();
        }
    }

    private static boolean hasFolder(List<ChartPackage.Entry> entries, String folder) {
        for (ChartPackage.Entry entry : entries)
            if (entry.getPath().equals(folder) || entry.getPath().startsWith(folder + "/")) return true;
        return false;
    }

    private static byte[] guidePng() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream data = new java.io.DataOutputStream(bytes);
        data.writeLong(0x89504e470d0a1a0aL);
        java.io.ByteArrayOutputStream headerBytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream header = new java.io.DataOutputStream(headerBytes);
        header.writeInt(1); header.writeInt(1); header.write(new byte[]{8, 6, 0, 0, 0});
        pngChunk(data, "IHDR", headerBytes.toByteArray());
        java.io.ByteArrayOutputStream pixels = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream compressed = new java.util.zip.DeflaterOutputStream(pixels)) {
            compressed.write(new byte[]{0, -1, -1, -1, -1});
        }
        pngChunk(data, "IDAT", pixels.toByteArray()); pngChunk(data, "IEND", new byte[0]);
        return bytes.toByteArray();
    }
    private static void pngChunk(java.io.DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(name); crc.update(data);
        out.writeInt(data.length); out.write(name); out.write(data); out.writeInt((int) crc.getValue());
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
