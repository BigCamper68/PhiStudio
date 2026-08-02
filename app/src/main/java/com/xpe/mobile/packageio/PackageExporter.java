package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageExporter {
    private static final String REPHIEDIT_INFO_PATH = "info.txt";

    public void exportPackage(ChartPackage chartPackage, ChartDocument editedChart,
                              OutputStream output) throws IOException {
        if (chartPackage == null || editedChart == null) {
            throw new IOException("A loaded chart package is required for package export");
        }
        if (output == null) throw new IOException("Unable to create package output");

        List<ChartPackage.Entry> entries = new ArrayList<>(chartPackage.getEntries());
        entries.sort(Comparator.comparing(ChartPackage.Entry::getPath)
                .thenComparing(ChartPackage.Entry::isDirectory));
        PackageManifest yamlManifest = preferredYamlManifest(chartPackage.getManifests());
        String yamlPath = yamlManifest == null ? null
                : PackageImporter.normalizePath(yamlManifest.getPath());
        boolean manifestWritten = false;
        boolean rePhiInfoWritten = false;
        boolean chartWritten = false;
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (ChartPackage.Entry packageEntry : entries) {
                String path = PackageImporter.normalizePath(packageEntry.getPath());
                ZipEntry outputEntry = new ZipEntry(packageEntry.isDirectory() ? path + "/" : path);
                outputEntry.setTime(0L);
                zip.putNextEntry(outputEntry);
                if (!packageEntry.isDirectory()) {
                    if (path.equals(chartPackage.getChartPath())) {
                        try {
                            writeUtf8(zip, RpePackageCompat.normalize(
                                    editedChart.toJsonString()));
                        } catch (JSONException exception) {
                            throw new IOException("Unable to serialize the edited RPE chart", exception);
                        }
                        chartWritten = true;
                    } else if (path.equals(yamlPath)) {
                        writeUtf8(zip, PhiraManifestCompat.normalize(
                                yamlManifest.getSourceText(), editedChart,
                                chartPackage.getChartPath(), chartPackage.getAudioPath(),
                                chartPackage.getIllustrationPath(),
                                chartPackage.getManifestOffsetMs(),
                                chartPackage.isUseRpe170Speed()));
                        manifestWritten = true;
                    } else if (REPHIEDIT_INFO_PATH.equalsIgnoreCase(path)) {
                        writeUtf8(zip, RePhiEditInfoCompat.normalize(
                                readWorkspaceUtf8(chartPackage, path), editedChart,
                                chartPackage.getChartPath(), chartPackage.getAudioPath(),
                                chartPackage.getIllustrationPath()));
                        rePhiInfoWritten = true;
                    } else {
                        copyWorkspaceEntry(chartPackage, path, zip);
                    }
                }
                zip.closeEntry();
            }
            if (!chartWritten) {
                throw new IOException("The selected RPE chart path is missing from the package entry list");
            }
            if (!manifestWritten) {
                ZipEntry manifestEntry = new ZipEntry("info.yml");
                manifestEntry.setTime(0L);
                zip.putNextEntry(manifestEntry);
                writeUtf8(zip, PhiraManifestCompat.normalize(
                        "", editedChart, chartPackage.getChartPath(),
                        chartPackage.getAudioPath(), chartPackage.getIllustrationPath(),
                        chartPackage.getManifestOffsetMs(), chartPackage.isUseRpe170Speed()));
                zip.closeEntry();
            }
            if (!rePhiInfoWritten) {
                ZipEntry infoEntry = new ZipEntry(REPHIEDIT_INFO_PATH);
                infoEntry.setTime(0L);
                zip.putNextEntry(infoEntry);
                writeUtf8(zip, RePhiEditInfoCompat.normalize(
                        "", editedChart, chartPackage.getChartPath(),
                        chartPackage.getAudioPath(), chartPackage.getIllustrationPath()));
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    private static PackageManifest preferredYamlManifest(List<PackageManifest> manifests)
            throws PackageException {
        PackageManifest fallback = null;
        for (PackageManifest manifest : manifests) {
            if (manifest.getKind() != PackageManifest.Kind.YAML) continue;
            String path = PackageImporter.normalizePath(manifest.getPath());
            if ("info.yml".equalsIgnoreCase(path) || "info.yaml".equalsIgnoreCase(path)) {
                return manifest;
            }
            if (fallback == null) fallback = manifest;
        }
        return fallback;
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readWorkspaceUtf8(ChartPackage chartPackage, String path)
            throws IOException {
        File source = PackageImporter.resolveInside(chartPackage.getWorkspace(), path);
        if (!source.isFile()) return "";
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void copyWorkspaceEntry(ChartPackage chartPackage, String path,
                                           OutputStream output) throws IOException {
        File source = PackageImporter.resolveInside(chartPackage.getWorkspace(), path);
        if (!source.isFile()) {
            throw new IOException("Preserved package entry is missing from the workspace: " + path);
        }
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }
}
