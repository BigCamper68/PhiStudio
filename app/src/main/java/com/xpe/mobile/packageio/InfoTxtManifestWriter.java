package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes the legacy RPE/Phira text manifest required by PEZ conversion tools. */
final class InfoTxtManifestWriter {
    static final String FILE_NAME = "info.txt";

    private InfoTxtManifestWriter() {
    }

    static void write(ChartPackage chartPackage, ChartDocument chart,
                      OutputStream output) throws IOException {
        PackageManifest previous = previousTextManifest(chartPackage);
        String chartPath = clean(chartPackage.getChartPath());
        String audioPath = firstNonBlank(chartPackage.getAudioPath(), chart.song);
        String picturePath = firstNonBlank(
                chartPackage.getIllustrationPath(), chart.background);
        String pathId = firstNonBlank(value(previous, "path"), stem(chartPath));

        StringBuilder text = new StringBuilder(320);
        text.append("#\n");
        append(text, "Name", chart.name);
        append(text, "Path", pathId);
        append(text, "Song", audioPath);
        append(text, "Picture", picturePath);
        append(text, "Chart", chartPath);
        append(text, "Level", chart.level);
        append(text, "Composer", chart.composer);
        append(text, "Charter", chart.charter);
        append(text, "LastEditTime", firstNonBlank(
                value(previous, "lastedittime"), "1970_1_1_0_0_0_"));
        append(text, "Length", firstNonBlank(value(previous, "length"), "0.000"));
        append(text, "EditTime", firstNonBlank(value(previous, "edittime"), "0.000"));
        append(text, "Group", firstNonBlank(value(previous, "group"), "Default"));
        output.write(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static boolean isInfoTxt(String path) {
        return path != null && path.indexOf('/') < 0
                && FILE_NAME.equalsIgnoreCase(path);
    }

    private static PackageManifest previousTextManifest(ChartPackage chartPackage) {
        for (PackageManifest manifest : chartPackage.getManifests()) {
            if (manifest.getKind() == PackageManifest.Kind.TEXT
                    && isInfoTxt(manifest.getPath())) return manifest;
        }
        return null;
    }

    private static String value(PackageManifest manifest, String key) {
        return manifest == null ? null : manifest.get(key);
    }

    private static void append(StringBuilder target, String key, String value) {
        target.append(key).append(": ").append(clean(value)).append('\n');
    }

    private static String firstNonBlank(String primary, String fallback) {
        String value = clean(primary);
        return value.isEmpty() ? clean(fallback) : value;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String stem(String path) {
        String value = clean(path);
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
