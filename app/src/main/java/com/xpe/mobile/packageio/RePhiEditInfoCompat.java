package com.xpe.mobile.packageio;

import com.xpe.mobile.model.ChartDocument;

import java.util.Locale;

/** Generates the root info.txt index expected by Re:PhiEdit .pez import. */
final class RePhiEditInfoCompat {
    private RePhiEditInfoCompat() {
    }

    static String normalize(String source, ChartDocument chart, String chartPath,
                            String audioPath, String illustrationPath) {
        String original = source == null ? "" : source;
        // Existing Re:PhiEdit metadata can contain editor-specific fields and timestamps.
        // Preserve it verbatim because package asset paths do not change during export.
        if (!original.trim().isEmpty()) return original;

        ChartDocument value = chart == null ? new ChartDocument() : chart;
        String safeChartPath = path(chartPath, "chart.json");
        StringBuilder output = new StringBuilder(320);
        output.append("#\n");
        field(output, "Name", text(value.name, "Untitled"));
        field(output, "Path", chartStem(safeChartPath));
        field(output, "Song", path(audioPath, "audio.mp3"));
        field(output, "Picture", path(illustrationPath, "illustration.png"));
        field(output, "Chart", safeChartPath);
        field(output, "Level", text(value.level, "UK Lv.10"));
        field(output, "Composer", text(value.composer, "Unknown"));
        field(output, "Charter", text(value.charter, "Unknown"));
        field(output, "LastEditTime", "1970_1_1_0_0_0_");
        field(output, "Length", "0");
        field(output, "EditTime", "0");
        field(output, "Group", "Default");
        return output.toString();
    }

    private static void field(StringBuilder output, String key, String value) {
        output.append(key).append(": ").append(singleLine(value)).append('\n');
    }

    private static String chartStem(String chartPath) {
        String value = path(chartPath, "chart.json");
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String path(String value, String fallback) {
        String normalized = text(value, fallback).replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String text(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String singleLine(String value) {
        String safe = value == null ? "" : value;
        StringBuilder output = new StringBuilder(safe.length());
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if (character == '\r' || character == '\n' || Character.isISOControl(character)) {
                output.append(' ');
            } else {
                output.append(character);
            }
        }
        return output.toString().trim();
    }
}
